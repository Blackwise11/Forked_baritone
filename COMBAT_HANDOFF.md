# Baritone Native Combat + Auto-Eat — Handoff

**What this is:** Combat (mob fighting) and auto-eat were ported **natively into
Baritone** on the `26.1` / NeoForge branch, as first-class `IBaritoneProcess`es
(ported from Arion's `CombatController` + `SurvivalManager`). This is the fork's
main delta over upstream Baritone. This doc is the single best onboarding
reference for the combat work — read it before changing combat/shield/bow code.

- **Minecraft:** 26.1.2 · **Loader:** NeoForge 26.1.2.94 · **Java:** 25 toolchain (Foojay)
- **Branch:** `26.1` (the `1.20.1` Fabric branch does **not** have this port)
- **Mod id:** `baritoe` (genuinely the id — an in-joke; Arion depends on this exact id)
- **Build:** `./gradlew.bat :neoforge:build` → `dist/` jars (api / standalone / unoptimized)

> **Current state (build #8):** combat compiles + builds clean; the four recent feature
> passes are **runtime-untested** — (1) melee rhythm blocking §6b, (2) humanized camera
> easing §12, (3) disengage-first backpedal retreat §13, (4) ranged-clump strafe + pillager
> fix §14. Everything before that (the §8 bug table rows up to the bow-LOS fix) is the
> proven baseline. Play-test §11 steps 1–11 in a live game.

> **Runtime install rule (load-bearing):** install
> `dist/baritone-unoptimized-neoforge-*.jar` — **NEVER `baritone-standalone-*`**.
> The standalone jar strips `baritone/api/event/`, causing
> `NoClassDefFoundError: baritone/api/event/listener/IGameEventListener` at runtime
> for anything (Arion) that compiles against the full API jar.

---

## 1. Where everything lives

| Path | What |
|---|---|
| `src/api/java/baritone/api/process/ICombatProcess.java` | Public combat API: `hunt`, `currentTarget`, `stop`, `autoDefendMode`, `isAutoDefending`. Extends `IBaritoneProcess`. |
| `src/api/java/baritone/api/process/AutoDefendMode.java` | enum `OFF / TASK / ALWAYS`. |
| `src/main/java/baritone/process/CombatProcess.java` | **The main file.** State machine, shield reflex, bow, weapons, reach buff. Most edits happen here. |
| `src/main/java/baritone/process/SurvivalProcess.java` | Auto-eat + death/respawn. Temporary process, priority 5.2. |
| `src/main/java/baritone/process/combat/ProjectileThreat.java` | Step-simulation projectile impact predictor. |
| `src/main/java/baritone/process/combat/{MobTactic,HuntFilter,WeaponType}.java` | Per-mob tactics (incl. `Pillager` ranged), hunt-target filter, weapon classification. |
| `src/main/java/baritone/behavior/LookBehavior.java` + `src/api/java/baritone/api/behavior/ILookBehavior.java` | The eased-camera layer (§12): `updateTarget(rot, blockInteract, ease)` overload + `easeToward`. Core look path — fragile, edit carefully. |
| `src/main/java/baritone/pathing/movement/Movement.java` | Non-forced pathing movement aims opt into camera easing (`updateTarget(rot, force, !force)`); forced (block break/place) aims stay exact. |
| `src/main/java/baritone/command/defaults/{HuntCommand,EatCommand}.java` | `#hunt` / `#eat` chat commands (keyword enums carry `@KeepName`). |
| `src/launch/java/baritone/launch/mixins/MixinPlayerController.java` | `@Inject` on `releaseUsingItem` — suppresses vanilla's per-tick auto-release while Baritone force-holds use. **THE** item-use sustainability hook. |
| `src/api/java/baritone/api/utils/IInputOverrideHandler.java` + `src/main/java/baritone/utils/InputOverrideHandler.java` | `forceUsingItem` flag + `isForceUsingItem`/`setForceUsingItem`. |
| `src/api/java/baritone/api/utils/IPlayerController.java` + `src/main/java/baritone/utils/player/BaritonePlayerController.java` | `releaseUsingItem(Player)` (sends RELEASE_USE_ITEM), `attack(Player, Entity)` (attack + swing — vanilla doesn't swing). |
| `src/api/java/baritone/api/Settings.java` | All `combat*` + `autoEat*` settings (see §4). |
| `../idea.md` | The user's spec for a full threat-model auto-shield (COLLECT→NORMALIZE→PREDICT→…→BLOCK/RECOVER). Source of truth for the *intended* shield architecture; we've implemented a compact slice of it, not the full 30-class refactor. |

---

## 2. Build & run

```bash
cd Java_project/baritone
./gradlew.bat :neoforge:build        # all of api/standalone/unoptimized → dist/
./gradlew.bat :neoforge:build -x proguard   # skip obfuscation (faster, for quick compile checks)
./gradlew.bat test                    # JUnit tests (root project only)
```

After a successful build, refresh the Arion compile-time reference:
```bash
cp dist/baritone-api-neoforge-*.jar ../arion/libs/
```
Then install `dist/baritone-unoptimized-neoforge-*.jar` into the game's `mods/`.

**Version string** comes from `git describe --always --tags --first-parent --dirty`
at configure time — that's why `dist/` jars are named `...-dirty.jar` (working tree
is uncommitted). **ProGuard jmods:** the provisioned Adoptium JDK 25 is stripped
(no `jmods/`); `jmods/` was copied from a full Azul Zulu JDK 25 into the toolchain
dir. If proguard fails with a missing-jmod error, that's why.

In-game commands (prefix `#`, gated by `chatControl`/`prefixControl`):
```
#hunt zombie   #hunt nearest   #hunt all   #hunt stop
#hunt autodefend [off|task|always]   #hunt bow [on|off]
#eat now   #eat on   #eat off
#cancel   #forcecancel
```

---

## 3. Architecture (the pipeline)

```
  onTick (every game tick)
     │
     ├─ auto-defend yield? (AUTODEFEND + no threat in pursue range → DEFER, frozen task resumes)
     ├─ safety reflexes (low HP / too many attackers → RETREAT)
     ├─ re-target reflex (nearest hostile in reaction range swaps in)
     ├─ SHIELD REFLEX (gated on a shield actually equipped — see §6)
     │     rangedDrawHold (skipped vs ≥2 ranged mobs — see §14) / ProjectileThreat / melee rhythm
     │     → raiseShield + face threat, else lowerShield and let the state machine run
     ├─ duration failsafe (manual hunts)
     └─ state switch:
        ACQUIRE → (SEARCH) → APPROACH → STRIKE → RECOVER → (RETREAT)
```

- **One process controls `PathingBehavior` at a time.** `priority()` = 1.0 (preempts
  Mine/Follow; below pausers at 5/5.1). `isTemporary()` is true only for AUTODEFEND —
  a temporary process *freezes* (not cancels) the task it preempts, so MineProcess
  resumes intact. MANUAL is non-temporary and seizes control.
- **Movement** is a `PathingCommand` returned from `onTick`; **attacks** are direct
  side-effects via `ctx.playerController().attack(...)`; **aim** goes through
  `baritone.getLookBehavior().updateTarget(rot, true, true)` every tick (combat opts
  into the eased-camera layer — see §12; `humanizeCamera` default off = exact snap).
- **Reach buff** is applied to BOTH the client player and the integrated-server
  `ServerPlayer` (by UUID) so out-of-range hits land in single-player; restored in
  `onLostControl()`/`cleanup()`.
- **Hostile detection** is `instanceof Monster || Slime || Phantom` by type + proximity
  — **NEVER `Mob.getTarget()`** (not synced to the client).

---

## 4. Settings (`Settings.java`, all `combat*` / `autoEat*`)

| Setting | Default | Role |
|---|---|---|
| `combatEnabled` | true | Master toggle; `#hunt` refuses when false. |
| `combatReach` | 4.5 | Reach buff target (`Attributes.ENTITY_INTERACTION_RANGE`); ≤3.0 = no buff. |
| `combatHumanizeAim` | true | Respect attack-cooldown scale (lower DPS, fairer look). |
| `combatRetreatHealth` | 6.0 | HP below which → RETREAT. |
| `combatMaxAttackers` | 3 | More hostiles than this in reaction range → RETREAT. |
| `combatSearchTimeoutSeconds` | 60 | SEARCH gives up after this. |
| `combatMaxDurationSeconds` | 300 | Manual hunt failsafe (auto-defend is threat-gated, uncapped). |
| `combatAutoDefendMode` | OFF | `OFF`/`TASK`/`ALWAYS`. TASK = only while `PathingBehavior.isPathing()`. |
| `combatDefendRange` | 10.0 | Self-activate radius. |
| `combatDefendPursueRange` | 18.0 | Yield when no hostile in this radius (> DefendRange so a drifting kill finishes). |
| `combatUseShield` | true | Enable the shield reflex. |
| `combatProjectileLookahead` | 10 | Ticks to simulate projectiles forward. |
| `combatShieldRadius` | 0.5 | AABB inflate amount for the projectile impact test. |
| `combatShieldLeadTicks` | 5 | Raise the shield this many ticks before predicted impact. |
| `combatMeleeBlock` | true | Predictive melee blocking: track swing rhythm, block the imminent hit, strike on the cooldown. Only for mobs inside their reach (can't kite). See §6b. |
| `combatMeleeShieldMaxHold` | 40 | Hard cap (ticks) on holding the shield vs melee with no strike → retreat + re-engage. Prevents pinning until the shield breaks. |
| `combatDodgeRanged` | true | Key-based perpendicular strafe vs aiming ranged mobs. |
| `combatDodgeAngle` | 18.0 | Aim-cone degrees for "the mob is aiming at us". |
| `combatUseBow` | true | Use a bow when out of melee range. |
| `combatBowMinRange` | 6.0 | Don't bow closer than this. |
| `combatBowDrawTicks` | 20 | Ticks to draw before releasing (≈ full charge). |
| `combatForceBow` | false | Force bow at any range (testing). Cleared on cleanup. |
| `combatVelocityStrikes` | true | Prefer mace/spear; time strikes to player velocity. |
| `autoEat` | false | Auto-eat below `autoEatBelowFood`. |
| `autoEatBelowFood` | 14.0 | Hunger threshold. |
| `autoResumeAfterDeath` | false | Best-effort respawn (soft/flagged). |
| `humanizeCamera` | false | Master toggle for the eased-camera layer (see §12). Combat aiming + non-forced pathing movement aims opt in; block-break/place aims stay pixel-exact. |
| `cameraMaxTurnRate` | 60.0 | Max yaw deg/tick during a flick (large delta). Pitch uses half this. |
| `cameraMinTurnRate` | 2.0 | Below this remaining gap, snap to desired (arrival; no permanent fractional offset). |
| `cameraSmallDeltaRate` | 0.3 | Fraction of the gap closed per tick while tracking (small delta). |
| `cameraFlickThreshold` | 40.0 | Yaw delta above which a flick (capped rate) is used instead of a track. |

---

## 5. THE item-use root cause (eat + shield + bow) — do not re-debug

This was the single hardest-won finding. **Read before touching any use-item code.**

**The problem:** Baritone's forced `Input.CLICK_RIGHT` only feeds `BlockPlaceHelper`
(which bails unless the crosshair is on a block) — it does NOT hold `options.keyUse`.
Vanilla `Minecraft.startUseItem()` runs **every tick**:
```java
if (player.isUsingItem() && !options.keyUse.isDown()) gameMode.releaseUsingItem(player);
```
So any sustained use was cancelled each tick: a bow never charged ("cancels"), a
shield dropped instantly, eating restarted from tick 0 ("holds food too long"). The
build #2 attempt (`options.keyUse.setDown(true)`) failed because vanilla `KeyMapping`
polling overwrites `isDown` with the real mouse state each tick.

**Also found:** `player.stopUsingItem()` does **NOT** send the `RELEASE_USE_ITEM`
packet — only `MultiPlayerGameMode.releaseUsingItem(Player)` does (verified: it sends
`ServerboundPlayerActionPacket(RELEASE_USE_ITEM)` then `player.releaseUsingItem()`).
So firing a bow via `stopUsingItem()` desynced the server (arrow didn't fire
server-side).

**The fix (in place):**
1. `IInputOverrideHandler`/`InputOverrideHandler` got a `forceUsingItem` flag
   (`isForceUsingItem`/`setForceUsingItem`, cleared in `clearAllKeys`).
2. `holdUseKey(boolean)` in `CombatProcess` + `SurvivalProcess` sets the flag
   (NOT `options.keyUse.setDown`).
3. `MixinPlayerController` `@Inject(method="releaseUsingItem", at=@At("HEAD"),
   cancellable=true)` cancels the call when the primary baritone's
   `isForceUsingItem()` is true AND the player arg is the primary player. → vanilla's
   auto-release is suppressed while Baritone force-holds.
4. `releaseUse()` = `setForceUsingItem(false)` **FIRST**, then
   `ctx.playerController().releaseUsingItem(player)` (sends RELEASE_USE_ITEM). Because
   the flag is cleared first, the mixin lets Baritone's *own* release through — the
   cancel scope is exactly "release while mid-forced-use."
5. `processRightClick` (→ `gameMode.useItem`) starts the use; `holdUseKey(true)`
   sustains it. Natural completion (food eaten) goes through
   `LivingEntity.updateUsingItem`→`completeUsingItem`, **not** `releaseUsingItem`, so
   eating still finishes.

**Mixin rule (do not regress):** do **NOT** use a call-site `@Redirect` inside
`Minecraft.startUseItem`. Build #3 did (`@Redirect` on the `releaseUsingItem` call
inside `startUseItem`) and it **crashed on load** under NeoForge:
`Critical injection failure ... (0/1) succeeded. Scanned 0 target(s).` — the
NeoForge-patched client jar reshapes `startUseItem`'s invokes, and `defaultRequire:1`
makes a miss fatal. The HEAD-inject on the stable public `releaseUsingItem(Player)`
method (in `MixinPlayerController`, which already `@Mixin(MultiPlayerGameMode)`) is
robust to patches. Keep it that way.

---

## 6. The shield reflex (current state — a compact slice of `idea.md`)

The full `idea.md` spec is a 30-class threat-model architecture (Threat/ThreatRegistry/
TrajectoryEngine/DefenseController/strategies). **We have NOT built that.** We have a
compact in-`CombatProcess` pipeline that implements the same *ideas* —
collect→normalize→predict→TTI→decide→block→recover — without the class explosion.
Expand toward the full registry/strategy architecture only if the compact version
proves insufficient.

**The reflex (in `onTick`):**
```java
if (bowDrawTicks == 0 && combatUseShield && shieldHand(player) != null) {
    int fresh = shieldDecision(player);     // sets shieldAimPoint
    if (fresh > 0) shieldHoldTicks = fresh;
    if (shieldHoldTicks > 0) {
        aimAtPoint(shieldAimPoint);          // face the THREAT, not the locked target
        raiseShield();
        shieldHoldTicks--;
        return new PathingCommand(null, REQUEST_PAUSE);
    }
    lowerShield();
} else {
    if (shieldHoldTicks > 0) shieldHoldTicks = 0;   // drop stale holds
    lowerShield();
}
```

**`shieldDecision(player)` returns hold-ticks from three signals:**
1. **Ranged draw** (`rangedDrawHold`) — a hostile drawing a bow/crossbow aimed at us.
   `LivingEntity.isUsingItem()` is EntityData-synced (client-visible); `getUseItem()`
   exposes the drawn item, so we can *see* a skeleton nocking. Per-mob `bowDrawStart`
   map (UUID→tickCount) tracks draw progress; pre-raise once
   `drawTicks >= BOW_DRAW_PREBLOCK_AT` (12 of ~20), re-armed each tick
   (`BOW_DRAW_HOLD=4`) → holds through release. Gated by `isAimingAtUs(mob)`.
   **Skipped entirely when `countRangedAttackers ≥ RANGED_CLUMP_THRESHOLD` (2)** — a
   shield covers one hemisphere, so vs 2+ shooters pre-blocking every draw pins the bot
   forever; the projectile-impact layer (signal 2) still catches a homing arrow. See §14.
2. **Projectile** (`ProjectileThreat.imminent`) — step-sim each projectile
   (drag 0.99 + gravity -0.05) against the player's inflated AABB; first tick it
   enters the box = impact. Raise when `ticksToImpact <= combatShieldLeadTicks`,
   hold `ticksToImpact + PROJECTILE_POSTHIT_HOLD` (3). **Skips projectiles with
   `deltaMovement.lengthSqr() < 0.04`** — a stuck/landed arrow is still a `Projectile`
   entity and would otherwise trigger a permanent tick-1 impact ("blocks arrows on
   the floor").
3. **Melee rhythm (selective block)** — a melee mob inside its attack reach
   (`MELEE_BLOCK_RANGE` 3.0 — we can't kite) whose cooldown is about to expire
   gets its next swing pre-blocked; a mob still on cooldown returns 0 so the shield
   drops and the state machine strikes during the window. See §6b.

**Reactive melee blocking is impossible client-side** (verified via bytecode:
`MeleeAttackGoal.checkAndPerformAttack` calls `resetAttackCooldown()` →
`mob.swing()` → `mob.doHurtTarget()` on the same server tick — the damage lands
with/instantly-with the swing, so a client-side raise that late can't intercept it).
So melee defense is **predictive**: track each mob's swing rhythm, block the
*imminent* hit, then exploit the cooldown. **Holding the shield against a clump is a
trap** — it suppresses our own offense and drains the shield — so the hold is
selective (one hit) and bounded by the hold-too-long failsafe (§6b). Kite-able melee
fights (outside the mob's ~3.0 reach) are not shielded at all; we kite and strike.

**Omnidirectional facing:** a shield only covers the front hemisphere, so to block a
skeleton's arrow from the left while locked on a zombie ahead, we face the *arrow*.
`shieldAimPoint` is set by `shieldDecision` to the most imminent threat (incoming
projectile position → overrides; else the drawing mob's eye; else the melee mob about
to swing). The reflex `aimAtPoint(shieldAimPoint)` instead of `aim(target)`.

**`raiseShield()`/`lowerShield()`:** `raiseShield` no-ops when `shieldHand(player)==null`
(offhand preferred); else `stopBowDraw()` + `processRightClick(hand)` if not blocking +
`holdUseKey(true)`. `lowerShield` = `releaseUse()`.

---

## 6b. Melee rhythm — selective block + strike-on-cooldown (NEW)

The third `shieldDecision` signal, replacing the old low-HP turtle. The play, per
playtesting: against a melee mob you can't kite (inside its ~3.0 reach), **block the
specific imminent hit, then drop the shield and strike during the mob's attack
cooldown.** A mob that swung is on cooldown whether or not its hit landed — verified
via bytecode that `MeleeAttackGoal.resetAttackCooldown()` runs *before* `swing()`/
`doHurtTarget()` and the `doHurtTarget` return value is `pop`-ed (never branched on),
so a blocked hit still spends the mob's swing. That cooldown is the engage window.

**Why selective, not held:** holding the shield against a clump pins the bot (no
offense, shield drains). Worse — a blocked hit sets `LivingEntity.lastHurt = 0`
(damage was reduced to 0), and the i-frame negation rule is "negate the next hit if
its damage ≤ lastHurt," so after a block the next *real* hit is **not** i-frame
absorbed (it lands for full damage). Taking a hit instead sets `lastHurt = realDamage`,
giving ~10 ticks where other equal mobs' hits *are* absorbed. So blocking trades
i-frame protection for HP preservation — fine for a single mob, bad to hold against a
clump. (CORRECTION to an earlier note: blocked hits **do** grant i-frames on 26.1 —
`INVULNERABLE_DURATION = 20`, effective window ~10 ticks — but they zero `lastHurt`,
which makes those i-frames unable to absorb the next equal hit. Net effect matches the
playtest: "if you block, every mob in the clump registers onto you.")

**The tracker** (`meleeLastSwing`/`meleeInterval`/`meleePrevSwinging`, UUID-keyed):
each tick `updateMeleeRhythm` scans hostiles in `REACTION_RANGE` and detects a rising
edge of the synced `LivingEntity.swinging` field (`swing()` sets `swingTime = -1`,
`swinging = true`; the swing is broadcast to the client via `ClientboundAnimatePacket`).
On a rising edge it timestamps `meleeLastSwing` and, once two swings are seen, measures
`meleeInterval` (self-calibrating — varies by attack-speed attribute/difficulty;
baseline 20 = `MeleeAttackGoal`'s `adjustedTickDelay(20)` until measured). Every
detected swing re-anchors the prediction (self-correcting drift).

**`meleeRhythmHold(player)`** returns hold-ticks for the most-imminent swing among
mobs inside `MELEE_BLOCK_RANGE` (3.0): `0` while a mob is on cooldown (→ strike
window, shield drops, state machine strikes), `>0` when its cooldown is about to
expire (→ raise, hold past the predicted swing). The first swing from an unseen mob is
**not** pre-blocked (unblockable anyway — take it, record the swing, then rhythm
engages). Stale readings (no swing for >2× interval) hold only briefly.

**Hold-too-long failsafe** (`combatMeleeShieldMaxHold`, default 40t ≈ 2s): if the
shield is held against melee for that many consecutive ticks without a strike landing
(a swing we couldn't detect, or a clump re-acquiring faster than we can exploit), the
bot drops the shield, enters RETREAT (`retreatPinned=true`), and re-engages once it
has **space** (no mob in `MELEE_BLOCK_RANGE`) — not on HP recovery, so it actually
creates distance before fighting again. The streak resets on every landed strike and
on shield-drop. Projectile/ranged holds don't count toward the streak (only
melee-attributed holds). **This is the guardrail that guarantees the shield can never
be pinned until it breaks.**

**Settings:** `combatMeleeBlock` (default true) toggles the feature;
`combatMeleeShieldMaxHold` (default 40) is the retreat threshold. Both cleared in
`cleanup()`/`yieldAutoDefend()` along with the tracker maps.

---

## 7. Bow + weapons

- **`doBowStrike`** calls `aim(target, true)` — **lead prediction**: flight time
  `t = max(1, dist/2.8)` (arrow ~3.0 b/tick full-draw, 0.99 drag), project target by
  `deltaMovement × t` (clamped to 0.6×dist), plus gravity-drop compensation
  `+½·0.05·t²`. Same physics as `ProjectileThreat`'s step-sim, inverted (there predict
  landing, here solve launch angle). Draw via `processRightClick(MAIN_HAND)` +
  `holdUseKey(true)`; release via `releaseUse()` at `combatBowDrawTicks`.
- **`doStrike`** calls `equipBestWeapon()` on every STRIKE entry (non-bow branch) —
  this is the **autotool-vs-weapon fix**: during APPROACH Baritone's autotool
  (`InventoryBehavior`) selects the best pickaxe/shovel to dig an obstructing block
  (desired); on reaching melee range nothing re-equipped the sword, so the bot swung
  with a shovel. `equipBestWeapon` re-selects the sword/mace each strike (cheap:
  STRIKE is transient, `setSelectedSlot` to same slot is a no-op, `attack` syncs the
  held slot to the server before the hit).
- **Sword prioritization** (`equipBestWeapon`): 26.1 has NO `SwordItem`/`PickaxeItem`
  classes (data-driven). Detect via `BuiltInRegistries.ITEM.getKey(item).getPath()` —
  `isSword` = endsWith `_sword`; `isUnwantedTool` = endsWith
  `_pickaxe`/`_shovel`/`_hoe`/`_shears`/`_brush` (check `_pickaxe` before `_axe`).
  Swords always beat non-swords even when an axe's raw ATTACK_DAMAGE is higher;
  unwanted tools skipped; velocity-scaled (mace/spear) preferred when
  `combatVelocityStrikes`.
- **Weapon-slot restoration**: `priorWeaponSlot` recorded at `hunt()`/`beginAutoDefend()`
  (first time only), restored in `cleanup()` + `yieldAutoDefend()`. Fixes "doesn't
  switch back to pickaxe after a hunt."
- **`WeaponType`** enum (`process/combat/`): MELEE/BOW/VELOCITY_SCALED. `forStack` —
  `BowItem`→BOW, `Items.MACE` or id contains "spear"→VELOCITY_SCALED (NOT trident —
  its id "trident" lacks "spear"). `doVelocityStrike` times the strike to player
  velocity (mace: jump + strike on `fallDistance>0`; spear: sprint + strike on
  horiz speed >0.25).

---

## 8. Bugs fixed this work (so you don't re-debug)

| Symptom | Root cause | Fix |
|---|---|---|
| Eat "holds food too long" / never completes | vanilla auto-released the use every tick | `forceUsingItem` flag + mixin suppression (§5) |
| Bow "cancels instead of firing" | (a) shield reflex ran every tick → `raiseShield`→`stopBowDraw`; (b) re-target `equipBestWeapon` switched hotbar mid-draw; (c) `stopUsingItem()` doesn't send RELEASE packet | (a)(b) guard with `bowDrawTicks == 0`; (c) fire via `releaseUsingItem` (§5) |
| Shield "not blocking when about to get hit" | straight-line closest-approach missed arcing arrows | `ProjectileThreat` step-sim with AABB intersection |
| Shield "holding without purpose" | the old low-HP turtle held the shield up against any melee threat constantly | replaced by the selective melee-rhythm block (§6b) — block only the imminent swing, drop on cooldown |
| Mixin crash on load `(0/1) succeeded` | call-site `@Redirect` in `startUseItem` matched zero targets under NeoForge | moved to HEAD-inject on `releaseUsingItem` in `MixinPlayerController` (§5) |
| **"auto shield activates even without shield, all combat bugs out"** | shield reflex returned `REQUEST_PAUSE` even when `raiseShield()` no-ops (no shield) → combat froze every tick | gate the whole reflex on `shieldHand(player) != null`; `else` clears stale holds |
| **"blocks arrows ON THE FLOOR"** | a landed/stuck arrow is still a `Projectile` entity; ~0 velocity never leaves the inflated box → permanent tick-1 impact | skip projectiles with `deltaMovement.lengthSqr() < 0.04` |
| **"doesn't switch back to sword after pathing"** | autotool selected shovel to clear path; nothing re-equipped on STRIKE | `doStrike` calls `equipBestWeapon()` each strike (§7) |
| No bow aiming prediction | `aim()` snapshot-aimed at current eye position | `aim(target, true)` lead prediction (§7) |
| **"keeps blocking the arrow until it lands on the floor"** | a blocked/missed arrow is still a `Projectile` near the player with >0.2 b/tick velocity; the step-sim re-predicted a tick-1 impact every tick and re-armed `shieldHoldTicks`, so the block only dropped once the arrow finally stopped moving | `ProjectileThreat.imminent` skips a close projectile that is no longer closing on the player (`v·(boxCenter−p) ≤ 0` when `distToBox < 3`) — a receding/tangential arrow is post-impact; shield now drops after the 3-tick post-hit hold |
| **"won't re-engage a lot of mobs / flees forever"** | `attackers > combatMaxAttackers` retreated at full HP, and `doRetreat` only re-engaged when `attackers ≤ maxAttackers` — a large pack never dropped below the count, so the bot ran indefinitely | count-retreat now also requires `health < retreatHealth+6`; `doRetreat` re-engages on HP recovery alone — a healthy bot fights the group (kiting+shield), retreats only when hurt |
| **"fires the bow when the mob is behind a block"** | no line-of-sight check; the bot drew and loosed into walls/terrain | `doApproach` and `doBowStrike` gate on `player.hasLineOfSight(target)`; no LOS → stop draw, close to melee |
| **"holds the shield forever against a clump until it breaks"** | the old low-HP turtle held the shield up against any melee threat; blocking zeroes `lastHurt` so i-frames can't absorb the next hit, so every mob keeps registering → pinned, no offense, shield drains | replaced by selective melee-rhythm block (§6b): block the imminent hit, strike on the mob's cooldown; `combatMeleeShieldMaxHold` failsafe retreats + re-engages if held too long without a strike |
| **i-frame mechanism (correction)** | earlier note claimed "blocked hits grant no i-frames" | bytecode says they DO grant i-frames (`INVULNERABLE_DURATION=20`), but set `lastHurt=0`, so the next real hit (damage>0) isn't i-frame-absorbed — same net effect for a clump, different cause |
| **"stands still, shield up, never engages vs 5+ skeletons/pillagers"** | `rangedDrawHold` pre-blocks every mob drawing a bow aimed at us; against a staggered clump one is *always* in its pre-release draw, so `shieldHoldTicks` is re-armed every tick → the reflex returns `REQUEST_PAUSE` every tick → the state machine (where `strafe()` lives, in `doRecover`) never runs. Pre-blocking is also a single-target defense (a shield covers one hemisphere) — you can't block 2+ shooters at once | `shieldDecision` skips the ranged-draw pre-block when `countRangedAttackers ≥ RANGED_CLUMP_THRESHOLD` (2); the state machine runs (APPROACH strafe-and-advances, RECOVER strafes) and the **projectile-impact** layer still raises the shield for an arrow actually predicted to hit — "block when the arrow is homing," not when one of many mobs is merely drawing. Single-archer fights keep the draw pre-block |
| **pillagers treated as melee (never strafed, no ranged logic)** | `Pillager` was missing from `MobTactic.forEntity` → fell to `GENERIC` (`ranged=false`) | added `Pillager` → ranged tactic (crossbow); now counted by `countRangedAttackers` and strafed/dodged like skeletons |

---

## 9. Known gaps / next work

1. **Play-test the current build (build #8: ranged-clump strafe + pillager fix).**
   Everything compiles and builds; the jar is well-formed. The shield no-shield freeze,
   stuck-arrow, autotool sword-swap, bow lead, melee rhythm, **camera easing (§12)**,
   **backpedal retreat (§13)** and **ranged-clump strafe (§14)** are all **runtime-untested** —
   re-test in a live game. Runtime jar: `dist/baritone-unoptimized-neoforge-*.jar`.
2. **Full `idea.md` threat-model architecture.** The compact slice works for vanilla
   arrows/fireballs/bow-draw. The full spec adds: Warden Sonic Boom (RayThreat),
   area/explosion threats, modded projectile adapters, a `ThreatRegistry` +
   `DefenseController` state machine, 360° interception geometry, a debug
   renderer/HUD, and confidence-weighted scoring. Implement phase-by-phase per
   `idea.md` §38 only if the compact version proves insufficient — start with a
   `RayThreat` for Sonic Boom and a `ThreatRegistry` if multi-threat prioritization
   becomes a real problem.
3. **Melee shield — now predictive (§6b), runtime-untested.** Replaced the turtle with
   selective rhythm-based blocking: block the imminent swing, strike on the cooldown,
   retreat if held too long. The first swing from an unseen mob still lands (unblockable
   client-side). ~60–80% block rate on predictable mobs expected; tune
   `combatMeleeBlock`/`combatMeleeShieldMaxHold` from playtests. Open question: vs a
   clump, whether taking the entry hit (for i-frames) outperforms blocking it (lastHurt=0
   weakens i-frames) — currently the bot blocks selectively; a take-the-hit entry mode is
   a future toggle if chip damage is too high.
4. **Bow lead tuning.** The `2.8 b/tick` average arrow speed and gravity model are
   approximations. If arrows consistently miss leading/short, calibrate against actual
   arrow trajectory (a full-draw arrow starts at 3.0 and decays with 0.99 drag; the
   effective average over a shot depends on distance).
5. **Arion integration.** Copy `dist/baritone-api-neoforge-*.jar` into `arion/libs/`
   (done after each build) so Arion can call `IBaritone#getCombatProcess()` instead of
   its own `CombatController`. (Follow-up flagged in the arion `plan/HANDOFF.md`.)
6. **Respawn** (`autoResumeAfterDeath`) is best-effort (sends
   `ServerboundClientCommandPacket(PERFORM_RESPAWN)`); flagged soft. If it misbehaves,
   scope down to lastDeathPos-only.
7. **Camera easing (§12) — runtime-untested, plus a freeLook caveat.** With default
   `freeLook=true` + `antiCheatCompatibility=true`, non-forced pathing aims resolve to
   SERVER (silent) — so the *visible* pathing-camera easing only applies when freeLook is
   off. Combat aiming is CLIENT regardless, so the combat "too quick turn" fix works with
   defaults. **Lookahead** ("look toward the turn point before the bend") is deferred to a
   Phase 2 — with freeLook on it needs a decoupled visible-camera channel (drive the
   visible camera toward a point ahead on the path while movement keeps its silent
   rotation); the `cameraLookahead*` settings were intentionally **not** added yet to
   avoid dead config. Verify eased turns look human and don't hurt hit-registration.
8. **Retreat (§13) — runtime-untested.** Verify the backpedal (MOVE_BACK under
   REQUEST_PAUSE) actually opens space, that `retreatBlocked` doesn't false-positive on
   slopes/stairs, and that the shield-hold pause doesn't stutter the retreat into a
   standstill.
9. **Ranged-clump strafe (§14) — runtime-untested.** Strafe-and-advance is key-based (no
   pathing) so it may bump in rough terrain — open-ground clumps are the intended case. If
   arrows still land too often, tune `combatDodgeAngle` / the strafe flip cadence.
   `RANGED_CLUMP_THRESHOLD` is a constant (2); promote to a setting if tuning is wanted.

---

## 10. Guardrails baked in (don't regress)

- **Shield reflex is gated on a shield being equipped.** Never return `REQUEST_PAUSE`
  from the reflex without `shieldHand(player) != null` — that's the freeze bug.
- **`bowDrawTicks == 0` guards** on the shield reflex and on re-target
  `equipBestWeapon`/`applyReachBuff` — don't switch the hotbar or raise the shield
  mid-draw, or the bow never charges.
- **Reach buff always restored** in `cleanup()`/`onLostControl()` (client + server
  player by UUID) — or the player keeps a permanently-long reach.
- **`releaseUse()` clears the flag FIRST**, then calls `releaseUsingItem` — so the
  mixin lets Baritone's own release through. Any new release path must follow this.
- **Hostile detection** is type + proximity, never `Mob.getTarget()`.
- **Stale-hold cleanup**: `shieldHoldTicks`/`shieldAimPoint`/`bowDrawStart` **and the
  melee tracker** (`meleeLastSwing`/`meleeInterval`/`meleePrevSwinging`/`meleeShieldStreak`/
  `retreatPinned`) cleared in `cleanup()` + `yieldAutoDefend()`.
- **Melee hold is selective + bounded.** Never hold the shield against melee without (a)
  returning 0 during the mob's cooldown so the state machine can strike, and (b) the
  `combatMeleeShieldMaxHold` failsafe retreating if a hold runs away. Removing either
  reintroduces the "pinned until the shield breaks" trap.
- **Ranged-draw pre-block is single-target only.** Never pre-block every drawing mob when
  `countRangedAttackers ≥ RANGED_CLUMP_THRESHOLD` (2) — a shield covers one hemisphere, so
  vs 2+ shooters pre-blocking re-arms `shieldHoldTicks` every tick (one is always drawing)
  and pins the bot. Vs a clump, skip the draw pre-block; rely on strafe-dodge + the
  projectile-impact layer. Removing this gate reintroduces the "stands still, shield up,
  never engages vs 5+ skeletons" turtle (§14).
- **Camera easing is opt-in per target.** Block-break/place and forced (raytrace-precise)
  aims pass `ease=false`; only combat aiming and non-forced pathing movement aims opt in.
  Easing also requires `humanizeCamera` on + `mode == CLIENT` + not elytra. Never ease a
  `blockInteract=true` target — `objectMouseOver` needs pixel-exact aim (§12).
- **`MOVE_BACK` is in `clearAuxKeys`.** The retreat backpedal forces it; if you add another
  forced-movement key combat manages, clear it there too or it leaks into the next tick.
- **`combatForceBow` is scoped to one hunt** — cleared in `cleanup()`.

---

## 11. Quick smoke test after any change

```bash
cd Java_project/baritone && ./gradlew.bat :neoforge:build
# expect: BUILD SUCCESSFUL, dist/baritone-unoptimized-neoforge-*.jar
cp dist/baritone-api-neoforge-*.jar ../arion/libs/   # refresh Arion's compile ref
```

Then in-game (install the **unoptimized** jar into `mods/`):
1. World loads with no mixin errors (check the log for
   `baritone$suppressAutoRelease` / any `Critical injection failure`).
2. `#hunt nearest` — bot acquires a hostile, approaches, swaps to sword, swings.
3. Vs a skeleton: bot raises shield as the skeleton draws / as the arrow approaches,
   faces the arrow; doesn't hold the shield up against a grounded/stuck arrow.
4. With a bow + arrows, `#hunt bow on` vs a distant mob: bot draws, leads a moving
   target, fires; arrows connect.
5. Bot pathing through dirt (autotool → shovel) then reaching melee: swaps back to
   sword before the first swing.
6. `#hunt stop` / `#cancel` — bot stops, restores prior hotbar slot, reach buff gone.
7. No shield equipped at all: combat still functions (no freeze) — the reflex no-ops.
8. **Melee rhythm (§6b):** vs a zombie you can't kite (cornered / tight space), the bot
   blocks the zombie's swing, then drops the shield and strikes during its cooldown —
   not a held block. Vs a clump, it cycles block→strike→re-block and, if pinned too long
   (~2s, no strike), retreats to reset and re-engages instead of holding the shield
   until it breaks. Kite-able 1v1s (open ground) should show NO shield use — just kite+strike.
9. **Humanized camera (§12):** with `humanizeCamera on`, the bot eases onto a combat
   target instead of one-tick snapping — small adjustments creep, wide angles flick at a
   capped rate. Without it (default), aim is unchanged (exact snap).
10. **Retreat (§13):** when hurt, the bot backpedals facing the threat (not an instant
    about-face) and only turns to flee when critical / mob-in-face / backed into a wall.
11. **Ranged clump (§14):** vs 5+ skeletons/pillagers the bot strafe-and-advances (diagonal
    close while dodging) and only raises the shield for an arrow actually homing in — it does
    NOT turtle on every draw. Vs a single archer it still pre-blocks the draw.

---

## 12. Humanized camera easing (Phase 1, approach C)

The pre-existing look path hard-sets the desired rotation every tick: `LookBehavior.onPlayerUpdate`
PRE calls `AimProcessor.peekRotation(target)`, and `calculateMouseMove` quantizes the **full**
`target − current` delta into a single tick — so every turn, however small, completes in one tick.
That is the "too quick turn like a real bot" and "small angle always flicks" problem. The external
`smoothcam` mod and Baritone's own `smoothLook` (a trailing average of raw targets) both average
*after* the snap, so they smear a snap rather than easing into it.

**Phase 1 adds an opt-in easing layer, separate from `smoothLook`:**

- A new `ILookBehavior.updateTarget(Rotation, boolean blockInteract, boolean ease)` overload. The
  2-arg form delegates with `ease=false` (exact, unchanged). `ease=true` means "this target is
  eligible for easing" — it does **not** force easing; the layer also requires `humanizeCamera` on,
  `target.mode == CLIENT` (client-visible), and not elytra.
- `LookBehavior.easeToward(current, desired)` computes a bounded per-tick step per axis:
  - gap < `cameraMinTurnRate` → **snap** the rest (arrival; no permanent fractional offset / wobble).
  - gap < `cameraFlickThreshold` → **track**: `step = delta * cameraSmallDeltaRate` (a fraction of
    the gap; small adjustments ease instead of snap).
  - else → **flick**: `step = sign(delta) * cameraMaxTurnRate` (capped fast turn; humans flick on
    wide angles, just not instantly). Pitch flicks at half the yaw rate (vertical snaps read robotic).
  - never overshoots; result is clamped (pitch), then handed to `peekRotation` so the eased step
    still goes through mouse-pixel quantization + jitter — it reads as mouse movement, spread over
    ticks instead of one.
- Easing state is carried by the player's own rotation (CLIENT mode doesn't revert in POST), so no
  persisted field is needed: `current` next tick = what we set this tick.
- POST skips `smoothLook` averaging on an eased tick (the `easedThisTick` flag) so the two layers
  don't fight.

**Who opts in (`ease=true`):**
- **Combat aiming** (`CombatProcess.aimAt`) — the "too quick turn onto the mob" fix. Melee attacks
  pass the entity by reference, so a briefly-lagging eased aim still connects; bow/ranged wait for
  the eased aim to converge before releasing (the strike gate reads the actual player rotation).
- **Non-forced pathing movement aims** (`Movement.update`, `!hasToForceRotations()`) — the turn
  toward the next block eases. **Forced (block break/place) aims stay `ease=false`** so
  `objectMouseOver` raytracing stays pixel-exact. Elytra, the fall-override aim, and all
  `blockInteract=true` block interactions never ease.

**Caveat (freeLook interaction):** with default `freeLook=true` + `antiCheatCompatibility=true`,
non-forced pathing aims resolve to **SERVER** mode (silent — movement uses `onPlayerRotationMove`,
the visible camera isn't Baritone-driven), so easing them is a visible no-op. Easing visibly applies
to pathing only when freeLook is off (or antiCheat off) so pathing resolves CLIENT. Combat aiming is
CLIENT regardless (`blockInteract=true`, `blockFreeLook=false` default), so the combat fix works
with defaults. Lookahead ("look toward the turn point before the bend") is **not yet implemented** —
deferred to a Phase 2 that walks `IPathExecutor`'s path; the `cameraLookahead*` settings were
intentionally **not** added yet to avoid dead config.

**Defaults off** — `humanizeCamera=false`. No behavior change unless toggled.

---

## 13. Retreat — disengage-first backpedal → flee

The old retreat turned and ran (`GoalRunAway`) the instant the HP/count trip fired — an instant
about-face that reads as a bot and drops the player's guard the moment they take a hit. The new
`doRetreat` is a two-tier disengage:

1. **Backpedal (default):** face the threat (`aim(flee)`, eased via §12 when `humanizeCamera` is on)
   and force `Input.MOVE_BACK` with no pathing goal (`REQUEST_PAUSE` holds pathing still while the
   key moves us backward). The shield reflex (which runs before the state switch) still blocks
   imminent hits during the backpedal — on a shield-hold tick the backpedal briefly pauses to
   plant-and-block, then resumes. Human-like: back off while watching the threat, don't instantly
   turn your back.
2. **Escalate to flee** only when one of:
   - **critical** — HP below `combatRetreatHealth` (can't keep trading hits),
   - **tooClose** — mob inside ~2 blocks (backpedal is slower than the mob's walk, so no gap opens),
   - **cornered** — a solid block is at the player's back (`retreatBlocked` samples ~1.5 blocks
     behind at feet + head height; backing into a wall just feeds free hits).

   On escalate, `moveGoal = GoalRunAway(12, flee.blockPosition())` — pathing drives the about-face
   (eased too, when pathing is client-visible). The shield reflex still only blocks imminent hits
   (it faces the nearest hostile while `target` is null), so the flee doesn't turtle.

`enterRetreat` no longer pre-queues a `GoalRunAway` on the triggering tick (it just sets state +
nulls target); `doRetreat` decides backpedal-vs-flee per tick from the next tick on. Re-engage
logic is unchanged: HP-recovery (HP retreat) or space-created (`retreatPinned` failsafe).
`MOVE_BACK` is now cleared by `clearAuxKeys` each tick so backpedal input never leaks.

**Runtime-untested** — verify the backpedal actually creates space (MOVE_BACK under REQUEST_PAUSE),
that `retreatBlocked` doesn't false-positive on slopes/stairs, and that the shield-hold pause
doesn't stutter the retreat into a standstill.

---

## 14. Ranged clumps — strafe-dodge instead of turtle (build #8)

**The bug:** vs a clump of ranged mobs (5+ skeletons/pillagers), the bot stood still with the
shield up and never engaged. Cause: `shieldDecision`'s ranged-draw layer (`rangedDrawHold`)
pre-blocks any mob drawing a bow/crossbow aimed at us. Against a single archer that's correct —
block near the end of its draw, then the projectile-impact layer handles the arrow's flight, with a
gap between shots. Against a **staggered clump** there is *always* at least one mob in its
pre-release draw window, so `shieldHoldTicks` is re-armed every tick and the reflex returns
`REQUEST_PAUSE` every tick. The state machine — where `strafe()` lives (inside `doRecover`) — never
runs. So: no strafe, no engage, turtle until the shield breaks. Pre-blocking is also fundamentally
single-target: a shield covers one hemisphere, so you can't block 2+ shooters at once anyway.

**The fix (matches "strafe to dodge and engage, block only when the arrow is homing"):**
- `countRangedAttackers(range)` counts hostile ranged mobs (via `MobTactic.forEntity`, so skeletons,
  **pillagers**, witches, blazes) within range.
- In `shieldDecision`: when `countRangedAttackers ≥ RANGED_CLUMP_THRESHOLD` (2), **skip the
  ranged-draw pre-block**. The **projectile-impact** layer (`ProjectileThreat`) is unchanged — it
  still raises the shield for an arrow whose simulated path enters our hitbox within
  `combatShieldLeadTicks`. That is precisely "block when the arrow is homing." With the draw pre-block
  gone, `shieldHoldTicks` is 0 most ticks → the reflex falls through to `lowerShield()` → the state
  machine runs. Strafing makes leading-aimed arrows whiff (the skeleton leads your current position;
  if you strafe, the lead is wrong), so the impact layer fires rarely — only when strafe fails or an
  arrow catches you mid-direction-flip. No sustained turtle.
- `doApproach`: vs a ranged clump, **strafe-and-advance** instead of a straight `GoalNear` line.
  `strafe()` faces the target and picks a tangential dodge direction (flipping when a shooter
  re-acquires us); we also drive `MOVE_FORWARD` so the bot closes diagonally while dodging, rather
  than walking straight into arrows. `moveGoal = null` (REQUEST_PAUSE) so pathing doesn't fight the
  key-movement. Pure pathing resumes at engage range or when shooters drop below the threshold.
- Single-archer fights (`countRangedAttackers < 2`) keep the draw pre-block — unchanged.

**Secondary fix:** `Pillager` was missing from `MobTactic.forEntity` → classified `GENERIC`
(`ranged=false`), so pillagers were never strafed and never hit the ranged logic. Added as a ranged
tactic (crossbow). (`Pillager` is `monster.illager.Pillager` in 26.1, not `monster.pillager`.)

**Caveats / runtime-untested:**
- Strafe-and-advance is key-based (no pathing), so in rough terrain (gaps, walls) it may bump where
  pathing would route around — open-ground clumps are the intended case.
- The impact-shield still pins for a few ticks when an arrow is genuinely homing; that's correct, but
  if strafe isn't dodging well (e.g. cornered) the bot may still eat shots. Tune
  `combatDodgeAngle` / the strafe flip cadence if arrows land too often.
- `RANGED_CLUMP_THRESHOLD = 2` is a constant (not a setting) — "2+ shooters = can't block them all."

