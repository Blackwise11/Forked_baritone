/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process;

import baritone.Baritone;
import baritone.api.Settings;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalRunAway;
import baritone.api.process.AutoDefendMode;
import baritone.api.process.ICombatProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.process.combat.HuntFilter;
import baritone.process.combat.MobTactic;
import baritone.process.combat.ProjectileThreat;
import baritone.process.combat.WeaponType;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The skilled PvE fighter. Cheat-style under the hood (direct attack calls, reach buff, smoothed
 * aim) but presented so it looks human. Hunts any mob. Runs a per-tick state machine as a native
 * Baritone process — Baritone is the legs, this is the fighter.
 *
 * <p>States: {@code IDLE → ACQUIRE → (SEARCH) → APPROACH → STRIKE → RECOVER → (RETREAT)}.
 * SEARCH keeps the hunt alive when no target is in range (wander + re-scan + timeout) instead of
 * giving up on the first empty scan.
 *
 * <p>Movement is driven by the {@link PathingCommand} returned from {@link #onTick}; the attack is
 * a direct side-effect via {@link baritone.api.utils.IPlayerController#attack}. Aim goes through
 * {@link baritone.api.behavior.ILookBehavior#updateTarget} every tick. The reach attribute buff is
 * restored in {@link #onLostControl}.
 */
public final class CombatProcess extends BaritoneProcessHelper implements ICombatProcess {

    private enum State { IDLE, ACQUIRE, SEARCH, APPROACH, STRIKE, RECOVER, RETREAT }

    /**
     * Why combat is engaged. {@code MANUAL} is an explicit {@code #hunt} — non-temporary, seizes
     * control and cancels the underlying task. {@code AUTODEFEND} self-activates to clear a mob
     * threatening the bot mid-task — temporary, so it freezes (not cancels) the underlying task
     * and yields back when the threat is gone. {@code NONE} = idle.
     */
    private enum Mode { NONE, MANUAL, AUTODEFEND }

    private static final double SCAN_RANGE = 32.0;
    private static final double PURSUE_RANGE = 64.0;

    // threat detection (client-side; see isHostile — NOT getTarget()-based)
    private static final double MELEE_THREAT = 2.5;    // a hostile this close is about to swing
    private static final double REACTION_RANGE = 8.0;  // notice closing threats within this
    private static final double SWAP_MARGIN = 0.5;     // hysteresis between near-equidistant mobs
    private static final int SWARM_KITE_THRESHOLD = 2; // >=2 hostiles in melee -> kite, don't trade
    private static final int RANGED_CLUMP_THRESHOLD = 2; // >=2 ranged attackers -> can't shield them all, strafe+dodge instead

    // shield timing: lead so the block is up before impact, hold so it doesn't flicker off, and
    // pre-raise during a ranged mob's bow draw so the block is up before it releases.
    private static final int PROJECTILE_POSTHIT_HOLD = 3; // hold the block this many ticks past arrow impact
    private static final int BOW_DRAW_PREBLOCK_AT = 12;   // pre-raise once a mob has drawn this many ticks (skeletons release ~20)
    private static final int BOW_DRAW_HOLD = 4;           // re-armed each tick while a draw is in its pre-release window

    private State state = State.IDLE;
    private Mode mode = Mode.NONE;
    private HuntFilter filter;
    private LivingEntity target;
    private MobTactic tactic;
    private int cooldown = 0;
    private int strikeJitter = 0;
    private int strafeDir = 1;
    private int strafeTicks = 0;
    private boolean prevAimed = false;   // strafe flip edge-detection (flip once per aim-lock, not every tick)
    private BlockPos lastGoalPos = null; // avoid re-issuing the same path every tick

    // weapon / shield / bow bookkeeping
    private WeaponType weaponType = WeaponType.MELEE; // type of the currently equipped weapon
    private boolean shielding = false;                // shield currently raised
    private int bowDrawTicks = 0;                     // ticks spent drawing the current bow shot
    private int priorWeaponSlot = -1;                 // hotbar slot the player held before combat took over
    private int shieldHoldTicks = 0;                  // forced shield-hold remaining (hysteresis so a block doesn't drop before impact)
    private Vec3 shieldAimPoint = null;               // world point to face while blocking (the live threat, not the locked target)

    // per-mob bow-draw tracking: when a ranged mob started drawing, so we can pre-raise near its release
    private final java.util.Map<UUID, Long> bowDrawStart = new java.util.HashMap<>();

    // melee rhythm tracking: a mob that just swung (rising edge of the synced `swinging` flag) is on
    // its attack cooldown — that's the window to drop the shield and strike. We track each mob's last
    // swing tick and the measured interval between swings (self-calibrating; MeleeAttackGoal's
    // adjustedTickDelay(20) is the baseline until two swings are observed). The shield goes up just
    // before the predicted next swing and comes down the instant the mob is on cooldown, so we block
    // the imminent hit and then attack during the cooldown — NOT a held block (which would suppress
    // our own offense and pin us against a clump).
    private final java.util.Map<UUID, Long> meleeLastSwing = new java.util.HashMap<>();      // tick of last detected swing
    private final java.util.Map<UUID, Long> meleeInterval = new java.util.HashMap<>();       // measured cooldown (ticks); absent = unmeasured
    private final java.util.Map<UUID, Boolean> meleePrevSwinging = new java.util.HashMap<>(); // rising-edge detection per mob
    private int meleeShieldStreak = 0;            // consecutive melee-shield ticks with no strike landed
    private boolean meleeHoldThisTick = false;    // set by meleeRhythmHold when it wants the shield this tick
    private boolean retreatPinned = false;        // RETREAT was triggered by the hold-too-long failsafe (re-engage on space, not HP)

    // melee rhythm tuning. MELEE_BLOCK_RANGE is "a melee mob can hit us" (~vanilla attack reach 3.0);
    // outside it we kite and never block. The lead raises the shield just before the predicted swing;
    // the streak cap bails out to RETREAT if a hold runs away (swing undetected / clump re-acquiring).
    private static final int MELEE_BASELINE_INTERVAL = 20;  // until measured (MeleeAttackGoal adjustedTickDelay(20))
    private static final int MELEE_BLOCK_LEAD = 2;          // raise this many ticks before the predicted next swing
    private static final int MELEE_BLOCK_RANGE = 3;         // inside this (squared) a melee mob can hit us -> can't kite
    private static final int MELEE_POSTHIT_HOLD = 3;        // hold past a detected swing so the block covers the hit

    // search / hunt bookkeeping
    private long searchStartTick = -1;
    private long huntStartTick = -1;
    private long tickCount = 0;
    private BlockPos searchAnchor = null;
    private int searchRepathTicks = 0;

    // reach buff bookkeeping so we always restore (client + integrated-server player)
    private double originalReach = Double.NaN;
    private double originalReachServer = Double.NaN;
    private UUID buffedPlayerUuid = null;

    /** Movement goal to return from onTick this tick; null = stand still (REQUEST_PAUSE). */
    private Goal moveGoal;

    public CombatProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public boolean isActive() {
        if (state != State.IDLE) {
            return true;
        }
        // Auto-defend: self-activate when a hostile threatens us while otherwise idle. Side effects
        // in isActive are precedented (FollowProcess.scanWorld); beginAutoDefend sets state !=
        // IDLE so the first line short-circuits the repeated isActive polls within a tick.
        if (shouldAutoDefend()) {
            beginAutoDefend();
            return true;
        }
        return false;
    }

    @Override
    public boolean isTemporary() {
        // AUTODEFEND is temporary so it freezes (not cancels) the task it preempts and yields back
        // cleanly. MANUAL stays non-temporary so an explicit #hunt seizes control as before.
        return mode == Mode.AUTODEFEND;
    }

    @Override
    public double priority() {
        // preempts Mine/Follow (DEFAULT_PRIORITY -1) and Elytra (0); below the pausers (5, 5.1)
        return 1.0;
    }

    @Override
    public Optional<Entity> currentTarget() {
        return Optional.ofNullable((Entity) target);
    }

    // ------------------------------------------------------------- public API

    @Override
    public void hunt(String arg) {
        if (!Baritone.settings().combatEnabled.value) {
            logDirect("Combat is disabled (combatEnabled = false).");
            return;
        }
        Optional<HuntFilter> f = HuntFilter.parse(arg);
        if (f.isEmpty()) {
            if (isActive()) {
                stop();
            }
            logDirect("Unknown mob: " + arg + "  (try a name like zombie, or 'nearest' / 'all')");
            return;
        }
        this.filter = f.get();
        this.mode = Mode.MANUAL;
        this.huntStartTick = tickCount;
        this.searchAnchor = null;
        this.lastGoalPos = null;
        recordPriorWeaponSlot();
        setState(State.ACQUIRE);
        logDirect("Hunting " + filter.describe() + ".");
    }

    @Override
    public void stop() {
        cleanup();
        baritone.getPathingBehavior().cancelEverything();
    }

    @Override
    public void autoDefendMode(AutoDefendMode mode) {
        Baritone.settings().combatAutoDefendMode.value = mode;
        logDirect("Auto-defend: " + mode.name().toLowerCase() + ".");
    }

    @Override
    public AutoDefendMode getAutoDefendMode() {
        return Baritone.settings().combatAutoDefendMode.value;
    }

    @Override
    public void autoDefend(boolean enabled) {
        // back-compat: true = TASK (the original behavior), false = OFF
        autoDefendMode(enabled ? AutoDefendMode.TASK : AutoDefendMode.OFF);
    }

    @Override
    public boolean isAutoDefending() {
        return Baritone.settings().combatAutoDefendMode.value != AutoDefendMode.OFF;
    }

    @Override
    public void onLostControl() {
        cleanup();
    }

    /** Clear all state and restore attributes. Idempotent — safe to call from onLostControl/stop. */
    private void cleanup() {
        boolean wasActive = isActive();
        boolean wasManual = mode == Mode.MANUAL;
        setState(State.IDLE);
        mode = Mode.NONE;
        target = null;
        filter = null;
        lastGoalPos = null;
        searchAnchor = null;
        prevAimed = false;
        stopBowDraw();
        lowerShield();
        restoreReach();
        restorePriorWeaponSlot();
        shieldHoldTicks = 0;
        shieldAimPoint = null;
        bowDrawStart.clear();
        meleeLastSwing.clear();
        meleeInterval.clear();
        meleePrevSwinging.clear();
        meleeShieldStreak = 0;
        meleeHoldThisTick = false;
        retreatPinned = false;
        // force-bow is a test toggle scoped to a single hunt — don't let it linger into the next one
        Baritone.settings().combatForceBow.value = false;
        baritone.getInputOverrideHandler().clearAllKeys();
        // Auto-defend yields quietly via yieldAutoDefend; only a manual hunt announces its end.
        if (wasActive && wasManual) {
            logDirect("Hunt ended.");
        }
    }

    // --------------------------------------------------- auto-defend lifecycle

    /**
     * Should auto-defend self-activate right now? True only when idle (no manual hunt owns us),
     * the feature + combat are enabled, and a hostile is within {@code combatDefendRange}.
     * Passive Endermen are skipped unless they're already aggravated (creepy).
     */
    private boolean shouldAutoDefend() {
        if (mode != Mode.NONE) {
            return false;
        }
        Settings s = Baritone.settings();
        if (!s.combatEnabled.value) {
            return false;
        }
        AutoDefendMode m = s.combatAutoDefendMode.value;
        if (m == AutoDefendMode.OFF) {
            return false;
        }
        if (ctx.player() == null || ctx.world() == null) {
            return false;
        }
        // TASK mode: only self-defend while another task is actively pathing (mining, goto, follow,
        // farm, ...). When idle, leave combat to the player. ALWAYS mode: defend unconditionally.
        if (m == AutoDefendMode.TASK && !baritone.getPathingBehavior().isPathing()) {
            return false;
        }
        LivingEntity t = nearestHostile(s.combatDefendRange.value);
        if (t == null) {
            return false;
        }
        if (t instanceof EnderMan em && !endermanAggro(em)) {
            return false;
        }
        return true;
    }

    /** Begin a self-defense engagement. Bypasses filter/pickTarget — auto-defend uses nearestHostile. */
    private void beginAutoDefend() {
        mode = Mode.AUTODEFEND;
        filter = null;
        huntStartTick = -1; // no duration cap on auto-defend; threatRemains() is its terminator
        lastGoalPos = null;
        searchAnchor = null;
        recordPriorWeaponSlot();
        setState(State.ACQUIRE);
    }

    /**
     * Is there still a hostile worth fighting? The current target is still valid, or another
     * hostile is within the pursuit leash.
     */
    private boolean threatRemains() {
        if (target != null && validTarget()) {
            return true;
        }
        return nearestHostile(Baritone.settings().combatDefendPursueRange.value) != null;
    }

    /**
     * Yield control back to the underlying task. Disarms without stop()/cancelEverything() so the
     * preempted process (e.g. MineProcess) resumes with its state intact. Returns DEFER so this
     * tick cedes control.
     */
    private PathingCommand yieldAutoDefend() {
        target = null;
        lastGoalPos = null;
        searchAnchor = null;
        stopBowDraw();
        lowerShield();
        restoreReach();
        restorePriorWeaponSlot();
        shieldHoldTicks = 0;
        shieldAimPoint = null;
        bowDrawStart.clear();
        meleeLastSwing.clear();
        meleeInterval.clear();
        meleePrevSwinging.clear();
        meleeShieldStreak = 0;
        meleeHoldThisTick = false;
        retreatPinned = false;
        baritone.getInputOverrideHandler().clearAllKeys();
        mode = Mode.NONE;
        setState(State.IDLE);
        logDirect("Threat cleared — resuming.");
        return new PathingCommand(null, PathingCommandType.DEFER);
    }

    /**
     * Whether an Enderman is aggravated toward the player. {@code Mob.target} isn't synced to the
     * client, but Enderman's screaming/creepy state is, so we use that to avoid auto-defending
     * against passive endermen that merely wandered close.
     */
    private static boolean endermanAggro(EnderMan em) {
        try {
            return em.isCreepy();
        } catch (Throwable ignored) {
            // API drift — treat as aggro so we don't ignore a genuinely threatening enderman.
            return true;
        }
    }

    // ------------------------------------------------------------- main loop

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        tickCount++;
        moveGoal = null;
        Player player = ctx.player();
        if (player == null || ctx.world() == null) {
            cleanup();
            return new PathingCommand(null, PathingCommandType.DEFER);
        }

        // Clear last tick's forced movement keys (strafe / jump / sprint) so they never leak. Each
        // state handler re-asserts the ones it wants this tick; this guarantees a clean slate and
        // that exiting a velocity-strike or strafe stops the input immediately.
        clearAuxKeys();

        Settings s = Baritone.settings();

        // ---- auto-defend yield: if no hostile remains in pursuit range, cede control ----
        // Never stop()/cancelEverything() — that would kill the underlying task. Just drop to IDLE
        // and DEFER so MineProcess/etc. resume with their state intact.
        if (mode == Mode.AUTODEFEND && !threatRemains()) {
            return yieldAutoDefend();
        }

        // ---- safety guardrails (reflex, never LLM) ----
        if (player.getHealth() < s.combatRetreatHealth.value) {
            enterRetreat();
            return cmd();
        }
        int attackers = countNearbyHostiles(REACTION_RANGE);
        if (attackers > s.combatMaxAttackers.value
                && player.getHealth() < s.combatRetreatHealth.value + 6.0) {
            // Outnumbered AND not at safe health → back off to create space. A healthy bot keeps
            // fighting the group (kiting + shield + reach handle multiple mobs); we only retreat
            // from a swarm when we're also hurt enough that trading into it is risky. Without the
            // health gate this fired every tick at full HP vs a group, so the bot fled forever and
            // never re-engaged "a lot of mobs" — it just ran until they left the reaction range.
            enterRetreat();
            return cmd();
        }

        // ---- defensive reflex: re-target the nearest hostile threatening us ----
        // The main loop locks onto one target and then ignores everything else, so a second mob
        // walking up to swing would land free hits. Every tick, re-evaluate the nearest hostile
        // within reaction range and swap to it when it's in our face while our locked target
        // isn't, or when it's meaningfully closer (hysteresis). NOT getTarget()-based: Mob.target
        // isn't synced to the client, so we detect threats by hostile type + proximity.
        LivingEntity threat = nearestHostile(REACTION_RANGE);
        if (threat != null && threat != target) {
            double threatDist = threat.distanceToSqr(player);
            boolean threatInMelee = threatDist <= MELEE_THREAT * MELEE_THREAT;
            boolean currentInMelee = target != null
                    && target.distanceToSqr(player) <= MELEE_THREAT * MELEE_THREAT;
            boolean threatCloser = target == null
                    || threatDist < target.distanceToSqr(player) - SWAP_MARGIN * SWAP_MARGIN;
            if ((threatInMelee && !currentInMelee) || threatCloser) {
                target = threat;
                tactic = MobTactic.forEntity(threat);
                lastGoalPos = null;
                // don't swap the hotbar slot while a bow is mid-draw — equipBestWeapon would switch
                // to a melee weapon and cancel the charge. Keep the bow; aim retargets to the new mob.
                if (bowDrawTicks == 0) {
                    equipBestWeapon();
                    applyReachBuff();
                }
                if (threatInMelee && (state == State.SEARCH || state == State.ACQUIRE)) {
                    setState(State.APPROACH);
                }
            }
        }

        // ---- defensive reflex: shield against incoming projectiles / melee swings ----
        // Skipped entirely while a bow is mid-draw: you can't block and draw at once, and aborting
        // the draw every tick (raiseShield calls stopBowDraw) meant the bow never charged — the shot
        // kept getting cancelled "like you switched hotbar". While drawing, strafing handles arrows
        // and the shot completes in ~combatBowDrawTicks. Shielding resumes once the draw releases.
        //
        // CRITICAL: the whole reflex is gated on a shield actually being equipped. raiseShield()
        // no-ops when shieldHand()==null, but if we still return REQUEST_PAUSE here combat freezes
        // every tick (the "auto shield activates even without shield, all combat bugs out" bug) —
        // the hold never arms, the pause fires forever, and no state ever advances.
        // Track melee swing rhythms BEFORE the shield reflex uses them: a mob that just swung is on
        // its attack cooldown (the strike window), and one whose cooldown is expiring is about to
        // swing (block window). Rising edge of the synced `swinging` flag = the mob spent its attack.
        updateMeleeRhythm(player);

        if (bowDrawTicks == 0 && s.combatUseShield.value && shieldHand(player) != null) {
            int fresh = shieldDecision(player);
            if (fresh > 0) {
                shieldHoldTicks = fresh; // (re)arm the hold so the block stays up through impact
            }
            if (shieldHoldTicks > 0) {
                // Failsafe: never hold the shield against melee indefinitely. If we've been blocking
                // for too long without landing a strike (a swing we couldn't detect, or a clump
                // re-acquiring faster than we can exploit), bail out — drop the shield, retreat to
                // reset, and re-engage. Without this the bot pins the shield up against a clump until
                // it breaks. Projectile/ranged holds don't count toward the streak, only melee.
                if (meleeHoldThisTick) {
                    meleeShieldStreak++;
                    if (meleeShieldStreak > Baritone.settings().combatMeleeShieldMaxHold.value) {
                        lowerShield();
                        logDirect("Pinned too long — retreating to reset.");
                        enterRetreat();
                        retreatPinned = true; // re-engage on space-created, not HP recovery
                        return cmd();
                    }
                }
                // Face the live threat, NOT the locked target: a shield only covers the front
                // hemisphere, so to block a skeleton's arrow from the left while we're locked on a
                // zombie ahead, we must look toward the arrow. shieldDecision sets shieldAimPoint to
                // the most imminent threat (incoming projectile, else the drawing mob, else the
                // melee mob about to swing, else nearest).
                if (shieldAimPoint != null) {
                    aimAtPoint(shieldAimPoint);
                } else {
                    LivingEntity face = target != null ? target : nearestHostile(REACTION_RANGE);
                    if (face != null) {
                        aim(face);
                    }
                }
                raiseShield();
                shieldHoldTicks--;
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            // Not holding this tick: if the melee rhythm opened a strike window (mob on cooldown),
            // reset the streak so a later hold starts fresh — we successfully exploited the window.
            if (!meleeHoldThisTick) {
                meleeShieldStreak = 0;
            }
            lowerShield();
        } else {
            // no shield / shield disabled / mid-bow-draw: drop any stale hold so a leftover count
            // from before the shield was unequipped (or before a draw started) can't freeze us later.
            if (shieldHoldTicks > 0) {
                shieldHoldTicks = 0;
            }
            meleeShieldStreak = 0;
            lowerShield();
        }

        // ---- hunt duration failsafe (manual hunts only; auto-defend yields via threatRemains) ----
        int maxDur = s.combatMaxDurationSeconds.value;
        if (mode == Mode.MANUAL && maxDur > 0 && huntStartTick >= 0 && (tickCount - huntStartTick) > maxDur * 20L) {
            logDirect("Hunt timed out after " + maxDur + "s.");
            stop();
            return cmd();
        }

        if (cooldown > 0) cooldown--;

        switch (state) {
            case ACQUIRE -> doAcquire();
            case SEARCH -> doSearch();
            case APPROACH -> doApproach();
            case STRIKE -> doStrike();
            case RECOVER -> doRecover();
            case RETREAT -> doRetreat();
            default -> {
            }
        }

        return cmd();
    }

    /** Build the pathing command from this tick's movement decision. */
    private PathingCommand cmd() {
        if (moveGoal != null) {
            return new PathingCommand(moveGoal, PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }
        // no movement desired (acquiring / striking / holding) — stand still
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    // ---------------------------------------------------------------- states

    private void doAcquire() {
        // Auto-defend bypasses pickTarget(): it dereferences `filter` (null in auto-defend) and
        // HuntFilter.Nearest's predicate is Monster-only, missing Slime/Phantom that isHostile
        // catches. Use nearestHostile directly over the pursuit leash.
        LivingEntity found = (mode == Mode.AUTODEFEND)
                ? nearestHostile(Baritone.settings().combatDefendPursueRange.value)
                : pickTarget();
        if (found == null) {
            if (mode == Mode.AUTODEFEND) {
                // No threat — the threatRemains() check in onTick should have caught this; yield.
                return;
            }
            enterSearch();
            return;
        }
        target = found;
        tactic = MobTactic.forEntity(found);
        lastGoalPos = null;
        searchAnchor = null;
        equipBestWeapon();
        applyReachBuff();
        setState(State.APPROACH);
    }

    /** Wander + re-scan. Transitions to APPROACH on target found, gives up on timeout. */
    private void doSearch() {
        // Auto-defend never searches — the onTick threatRemains() check yields when no hostile is
        // left. If we ever land here, do nothing this tick and let the yield fire next tick.
        if (mode == Mode.AUTODEFEND) {
            return;
        }
        LivingEntity found = pickTarget();
        if (found != null) {
            target = found;
            tactic = MobTactic.forEntity(found);
            lastGoalPos = null;
            equipBestWeapon();
            applyReachBuff();
            logDirect("Spotted " + filter.describe() + " — moving in.");
            setState(State.APPROACH);
            return;
        }

        int searchTimeout = Baritone.settings().combatSearchTimeoutSeconds.value;
        if (searchStartTick >= 0 && (tickCount - searchStartTick) > searchTimeout * 20L) {
            logDirect("Couldn't find any " + filter.describe() + " nearby — giving up the hunt.");
            stop();
            return;
        }

        // wander: pick a roam point near the anchor, re-path every few seconds
        Player player = ctx.player();
        if (searchAnchor == null) {
            searchAnchor = player.blockPosition().immutable();
        }
        if (--searchRepathTicks <= 0 || lastGoalPos == null) {
            searchRepathTicks = 60; // re-roam every ~3s
            double ang = jitter(0.0, Math.PI * 2);
            double dist = jitter(8.0, 20.0);
            BlockPos roam = searchAnchor.offset(
                    (int) (Math.cos(ang) * dist), 0, (int) (Math.sin(ang) * dist));
            lastGoalPos = roam;
            moveGoal = new GoalNear(roam, 2);
        } else {
            moveGoal = new GoalNear(lastGoalPos, 2);
        }
    }

    private void enterSearch() {
        searchStartTick = tickCount;
        searchAnchor = null;
        searchRepathTicks = 0;
        lastGoalPos = null;
        setState(State.SEARCH);
    }

    private void doApproach() {
        if (!validTarget()) {
            onTargetLost();
            return;
        }
        Player player = ctx.player();
        double dist = player.distanceTo(target);

        aim(target);

        // ranged option: if we can bow and the target is out of melee reach, shoot instead of close.
        // Bows outrange melee mobs, so against a skeleton this lets us trade favorably from distance.
        // Only bow when we have line of sight — otherwise the bot drew and fired into walls/terrain
        // when the mob ducked behind a block, wasting arrows on a shot that could never connect.
        if (shouldUseBow(dist) && player.hasLineOfSight(target)) {
            equipBow();
            setState(State.STRIKE);
            return;
        }
        // otherwise fight melee — if we were holding a bow, swap back to a melee weapon
        if (weaponType == WeaponType.BOW) {
            stopBowDraw();
            equipBestWeapon();
        }

        if (dist <= tactic.engageRange()) {
            setState(State.STRIKE);
            strikeJitter = jitter(0, 2);
            return;
        }

        // vs a clump of ranged attackers, closing in a straight GoalNear line walks into a wall of
        // arrows. The shield reflex no longer turtles on every draw (it can't block 2+ shooters —
        // see shieldDecision), so while closing we strafe-and-advance: strafe() faces the target and
        // picks a tangential dodge direction (flipping when a shooter re-acquires us), and we also
        // drive MOVE_FORWARD so we still close the gap. Net movement is diagonal toward the mob,
        // dodging leading-aimed arrows instead of eating them. An arrow that still gets through is
        // caught by the projectile-impact shield layer (raised only when it's homing). Pure pathing
        // resumes once we reach engage range or the shooters thin out below the clump threshold.
        if (tactic.ranged()
                && Baritone.settings().combatDodgeRanged.value
                && countRangedAttackers(REACTION_RANGE) >= RANGED_CLUMP_THRESHOLD) {
            strafe();
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
            return;
        }

        // only re-issue the goal when the mob moved significantly — otherwise we reset Baritone's
        // path every tick and it never takes a step
        BlockPos mobPos = target.blockPosition();
        if (lastGoalPos == null || mobPos.distSqr(lastGoalPos) > 4) {
            lastGoalPos = mobPos;
        }
        moveGoal = new GoalNear(mobPos, (int) Math.max(1, tactic.engageRange() - 1));
    }

    private void doStrike() {
        if (!validTarget()) {
            onTargetLost();
            return;
        }
        Player player = ctx.player();
        double dist = player.distanceTo(target);

        // Route by weapon type. The equipped weapon's type is set by equipBestWeapon/equipBow; if we
        // are holding a bow at point-blank, drop to melee first.
        if (weaponType == WeaponType.BOW) {
            boolean inRange = dist > Baritone.settings().combatBowMinRange.value
                    || Baritone.settings().combatForceBow.value;
            if (inRange) {
                doBowStrike(player, dist);
                return;
            }
            stopBowDraw();
            equipBestWeapon(); // swap to melee
            // fall through to a melee strike this tick
        } else {
            // Re-equip our weapon every time we enter a strike. While APPROACHing, Baritone's
            // autotool (InventoryBehavior) selects the best pickaxe/shovel to dig through an
            // obstructing block in the path — desired, so the bot actually clears the path. But
            // nothing put the sword back, so the first swing after reaching the mob bonked it with
            // a shovel ("doesn't switch back to sword"). equipBestWeapon re-selects the sword/mace;
            // it's a no-op setSelectedSlot when we already hold it, so calling it each STRIKE tick
            // is cheap and safe. STRIKE is transient (one attack → RECOVER), so this runs ~once per
            // swing, not every tick of the fight.
            equipBestWeapon();
        }

        if (weaponType == WeaponType.VELOCITY_SCALED
                && Baritone.settings().combatVelocityStrikes.value) {
            doVelocityStrike(player, dist);
            return;
        }
        doMeleeStrike(player, dist);
    }

    /** The default: swing on cooldown at melee reach. */
    private void doMeleeStrike(Player player, double dist) {
        if (dist > tactic.engageRange()) {
            setState(State.APPROACH);
            return;
        }
        aim(target);
        if (strikeJitter > 0) {
            strikeJitter--;
            return; // human-looking timing jitter
        }
        if (cooldown > 0) return;
        // respect attack cooldown when humanize is on (lower DPS, looks fair)
        if (Baritone.settings().combatHumanizeAim.value && player.getAttackStrengthScale(0.5f) < 0.9f) {
            return;
        }
        // the cheat core: direct attack call (swings main hand internally) — a moving attacker
        ctx.playerController().attack(player, target);
        cooldown = (int) Math.max(4, player.getCurrentItemAttackStrengthDelay() / 2);
        meleeShieldStreak = 0; // we exploited a cooldown window — reset the hold-too-long failsafe
        setState(State.RECOVER);
    }

    /**
     * Draw and release a bow: hold right-click (via processRightClick on the bow's hand) to charge,
     * then stopUsingItem to fire. Arrows must be in the inventory; if not, falls back to melee.
     * Runtime-untested against a live server — the draw/release packet timing is the risk.
     */
    private void doBowStrike(Player player, double dist) {
        aim(target, true); // lead the target so arrows hit moving mobs
        if (!hasArrows(player)) {
            // out of ammo — swap to melee and close
            stopBowDraw();
            equipBestWeapon();
            setState(State.APPROACH);
            return;
        }
        // Don't fire into terrain: if the target broke line of sight (moved behind a block) while we
        // were drawing or about to draw, stop the draw and close in. Firing would bury the arrow in a
        // wall and waste the shot. Re-checked every draw tick so a mob that ducks mid-charge aborts.
        if (!player.hasLineOfSight(target)) {
            stopBowDraw();
            equipBestWeapon();
            setState(State.APPROACH);
            return;
        }
        if (bowDrawTicks == 0) {
            // begin drawing this tick (bow is already selected in the main hand). processRightClick
            // starts the use but doesn't sustain it — hold the real use key down so vanilla keeps
            // drawing across ticks, otherwise the bow releases at tick 0 and never charges.
            // Drop any active shield first: a shield and a bow both use the use key, and starting a
            // draw while blocking would leave the block half-up and the draw cancelled.
            lowerShield();
            shieldHoldTicks = 0;
            ctx.playerController().processRightClick(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND);
            holdUseKey(true);
            bowDrawTicks = 1;
            return;
        }
        bowDrawTicks++;
        // re-assert the held use key each tick (input polling can clear it) and restart if the draw
        // was interrupted (took damage, swapped slot, ...).
        holdUseKey(true);
        if (!player.isUsingItem()) {
            holdUseKey(false);
            bowDrawTicks = 0;
            return;
        }
        if (bowDrawTicks >= Baritone.settings().combatBowDrawTicks.value) {
            // release to fire — releaseUse sends RELEASE_USE_ITEM (fires the arrow with the accumulated
            // charge) and clears the force-hold. This is the shot completing, not a cancel.
            releaseUse();
            bowDrawTicks = 0;
            cooldown = 10; // brief recovery before the next shot
            setState(State.RECOVER);
        }
    }

    /**
     * Mace / spear: damage scales with the player's velocity, so the strike is timed to coincide
     * with movement — the mace smash-attacks on a fall (jump to gain fallDistance), and a spear
     * benefits from sprint momentum. While not yet moving fast enough, build velocity; once fast,
     * strike for the bonus. Falls back to a plain melee swing if velocity strikes are disabled.
     */
    private void doVelocityStrike(Player player, double dist) {
        if (dist > tactic.engageRange()) {
            setState(State.APPROACH);
            return;
        }
        aim(target);
        if (cooldown > 0) {
            return;
        }
        if (Baritone.settings().combatHumanizeAim.value && player.getAttackStrengthScale(0.5f) < 0.9f) {
            return;
        }
        boolean mace = player.getMainHandItem().is(Items.MACE);
        double horizSpeed = player.getDeltaMovement().multiply(1, 0, 1).length();
        boolean fastEnough = mace ? player.fallDistance > 0.0 : horizSpeed > 0.25;
        if (!fastEnough) {
            // build velocity: mace -> jump to gain a fall; spear -> sprint forward into the mob
            if (mace) {
                if (player.onGround()) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
                }
            } else {
                baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, true);
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
            }
            return; // strike next tick once we're moving fast
        }
        // moving fast — strike now and release the velocity-building inputs
        baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
        ctx.playerController().attack(player, target);
        cooldown = (int) Math.max(4, player.getCurrentItemAttackStrengthDelay() / 2);
        meleeShieldStreak = 0; // we exploited a cooldown window — reset the hold-too-long failsafe
        setState(State.RECOVER);
    }

    private void doRecover() {
        if (!validTarget()) {
            onTargetLost();
            return;
        }
        Player player = ctx.player();
        double dist = player.distanceTo(target);

        // creeper: back off well outside the blast radius while it's swelling, and only
        // re-engage once it has cooled (getSwellDir <= 0). dangerRange (7) clears the 3-block blast.
        if (tactic.retreatOnWindup() && target instanceof Creeper c && c.getSwellDir() > 0) {
            backOff(tactic.dangerRange());
            return;
        }

        // ranged mobs: strafe to make arrows whiff (aim-based dodge), then close in to hit on cooldown.
        if (tactic.ranged()) {
            if (Baritone.settings().combatDodgeRanged.value) {
                strafe();
            } else {
                aim(target);
            }
            if (cooldown <= 0) {
                setState(State.STRIKE);
                strikeJitter = jitter(0, 2);
            }
            return;
        }

        // swarm kite: >=2 hostiles in melee -> back off the group centroid to create space
        if (countNearbyHostiles(MELEE_THREAT) >= SWARM_KITE_THRESHOLD) {
            backOffFromCentroid(tactic.kiteRange());
            return;
        }

        // melee hit-and-run: while our swing recovers, hold at the edge of reach (~3.5) so the
        // mob whiffs; only retreat if it's inside that range, otherwise hold and let STRIKE close
        // in. kiteRange < engageRange, so we stay able to hit — no more backing out of reach.
        if (cooldown > 0 && dist < tactic.kiteRange()) {
            backOff(tactic.kiteRange());
            return;
        }

        if (cooldown <= 0) {
            setState(State.STRIKE);
            strikeJitter = jitter(0, 2);
        }
    }

    private void doRetreat() {
        Player player = ctx.player();
        Settings s = Baritone.settings();
        // When to re-engage depends on WHY we retreated:
        //  - HP retreat (hurt): re-engage once health recovers (kiting/shield handle the rest).
        //  - Pinned failsafe (held the shield too long): re-engage once we've created space — i.e.
        //    no melee mob remains inside its attack reach. If the clump pursues tightly we keep
        //    fleeing (better than re-pinning immediately); once we break reach, we re-engage and
        //    the selective-block cycle resumes. HP must also be non-critical.
        boolean hpOk = player.getHealth() >= s.combatRetreatHealth.value + 6;
        boolean hasSpace = countNearbyHostiles(MELEE_BLOCK_RANGE) == 0;
        if (retreatPinned) {
            if (hasSpace && player.getHealth() >= s.combatRetreatHealth.value) {
                retreatPinned = false;
                logDirect("Reset — re-engaging.");
                setState(State.ACQUIRE);
                return;
            }
        } else if (hpOk) {
            // Re-engage once healthy — even when still outnumbered. The count-based retreat in
            // onTick only triggers when hurt, so a recovered bot re-joins the fight and lets
            // kiting/shield handle the group, instead of fleeing a large pack forever.
            logDirect("Healthy again — re-engaging.");
            setState(State.ACQUIRE);
            return;
        }

        LivingEntity flee = (target != null && target.isAlive()) ? target : nearestHostile(REACTION_RANGE);
        if (flee == null) {
            return; // nothing to flee from right now; hold (we're still hurt, else we'd have re-engaged)
        }

        // Disengage-first retreat. The old behavior turned and ran (GoalRunAway) the instant the
        // HP/count trip fired — an instant about-face that reads as a bot and drops the player's
        // guard. A human backs off while still watching the threat and only turns to flee when
        // staying facing-forward is too dangerous. So: prefer to BACKPEDAL (face the threat with an
        // eased camera, walk backward, let the shield reflex above block imminent hits) and only
        // escalate to a full turn-and-run when one of these holds:
        //   - critical: HP is below the retreat floor (we can't afford to keep eating hits)
        //   - tooClose: the mob is inside ~2 blocks (backpedal is slower than the mob's walk → no gap)
        //   - cornered: a solid block is at our back (backpedal feeds free hits into a wall)
        double dist = flee.distanceTo(player);
        boolean critical = player.getHealth() < s.combatRetreatHealth.value;
        boolean tooClose = dist < 2.0;
        boolean cornered = retreatBlocked(player, flee);
        if (critical || tooClose || cornered) {
            // ESCALATE: turn and run. Pathing (GoalRunAway) drives the about-face; when humanizeCamera
            // is on and pathing resolves client-visible, that turn eases instead of snapping. The
            // shield reflex above still runs during RETREAT (it's before the state switch) and faces
            // the nearest hostile while target is null — so during the flee we only block what's
            // actually about to land, not turtle forever. Re-engage checks above fire each tick.
            moveGoal = new GoalRunAway(12, flee.blockPosition());
            return;
        }
        // DISENGAGE: backpedal. Face the threat (aim goes through the humanizeCamera easing layer —
        // combat opts into ease — so the threat-tracking turn eases instead of one-tick snapping).
        // MOVE_BACK walks us backward; no pathing goal so REQUEST_PAUSE holds pathing still while the
        // forced key moves us. On ticks where the shield reflex raises a block (it returns before
        // this method runs), the backpedal briefly pauses to plant-and-block — human-like, and the
        // block window is short by design (melee rhythm / projectile-impact gated).
        aim(flee);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, true);
        moveGoal = null;
    }

    /**
     * Is there a solid block at the player's back (the side away from the threat), so backpedaling
     * can't open space? When true the retreat escalates from a backpedal to a turn-and-run flee —
     * backing into a wall just pins the player for free hits. Samples ~1.5 blocks behind the player
     * at both feet and head height (either blocked = cornered).
     */
    private boolean retreatBlocked(Player player, LivingEntity flee) {
        Vec3 away = new Vec3(player.getX() - flee.getX(), 0, player.getZ() - flee.getZ());
        double len = away.length();
        if (len < 1e-3) {
            return false; // threat is on top of us — not a "back to a wall" situation
        }
        away = away.scale(1.5 / len);
        Vec3 base = player.position().add(away);
        BlockPos feetBehind = BlockPos.containing(base);
        BlockPos headBehind = BlockPos.containing(base.add(0, 1, 0));
        return isSolidBlock(feetBehind) || isSolidBlock(headBehind);
    }

    /** Collision present at this position (false for air/water/leaves/etc). */
    private boolean isSolidBlock(BlockPos pos) {
        return !ctx.world().getBlockState(pos).getCollisionShape(ctx.world(), pos).isEmpty();
    }

    /** Target died / despawned / walked out of range — re-acquire or search. */
    private void onTargetLost() {
        if (target != null && !target.isAlive()) {
            logDirect("Target down.");
        }
        // if we were mid-bow-draw, release the use key now — otherwise it stays held into the next
        // state and triggers an unwanted item-use (eating food, raising a shield) on the wrong slot
        stopBowDraw();
        target = null;
        lastGoalPos = null;
        setState(State.ACQUIRE);
    }

    // ------------------------------------------------------------- behaviors

    private void aim(LivingEntity e) {
        aim(e, false);
    }

    /**
     * Aim at an entity's eyes. When {@code lead} is true (bow shots), predict where the target will
     * be when the arrow arrives and aim there instead of its current position — a moving mob walked
     * out of the arrow's path, so a snapshot aim whiffed. The lead models the arrow's flight: travel
     * time from distance / arrow speed (~2.8 b/tick average over a full-draw shot, which starts at
     * ~3.0 and decays with 0.99 drag), the target's per-tick velocity over that time, and the arrow's
     * gravity drop (aim above the target by ~½·g·t² so the arc intersects it). This is the bow-side
     * counterpart of {@link ProjectileThreat}'s step simulation — same physics, inverted: there we
     * predict an incoming arrow's landing, here we solve for the launch angle that lands ours.
     */
    private void aim(LivingEntity e, boolean lead) {
        Player player = ctx.player();
        if (player == null) return;
        // base aim point: the entity's eyes, biased down slightly toward center mass
        Vec3 point = e.getEyePosition().add(0, -0.2, 0);
        if (lead) {
            double dist = ctx.playerHead().distanceTo(point);
            double t = Math.max(1.0, dist / 2.8); // approximate ticks of flight
            // lead the target by its velocity over the flight time (clamped so a fast-moving entity
            // or a stale velocity reading can't over-lead into another chunk)
            Vec3 vel = e.getDeltaMovement();
            double leadMag = vel.length() * t;
            if (leadMag > dist * 0.6) {
                vel = vel.scale(dist * 0.6 / leadMag);
            }
            point = point.add(vel.scale(t));
            // compensate for arrow gravity: aim above the predicted point by the drop over the flight
            point = point.add(0, 0.5 * 0.05 * t * t, 0);
        }
        aimAt(point);
    }

    /** Aim at an explicit world point (used by the shield reflex to face the incoming threat). */
    private void aimAtPoint(Vec3 point) {
        aimAt(point);
    }

    private void aimAt(Vec3 point) {
        // small random offset so the aim isn't pixel-locked (reads human, dodges anti-cheat heuristics)
        double ox = jitter(-0.1, 0.1);
        double oy = jitter(-0.1, 0.1);
        Vec3 p = point.add(ox, oy, 0);
        Rotation rot = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), p, ctx.playerRotations());
        // blockInteract=true forces the rotation to actually apply (CLIENT-visible); re-issue every
        // tick (LookBehavior clears its target each POST). ease=true runs the turn through the
        // humanizeCamera easing layer so the bot eases onto the target instead of one-tick snapping
        // — the "too quick turn like a real bot" fix. Melee attacks pass the entity by reference, so
        // a briefly-lagging eased aim still connects; bow/ranged wait for the eased aim to converge
        // before releasing (the strike gate reads the actual player rotation).
        baritone.getLookBehavior().updateTarget(rot, true, true);
    }

    /** Step away from the target to a point `distance` blocks beyond it. */
    private void backOff(double distance) {
        if (target == null) return;
        Player player = ctx.player();
        Vec3 away = player.position().subtract(target.position()).normalize().scale(distance);
        BlockPos retreatPos = BlockPos.containing(target.position().add(away));
        moveGoal = new GoalNear(retreatPos, 1);
    }

    /**
     * Dodge a ranged attacker using its look direction (the F3+B entity-look line). When the mob's
     * view vector lines up on the player — i.e. it's aiming a shot — strafe perpendicular so the
     * arrow whiffs. This is key-based (MOVE_LEFT/RIGHT) rather than path-goal-based, so it is
     * responsive enough to actually dodge; the player keeps facing the mob (combat always aims at
     * it), so left/right strafes tangentially around it. Direction flips periodically and whenever
     * the mob re-acquires us, so we don't strafe straight into a second shot.
     */
    private void strafe() {
        Player player = ctx.player();
        if (player == null || target == null) return;

        // does the mob's look vector line up on us? (aim cone test, flattened to the horizontal plane)
        boolean aimedAtUs = isAimingAtUs(target);
        // Flip only on the transition into being aimed at — flipping every tick would cancel out and
        // produce no real dodge. Otherwise flip periodically so we don't strafe predictably.
        if (aimedAtUs && !prevAimed) {
            strafeDir = -strafeDir;
            strafeTicks = jitter(6, 10);
        } else if (!aimedAtUs && strafeTicks <= 0) {
            strafeDir = -strafeDir;
            strafeTicks = jitter(10, 18);
        }
        prevAimed = aimedAtUs;
        strafeTicks--;

        aim(target); // keep facing the mob while strafing tangentially
        // MOVE_LEFT/RIGHT are relative to our facing, which is the mob — so this strafes around it
        baritone.getInputOverrideHandler().setInputForceState(
                strafeDir >= 0 ? Input.MOVE_RIGHT : Input.MOVE_LEFT, true);
        baritone.getInputOverrideHandler().setInputForceState(
                strafeDir >= 0 ? Input.MOVE_LEFT : Input.MOVE_RIGHT, false);
        // don't let pathing fight the strafe — hold still and let the key move us
        moveGoal = null;
    }

    /**
     * Is a ranged mob currently aiming at the player? Compares the mob's look direction to the
     * bearing from mob to player, within {@code combatDodgeAngle} degrees (horizontal plane). This
     * is the "F3+B entity look line pointing at us" signal — a skeleton drawing its bow on us.
     */
    private boolean isAimingAtUs(LivingEntity mob) {
        Player player = ctx.player();
        if (player == null) return false;
        Vec3 look;
        try {
            look = mob.getViewVector(1.0f);
        } catch (Throwable ignored) {
            return false;
        }
        Vec3 toPlayer = player.position().add(0, 1.0, 0).subtract(mob.getEyePosition());
        Vec3 flatLook = new Vec3(look.x, 0, look.z);
        Vec3 flatTo = new Vec3(toPlayer.x, 0, toPlayer.z);
        double ll = flatLook.length();
        double tl = flatTo.length();
        if (ll < 1.0e-4 || tl < 1.0e-4) return false;
        double dot = flatLook.dot(flatTo) / (ll * tl); // cos of angle between, -1..1
        double cosThreshold = Math.cos(Math.toRadians(Baritone.settings().combatDodgeAngle.value));
        return dot >= cosThreshold;
    }

    // --------------------------------------------------------------- helpers

    private LivingEntity pickTarget() {
        Player player = ctx.player();
        return ctx.entitiesStream()
                .filter(e -> e instanceof LivingEntity && e.isAlive() && e != player)
                .filter(e -> filter.predicate().test(e))
                .filter(e -> e.distanceToSqr(player) <= SCAN_RANGE * SCAN_RANGE)
                .map(e -> (LivingEntity) e)
                // priority: hostile first, then lowest HP, then nearest
                .min(Comparator
                        .comparingInt((LivingEntity e) -> isHostile(e) ? 0 : 1)
                        .thenComparingDouble(LivingEntity::getHealth)
                        .thenComparingDouble(e -> e.distanceToSqr(player)))
                .orElse(null);
    }

    /**
     * Client-side hostile check. NOT getTarget()-based: {@code Mob.target} is set server-side by
     * mob AI and is never synced to the client, so {@code mob.getTarget()} is null on the client.
     * Detecting threats by hostile type + proximity is the reliable client-side signal.
     *
     * <p>{@code Monster} covers Zombie/Husk/Drowned, Skeleton variants, Creeper, Spider, EnderMan,
     * Witch, Blaze, etc. {@code Slime} and {@code Phantom} are hostile but extend {@code Mob}
     * directly, so they're listed explicitly.
     */
    private static boolean isHostile(Entity e) {
        return e instanceof Monster
                || e instanceof Slime
                || e instanceof Phantom;
    }

    /** The nearest hostile within {@code range} of the player, or null. */
    private LivingEntity nearestHostile(double range) {
        Player player = ctx.player();
        if (player == null) return null;
        double rs = range * range;
        return ctx.entitiesStream()
                .filter(e -> e instanceof LivingEntity && e.isAlive() && e != player)
                .filter(CombatProcess::isHostile)
                .filter(e -> e.distanceToSqr(player) <= rs)
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(player)))
                .map(e -> (LivingEntity) e)
                .orElse(null);
    }

    /** Count hostiles within {@code range} of the player. */
    private int countNearbyHostiles(double range) {
        Player player = ctx.player();
        if (player == null) return 0;
        double rs = range * range;
        return (int) ctx.entitiesStream()
                .filter(e -> e instanceof LivingEntity && e.isAlive() && e != player)
                .filter(CombatProcess::isHostile)
                .filter(e -> e.distanceToSqr(player) <= rs)
                .count();
    }

    /**
     * Count ranged hostiles (skeletons, pillagers, witches, blazes) within {@code range}. A clump of
     * these is the case where the shield reflex can't keep up: you can't block 2+ shooters at once
     * (a shield covers one hemisphere), and staggered fire means one is always in its pre-release
     * draw, so pre-blocking every draw pins the bot forever. When this returns ≥
     * {@link #RANGED_CLUMP_THRESHOLD}, {@code shieldDecision} skips the ranged-draw pre-block and the
     * bot strafes to dodge instead (blocking only an arrow that's actually homing — the projectile
     * impact layer). Classifies via {@link MobTactic#forEntity} so pillagers (crossbow) are counted.
     */
    private int countRangedAttackers(double range) {
        Player player = ctx.player();
        if (player == null) return 0;
        double rs = range * range;
        int n = 0;
        for (Entity e : ctx.entitiesStream().toList()) {
            if (!(e instanceof LivingEntity) || !e.isAlive() || e == player) continue;
            if (!isHostile(e)) continue;
            if (e.distanceToSqr(player) > rs) continue;
            if (MobTactic.forEntity(e).ranged()) n++;
        }
        return n;
    }

    /** Back away from the centroid of all nearby hostiles (multi-mob spacing). */
    private void backOffFromCentroid(double distance) {
        Player player = ctx.player();
        if (player == null) return;
        double sumX = 0, sumY = 0, sumZ = 0, n = 0;
        double rs = REACTION_RANGE * REACTION_RANGE;
        for (Entity e : ctx.entitiesStream().toList()) {
            if (!(e instanceof LivingEntity) || !e.isAlive() || e == player) continue;
            if (!isHostile(e)) continue;
            if (e.distanceToSqr(player) > rs) continue;
            sumX += e.getX();
            sumY += e.getY();
            sumZ += e.getZ();
            n++;
        }
        if (n == 0) {
            backOff(distance);
            return;
        }
        Vec3 centroid = new Vec3(sumX / n, sumY / n, sumZ / n);
        Vec3 away = player.position().subtract(centroid);
        if (away.lengthSqr() < 1.0e-4) return; // standing on the centroid; no direction to pick
        BlockPos retreatPos = BlockPos.containing(player.position().add(away.normalize().scale(distance)));
        moveGoal = new GoalNear(retreatPos, 1);
    }

    /** Alive, not removed, still in the world, and within pursue range. */
    private boolean validTarget() {
        if (target == null || !target.isAlive() || target.isRemoved()) return false;
        Player player = ctx.player();
        if (player == null) return false;
        return target.distanceToSqr(player) <= PURSUE_RANGE * PURSUE_RANGE;
    }

    /**
     * Select the best melee weapon in the hotbar and record its {@link WeaponType}. Prefers a
     * velocity-scaled weapon (mace/spear) when {@code combatVelocityStrikes} is on, since those hit
     * far harder than their base damage suggests; otherwise prefers a sword — an axe may list higher
     * raw attack damage but swords swing faster and we never want a shovel/pickaxe in a fight. Tools
     * (pickaxe/shovel/hoe/shears) are skipped entirely so a high-modifier shovel never wins; an axe
     * is the only fallback when no sword or velocity weapon is present. Never selects a bow — bows
     * are equipped explicitly via {@link #equipBow()}.
     *
     * <p>26.1 has no {@code SwordItem}/{@code PickaxeItem} classes (swords and pickaxes are
     * data-driven), so "is this a sword / an unwanted tool" is decided by registry id path.
     */
    private void equipBestWeapon() {
        Player player = ctx.player();
        if (player == null) return;
        boolean wantVelocity = Baritone.settings().combatVelocityStrikes.value;
        int best = -1;
        double bestDmg = -1;
        WeaponType bestType = WeaponType.MELEE;
        boolean bestIsSword = false;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            WeaponType type = WeaponType.forStack(stack);
            if (type == WeaponType.BOW) continue; // bow is selected separately
            if (isUnwantedTool(stack)) continue;   // never fight with a pickaxe/shovel/hoe/shears
            boolean isSword = isSword(stack);
            double d = attackDamage(stack);
            // a velocity weapon wins outright when we want one (and beats a tie), regardless of base dmg
            if (type == WeaponType.VELOCITY_SCALED && wantVelocity) {
                if (best < 0 || bestType != WeaponType.VELOCITY_SCALED || d > bestDmg) {
                    best = i; bestDmg = d; bestType = type; bestIsSword = isSword;
                }
                continue;
            }
            if (bestType == WeaponType.VELOCITY_SCALED && wantVelocity) continue; // keep the velocity weapon
            // a sword always beats a non-sword, even an axe with nominally higher attack damage
            if (isSword && !bestIsSword) {
                best = i; bestDmg = d; bestType = type; bestIsSword = true;
                continue;
            }
            if (!isSword && bestIsSword) continue;
            if (d > bestDmg) {
                bestDmg = d; best = i; bestType = type; bestIsSword = isSword;
            }
        }
        if (best >= 0) {
            player.getInventory().selected = best;
            weaponType = bestType;
        }
    }

    /** Is this stack a sword? Check the item id — works on both 26.1 (data-driven) and 1.21.1. */
    private static boolean isSword(ItemStack stack) {
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key != null && key.getPath().endsWith("_sword");
    }

    /** Tools we never want to swing in combat (mining/harvesting tools). */
    private static boolean isUnwantedTool(ItemStack stack) {
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (key == null) return false;
        String p = key.getPath();
        // check _pickaxe before _axe (a pickaxe path ends in "axe" too)
        return p.endsWith("_pickaxe") || p.endsWith("_shovel") || p.endsWith("_hoe")
                || p.endsWith("_shears") || p.endsWith("_brush");
    }

    /** Total attack-damage attribute on a stack (0 if none). */
    private static double attackDamage(ItemStack stack) {
        var attrs = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
        double d = 0;
        if (attrs != null) {
            for (var mod : attrs.modifiers()) {
                if (mod.attribute().is(Attributes.ATTACK_DAMAGE.unwrapKey().get())) {
                    d += mod.modifier().amount();
                }
            }
        }
        return d;
    }

    /**
     * Remember the hotbar slot the player was holding when combat began, so we can put it back on
     * yield/cleanup. Without this, after a hunt (or an auto-defend burst that froze a MineProcess)
     * the bot would resume mining with a sword still equipped instead of its pickaxe. Only records
     * the first slot across a combat engagement — re-arming mid-fight must not overwrite it.
     */
    private void recordPriorWeaponSlot() {
        Player player = ctx.player();
        if (player == null) return;
        if (priorWeaponSlot < 0) {
            priorWeaponSlot = player.getInventory().selected;
        }
    }

    /** Restore the hotbar slot recorded by {@link #recordPriorWeaponSlot}, then clear it. */
    private void restorePriorWeaponSlot() {
        if (priorWeaponSlot >= 0 && ctx.player() != null) {
            ctx.player().getInventory().selected = priorWeaponSlot;
        }
        priorWeaponSlot = -1;
    }

    // ------------------------------------------------------- ranged weapon mgmt

    /** Should we shoot instead of close, given the current distance to the target? */
    private boolean shouldUseBow(double dist) {
        if (!Baritone.settings().combatUseBow.value) return false;
        Player player = ctx.player();
        if (player == null) return false;
        // need both a bow in the hotbar and at least one arrow
        if (findBowSlot(player) < 0 || !hasArrows(player)) {
            return false;
        }
        // force-bow: shoot regardless of distance (testing). Otherwise stay out of the mob's face.
        if (Baritone.settings().combatForceBow.value) {
            return true;
        }
        return dist > Baritone.settings().combatBowMinRange.value;
    }

    /** Select the bow slot and mark the weapon type. No-op if no bow in the hotbar. */
    private void equipBow() {
        Player player = ctx.player();
        if (player == null) return;
        int slot = findBowSlot(player);
        if (slot < 0) return;
        player.getInventory().selected = slot;
        weaponType = WeaponType.BOW;
    }

    private static int findBowSlot(Player player) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() instanceof net.minecraft.world.item.BowItem) {
                return i;
            }
        }
        return -1;
    }

    /** Is there at least one arrow anywhere in the inventory (hotbar or main grid)? */
    private static boolean hasArrows(Player player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() instanceof ArrowItem) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------- shield

    /**
     * How many ticks to hold the shield right now (0 = don't block). Combines three signals so the
     * block is up *before* the hit registers, not after:
     * <ul>
     *   <li><b>Ranged draw</b>: a hostile is drawing a bow/crossbow aimed at us — pre-raise near the
     *       end of its draw so the block is up before it releases. This is the primary anti-skeleton
     *       layer; by the time the arrow exists, we're already blocking.</li>
     *   <li><b>Projectile</b>: an arrow/fireball's simulated path enters our hitbox within
     *       {@code combatShieldLeadTicks} — raise now and hold past impact. The step simulation
     *       accounts for arc + real hitbox, so this catches arrows the old straight-line model missed.</li>
     *   <li><b>Melee rhythm</b>: against a melee mob inside its attack reach (we can't kite), raise
     *       the shield just before its predicted next swing and — crucially — hold 0 while it's on
     *       its attack cooldown, so the shield drops and we strike during the window.</li>
     * </ul>
     * Reactive melee blocking is impossible client-side (a mob's swing and its damage land on the
     * same server tick), so melee defense is <b>predictive</b>: we track each mob's swing rhythm and
     * block the imminent hit, then exploit the cooldown. Holding the shield against a clump is a
     * trap (suppresses our offense, drains the shield) — so the hold is selective and bounded by the
     * hold-too-long failsafe (retreat + re-engage). Kite-able melee fights (outside the mob's reach)
     * are not shielded at all; we kite and strike. Ranged is where continuous shielding pays off.
     */
    private int shieldDecision(Player player) {
        int hold = 0;
        shieldAimPoint = null;
        meleeHoldThisTick = false; // set true below only if the melee rhythm is the winning signal
        // ---- ranged draw: pre-raise before a skeleton/pillager releases ----
        // ONLY when we face a single ranged attacker (or none). Against a clump (≥ RANGED_CLUMP_THRESHOLD
        // shooters) pre-blocking is a trap: a shield covers one hemisphere so we can't block 2+ mobs at
        // once, and staggered fire means one is always in its pre-release draw — pre-blocking every draw
        // re-arms shieldHoldTicks every tick and the reflex pins the bot forever (the "stands still,
        // shield up, never engages" bug vs 5+ skeletons). Instead, vs a clump we skip the draw pre-block
        // entirely and rely on strafe-dodge (the state machine now runs) + the projectile-impact layer
        // below, which raises the shield only for an arrow actually predicted to hit — "block when the
        // arrow is homing," not when one of many mobs is merely drawing.
        if (countRangedAttackers(REACTION_RANGE) < RANGED_CLUMP_THRESHOLD) {
            // rangedDrawHold sets shieldAimPoint to the drawing mob it pre-blocks for.
            hold = Math.max(hold, rangedDrawHold(player));
        }
        // ---- projectile: block just before impact, hold a few ticks past it ----
        AABB box = player.getBoundingBox().inflate(Baritone.settings().combatShieldRadius.value);
        ProjectileThreat pt = ProjectileThreat.imminent(
                ctx.entitiesStream().toList(), box, player.getUUID(),
                Baritone.settings().combatProjectileLookahead.value);
        if (pt != null && pt.ticksToImpact <= Baritone.settings().combatShieldLeadTicks.value) {
            // raise now (lead) and hold until just after the predicted impact tick
            hold = Math.max(hold, pt.ticksToImpact + PROJECTILE_POSTHIT_HOLD);
            // a live inbound projectile is the most imminent threat — face IT (not the drawing mob)
            // so the shield covers the arrow's approach. This is the omnidirectional rule from the
            // threat model: block toward the threat, wherever it comes from.
            shieldAimPoint = pt.projectile.position();
        }
        // ---- melee rhythm: selective block, not a held turtle ----
        // Replaces the old low-HP turtle (which held the shield up against any melee threat and so
        // pinned the bot against a clump, suppressing its own offense). Now: raise the shield just
        // before a melee mob's predicted next swing (block the imminent hit), and — crucially —
        // return 0 while that mob is on its attack cooldown, so the shield drops and the state
        // machine can strike during the window. Only considers mobs inside their attack reach (we
        // can't kite); kite-able fights are untouched. See meleeRhythmHold for the timing.
        int meleeHold = meleeRhythmHold(player);
        hold = Math.max(hold, meleeHold);
        // Attribute the hold to melee ONLY when melee is the winning (max) signal, so a projectile
        // block against a nearby zombie doesn't inflate the melee hold-streak and falsely trigger
        // the retreat failsafe. A tie counts as melee (both are threatening).
        meleeHoldThisTick = meleeHold > 0 && meleeHold >= hold;
        return hold;
    }

    /**
     * Update per-mob melee swing tracking. A rising edge of the synced {@code swinging} flag (false→true)
     * means the mob just spent its attack — {@code MeleeAttackGoal} calls {@code swing()} and
     * {@code doHurtTarget()} on the same server tick, and the swing is broadcast to the client. We
     * timestamp it and, once we've seen two swings, measure the mob's real attack interval (which
     * varies by attack-speed attribute and difficulty — the {@link #MELEE_BASELINE_INTERVAL} is only
     * a fallback until measured). Self-correcting: every detected swing re-anchors the prediction.
     */
    private void updateMeleeRhythm(Player player) {
        long now = tickCount;
        double rs = REACTION_RANGE * REACTION_RANGE;
        for (Entity e : ctx.entitiesStream().toList()) {
            if (!(e instanceof LivingEntity) || !e.isAlive() || e == player) continue;
            if (!isHostile(e)) continue;
            if (e.distanceToSqr(player) > rs) continue;       // only track mobs we can actually see engaging
            LivingEntity mob = (LivingEntity) e;
            UUID id = mob.getUUID();
            boolean nowSwinging = mob.swinging;               // public, EntityData-synced
            boolean prev = meleePrevSwinging.getOrDefault(id, false);
            meleePrevSwinging.put(id, nowSwinging);
            if (nowSwinging && !prev) {
                Long last = meleeLastSwing.get(id);
                if (last != null) {
                    long delta = now - last;
                    // sane cooldown window — discard absurd deltas (a mob that stopped engaging then
                    // resumed much later shouldn't poison the measured interval)
                    if (delta >= 5 && delta <= 200) {
                        meleeInterval.put(id, delta);
                    }
                }
                meleeLastSwing.put(id, now);
            }
            // expire mobs we haven't seen swing in a long time so the maps don't grow unbounded
            Long last = meleeLastSwing.get(id);
            if (last != null && now - last > 600) {
                meleeLastSwing.remove(id);
                meleeInterval.remove(id);
                meleePrevSwinging.remove(id);
            }
        }
    }

    /**
     * How many ticks to hold the shield against an imminent melee swing (0 = don't block — strike
     * window). A mob inside {@code MELEE_BLOCK_RANGE} (we can't kite outside its reach) whose cooldown
     * is about to expire gets its swing pre-blocked; a mob still on cooldown returns 0 so the shield
     * drops and we strike. The first swing from an unseen mob is not pre-blocked (it's unblockable
     * anyway — the mob swings and damages on the same tick) — we take it, record the swing, then the
     * rhythm engages for subsequent swings.
     *
     * <p>Sets {@link #meleeHoldThisTick} so the reflex can count melee holds toward the
     * hold-too-long failsafe (projectile/ranged holds don't count).
     */
    private int meleeRhythmHold(Player player) {
        if (!Baritone.settings().combatMeleeBlock.value) return 0;
        long now = tickCount;
        int bestHold = 0;
        LivingEntity bestMob = null;
        double rs = MELEE_BLOCK_RANGE * MELEE_BLOCK_RANGE;
        for (Entity e : ctx.entitiesStream().toList()) {
            if (!(e instanceof LivingEntity) || !e.isAlive() || e == player) continue;
            if (!isHostile(e)) continue;
            if (e.distanceToSqr(player) > rs) continue;       // outside the mob's reach — kite, don't block
            LivingEntity mob = (LivingEntity) e;
            Long last = meleeLastSwing.get(mob.getUUID());
            if (last == null) continue;                        // never seen it swing — can't predict yet
            long interval = meleeInterval.getOrDefault(mob.getUUID(), (long) MELEE_BASELINE_INTERVAL);
            long nextSwing = last + interval;
            long untilSwing = nextSwing - now;
            // stale: if it's been far longer than the interval since the last swing, the mob may have
            // swung undetected (lost LOS, despawned briefly) — don't trust the prediction, hold only
            // briefly so we don't pin the shield on a bad reading.
            int hold;
            if (now - last > interval * 2) {
                hold = MELEE_POSTHIT_HOLD;
            } else if (untilSwing <= MELEE_BLOCK_LEAD) {
                // swing imminent (cooldown expired or about to) — raise and hold past the predicted
                // swing tick so the block is up when it lands.
                hold = (int) Math.max(MELEE_POSTHIT_HOLD, untilSwing + MELEE_POSTHIT_HOLD + 1);
            } else {
                // mob on cooldown — this is the strike window: don't block (return 0 for this mob)
                continue;
            }
            if (hold > bestHold) {
                bestHold = hold;
                bestMob = mob;
            }
        }
        if (bestHold > 0 && bestMob != null && shieldAimPoint == null) {
            // face the mob about to swing (omnidirectional: the shield covers the front hemisphere).
            // Only set if no projectile/ranged threat already claimed the aim point — those outrank
            // melee for facing (a flying arrow is the more immediate hit).
            shieldAimPoint = bestMob.getEyePosition();
        }
        return bestHold;
    }

    /**
     * Detect ranged mobs drawing a bow/crossbow aimed at the player, and pre-raise the shield near
     * the end of their draw. {@code LivingEntity.isUsingItem()} is EntityData-synced (client-visible),
     * and {@code getUseItem()} exposes the item being drawn — so we can see a skeleton nocking its bow.
     * We track each mob's draw start tick and only block once it has drawn long enough that release is
     * imminent (~12 of the ~20-tick draw), so we don't hold the shield for the entire draw (which
     * would suppress our own attacks). When the mob releases, the projectile layer takes over for the
     * arrow's flight — a clean handoff from draw-detection to impact-detection.
     */
    private int rangedDrawHold(Player player) {
        int hold = 0;
        for (Entity e : ctx.entitiesStream().toList()) {
            if (!(e instanceof LivingEntity) || !e.isAlive() || e == player) continue;
            if (!isHostile(e)) continue;
            if (e.distanceToSqr(player) > 900.0) continue; // 30 blocks — beyond this an arrow won't reach before we react
            LivingEntity mob = (LivingEntity) e;
            UUID id = mob.getUUID();
            boolean drawing = false;
            if (mob.isUsingItem()) {
                ItemStack used = mob.getUseItem();
                if (!used.isEmpty()) {
                    Item item = used.getItem();
                    if (item instanceof BowItem || item instanceof CrossbowItem) {
                        drawing = true;
                    }
                }
            }
            if (!drawing) {
                bowDrawStart.remove(id);
                continue;
            }
            // only pre-block if the mob is actually aiming at us (it faces its target while drawing)
            if (!isAimingAtUs(mob)) continue;
            long start = bowDrawStart.computeIfAbsent(id, k -> tickCount);
            long drawTicks = tickCount - start;
            if (drawTicks >= BOW_DRAW_PREBLOCK_AT) {
                hold = Math.max(hold, BOW_DRAW_HOLD); // re-armed each tick → holds through release
                // face the drawing mob so the shield covers its incoming shot (it fires toward where
                // it's looking — at us — so looking back at it puts our shield between us and the arrow)
                shieldAimPoint = mob.getEyePosition();
            }
        }
        return hold;
    }

    private void raiseShield() {
        Player player = ctx.player();
        if (player == null) return;
        InteractionHand hand = shieldHand(player);
        if (hand == null) return; // no shield equipped anywhere
        stopBowDraw(); // can't draw a bow and block at once
        // Start the block if we aren't already blocking. processRightClick alone only begins the use;
        // it doesn't sustain it — vanilla handleKeybinds sees the use key released next tick and calls
        // stopUsingItem(), dropping the shield immediately. So we ALSO hold the real use key down via
        // setDown, which keeps the block up across ticks. Re-asserted every tick while shielding.
        if (!player.isBlocking()) {
            ctx.playerController().processRightClick(ctx.player(), ctx.world(), hand);
        }
        holdUseKey(true);
        shielding = true;
    }

    private void lowerShield() {
        if (!shielding) return;
        // releaseUse clears the force-hold and sends RELEASE_USE_ITEM so the block drops immediately
        // (don't wait for the next input poll) and we can attack/move again this tick.
        releaseUse();
        shielding = false;
    }

    /** Which hand holds a shield, or null. Offhand preferred (keeps the main hand free). */
    private static InteractionHand shieldHand(Player player) {
        if (!player.getOffhandItem().isEmpty() && player.getOffhandItem().is(Items.SHIELD)) {
            return InteractionHand.OFF_HAND;
        }
        if (!player.getMainHandItem().isEmpty() && player.getMainHandItem().is(Items.SHIELD)) {
            return InteractionHand.MAIN_HAND;
        }
        return null;
    }

    // ----------------------------------------------------- use-item primitives

    /**
     * Hold/release the item-use key for a sustained use (bow draw, shield block). Sets Baritone's
     * force-use flag, which a mixin ({@code MixinMinecraft#baritone$suppressAutoRelease}) checks to
     * suppress vanilla's per-tick auto-{@code releaseUsingItem} — the call that would otherwise
     * cancel the draw/shield every tick because the real use key isn't held. The use itself is
     * started with {@code processRightClick}; this just keeps it alive. Stopped via {@link #releaseUse}.
     */
    private void holdUseKey(boolean down) {
        baritone.getInputOverrideHandler().setForceUsingItem(down);
    }

    /**
     * Release the in-use item the correct way: clears the force-hold, then calls
     * {@code releaseUsingItem} which sends the {@code RELEASE_USE_ITEM} packet and
     * {@code stopUsingItem}s. For a drawn bow this is what actually fires the arrow server-side
     * (with the accumulated charge); for a shield it drops the block. {@code player.stopUsingItem()}
     * alone does NOT send the release packet, desyncing the server and producing the "bow cancels
     * instead of firing" symptom, so this is the only release path combat uses.
     */
    private void releaseUse() {
        holdUseKey(false);
        try {
            Player player = ctx.player();
            if (player != null && player.isUsingItem()) {
                ctx.playerController().releaseUsingItem(player);
            }
        } catch (Throwable ignored) {
            // never crash combat over a release packet
        }
    }

    /**
     * Abort a bow draw in progress. releaseUse sends RELEASE_USE_ITEM so the server stays in sync;
     * a low-charge draw (the usual abort case) cancels without firing, while a near-full draw looses
     * a stray arrow — harmless either way.
     */
    private void stopBowDraw() {
        if (bowDrawTicks > 0) {
            releaseUse();
            bowDrawTicks = 0;
        }
    }

    /** Clear the forced movement/aux keys combat manages (strafe, backpedal, jump, sprint). */
    private void clearAuxKeys() {
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
    }

    // ------------------------------------------------------------- reach buff

    private void applyReachBuff() {
        Player player = ctx.player();
        if (player == null) return;
        if (!Double.isNaN(originalReach)) return; // already buffed
        double reach = Baritone.settings().combatReach.value;
        if (reach <= 3.0) return; // no buff requested (vanilla reach)
        var attr = player.getAttribute(Attributes.ENTITY_INTERACTION_RANGE);
        if (attr == null) return;
        originalReach = attr.getBaseValue();
        attr.setBaseValue(reach);
        buffedPlayerUuid = player.getUUID();
        applyServerReachBuff(reach);
    }

    /**
     * On an integrated (single-player) server, the server-side Player is what validates attack
     * range — so buffing only the client player wouldn't extend real hit distance. Apply the same
     * buff to the matching server player. No-op on dedicated servers (server == null).
     */
    private void applyServerReachBuff(double reach) {
        MinecraftServer server = ctx.minecraft().getSingleplayerServer();
        if (server == null || buffedPlayerUuid == null) return;
        final UUID uuid = buffedPlayerUuid;
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
            if (sp == null) return;
            var attr = sp.getAttribute(Attributes.ENTITY_INTERACTION_RANGE);
            if (attr == null) return;
            if (Double.isNaN(originalReachServer)) {
                originalReachServer = attr.getBaseValue();
            }
            attr.setBaseValue(reach);
        });
    }

    private void restoreReach() {
        Player player = ctx.player();
        if (player != null && !Double.isNaN(originalReach)) {
            var attr = player.getAttribute(Attributes.ENTITY_INTERACTION_RANGE);
            if (attr != null) {
                attr.setBaseValue(originalReach);
            }
            originalReach = Double.NaN;
        }
        if (buffedPlayerUuid != null) {
            final UUID uuid = buffedPlayerUuid;
            buffedPlayerUuid = null;
            final double orig = Double.isNaN(originalReachServer) ? 3.0 : originalReachServer;
            originalReachServer = Double.NaN;
            MinecraftServer server = ctx.minecraft().getSingleplayerServer();
            if (server != null) {
                server.execute(() -> {
                    ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
                    if (sp != null) {
                        var attr = sp.getAttribute(Attributes.ENTITY_INTERACTION_RANGE);
                        if (attr != null) {
                            attr.setBaseValue(orig);
                        }
                    }
                });
            }
        }
    }

    // ---------------------------------------------------------------- misc

    private void enterRetreat() {
        if (state != State.RETREAT) {
            logDirect("Backing off — too dangerous.");
            target = null; // don't snap back to the mob that almost killed us
            setState(State.RETREAT);
        }
        // Default: an HP/count-driven retreat re-engages on health recovery. The hold-too-long
        // failsafe sets retreatPinned=true AFTER calling this so doRetreat re-engages on space instead.
        retreatPinned = false;
        // Don't pre-set a movement goal here. The old code immediately queued a GoalRunAway (an
        // instant about-face) on the triggering tick; doRetreat now decides per-tick whether to
        // backpedal (face the threat, MOVE_BACK) or escalate to a turn-and-run flee, so leaving
        // moveGoal null here just pauses pathing for the one tick until doRetreat takes over.
    }

    private void setState(State next) {
        state = next;
    }

    private static int jitter(int lo, int hi) {
        return ThreadLocalRandom.current().nextInt(lo, hi + 1);
    }

    private static double jitter(double lo, double hi) {
        return ThreadLocalRandom.current().nextDouble(lo, hi);
    }

    @Override
    public String displayName0() {
        if (mode == Mode.AUTODEFEND) {
            return "Auto-defend" + (target != null ? " (" + target.getType().toShortString() + ")" : "");
        }
        return "Hunt " + (filter != null ? filter.describe() : "?");
    }
}
