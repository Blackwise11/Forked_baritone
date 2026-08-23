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

package baritone.utils;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.event.events.TickEvent;
import baritone.api.utils.Helper;
import baritone.api.utils.IInputOverrideHandler;
import baritone.api.utils.input.Input;
import baritone.behavior.Behavior;
import baritone.process.MineProcess;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.HashMap;
import java.util.Map;

/**
 * An interface with the game's control system allowing the ability to
 * force down certain controls, having the same effect as if we were actually
 * physically forcing down the assigned key.
 *
 * @author Brady
 * @since 7/31/2018
 */
public final class InputOverrideHandler extends Behavior implements IInputOverrideHandler, Helper {

    /**
     * Maps inputs to whether or not we are forcing their state down.
     */
    private final Map<Input, Boolean> inputForceStateMap = new HashMap<>();

    /**
     * True while Baritone is force-holding the use key (to sustain a bow draw, a shield block, or an
     * eat). A mixin suppresses vanilla's per-tick auto-release while this is set; see
     * {@link IInputOverrideHandler#isForceUsingItem()}.
     */
    private boolean forceUsingItem = false;

    private final BlockBreakHelper blockBreakHelper;
    private final BlockPlaceHelper blockPlaceHelper;

    /**
     * FTB Ultimine's keybind, discovered at runtime (no compile-time dependency). Held down
     * while Baritone is breaking one of the blocks a {@code #mine} command asked for, so
     * Ultimine veins the whole deposit, like a vein-miner — but NOT while Baritone merely digs
     * through unrelated blocks to reach the target. Null until found — see
     * {@link #tickUltimineKey(boolean)}.
     *
     * <p><b>Known risk:</b> if Ultimine polls the physical GLFW key instead of
     * {@link KeyMapping#isDown()}, this does nothing (harmless). Needs in-game verification
     * with the mod installed; the design is dependency-free, so failure mode is a no-op.
     */
    private KeyMapping ultimineKey;

    /** Ticks until the next Ultimine keybind scan when the previous scan failed. */
    private int ultimineRescanCountdown = 0;

    /** Whether WE currently hold the Ultimine key down (so we only release what we pressed). */
    private boolean ultimineHeld = false;

    /** Logged "not installed" once per session, not every scan. */
    private boolean ultimineMissingLogged = false;

    public InputOverrideHandler(Baritone baritone) {
        super(baritone);
        this.blockBreakHelper = new BlockBreakHelper(baritone.getPlayerContext());
        this.blockPlaceHelper = new BlockPlaceHelper(baritone.getPlayerContext());
    }

    /**
     * Returns whether or not we are forcing down the specified {@link Input}.
     *
     * @param input The input
     * @return Whether or not it is being forced down
     */
    @Override
    public final boolean isInputForcedDown(Input input) {
        return input == null ? false : this.inputForceStateMap.getOrDefault(input, false);
    }

    /**
     * Sets whether or not the specified {@link Input} is being forced down.
     *
     * @param input  The {@link Input}
     * @param forced Whether or not the state is being forced
     */
    @Override
    public final void setInputForceState(Input input, boolean forced) {
        this.inputForceStateMap.put(input, forced);
    }

    /**
     * Clears the override state for all keys
     */
    @Override
    public final void clearAllKeys() {
        this.inputForceStateMap.clear();
        this.forceUsingItem = false;
        releaseUltimineKey(); // never leave a mod keybind stuck down through a cancel
    }

    @Override
    public final boolean isForceUsingItem() {
        return this.forceUsingItem;
    }

    @Override
    public final void setForceUsingItem(boolean force) {
        this.forceUsingItem = force;
    }

    @Override
    public final void onTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.OUT) {
            return;
        }
        if (isInputForcedDown(Input.CLICK_LEFT)) {
            setInputForceState(Input.CLICK_RIGHT, false);
        }
        boolean breaking = isInputForcedDown(Input.CLICK_LEFT);
        blockBreakHelper.tick(breaking);
        blockPlaceHelper.tick(isInputForcedDown(Input.CLICK_RIGHT));
        tickUltimineKey(breaking);

        if (inControl()) {
            if (ctx.player().input.getClass() != PlayerMovementInput.class) {
                ctx.player().input = new PlayerMovementInput(this);
            }
        } else {
            if (ctx.player().input.getClass() == PlayerMovementInput.class) { // allow other movement inputs that aren't this one, e.g. for a freecam
                ctx.player().input = new KeyboardInput(ctx.minecraft().options);
            }
        }
        // only set it if it was previously incorrect
        // gotta do it this way, or else it constantly thinks you're beginning a double tap W sprint lol
    }

    private boolean inControl() {
        for (Input input : new Input[]{Input.MOVE_FORWARD, Input.MOVE_BACK, Input.MOVE_LEFT, Input.MOVE_RIGHT, Input.SNEAK, Input.JUMP}) {
            if (isInputForcedDown(input)) {
                return true;
            }
        }
        // if we are not primary (a bot) we should set the movementinput even when idle (not pathing)
        return baritone.getPathingBehavior().isPathing() || baritone != BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    // ------------------------------------------------------------ FTB Ultimine

    /**
     * Hold FTB Ultimine's keybind while Baritone is breaking one of the blocks a {@code #mine}
     * command asked for, release otherwise. Mere block breaking is not enough — Baritone digs
     * through plenty of unrelated blocks while traveling to the target, and veining those
     * would chew up the terrain (and the pickaxe) for no reason. See {@link #isBreakingMineTarget()}.
     *
     * <p>Called every tick from {@link #onTick} — note that {@code clearAllKeys()} (which several
     * processes call every tick) also releases the key; this re-asserts it in the same tick
     * while breaking continues, so at worst a cancel-then-continue transition costs one tick
     * of the overlay.
     */
    private void tickUltimineKey(boolean breaking) {
        if (!Baritone.settings().useFtbUltimine.value || ctx.minecraft().options == null) {
            releaseUltimineKey();
            return;
        }
        if (ultimineKey == null) {
            // Rescan periodically (every 5s): covers the mod loading after us, world joins
            // recreating options, and keybind re-registration. A scan is a small array walk.
            if (ultimineRescanCountdown-- > 0) {
                return;
            }
            ultimineRescanCountdown = 100;
            ultimineKey = findUltimineKey();
            if (ultimineKey == null) {
                if (!ultimineMissingLogged) {
                    logDebug("FTB Ultimine keybind not found — useFtbUltimine will stay inactive until the mod is present.");
                    ultimineMissingLogged = true;
                }
                return;
            }
        }
        if (breaking && isBreakingMineTarget()) {
            ultimineKey.setDown(true);
            ultimineHeld = true;
        } else {
            releaseUltimineKey();
        }
    }

    /**
     * Whether the block Baritone is currently breaking is an actual {@code #mine} target —
     * the same ray-traced block {@link BlockBreakHelper} is hitting, checked against the
     * MineProcess filter. Returns false outside of a mine command (pathing, building, combat,
     * tunneling, ...) so those never trigger Ultimine.
     */
    private boolean isBreakingMineTarget() {
        HitResult trace = ctx.objectMouseOver();
        if (trace == null || trace.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        MineProcess mine = baritone.getMineProcess();
        if (!mine.isActive()) {
            return false;
        }
        return mine.isMineTarget(((BlockHitResult) trace).getBlockPos());
    }

    /**
     * Scan the game's keybinds for FTB Ultimine's. Matched on the binding's translation key
     * ({@code key.ftbultimine}); falls back to a category match ({@code key.categories.ftbultimine})
     * in case the binding key is renamed.
     */
    private KeyMapping findUltimineKey() {
        for (KeyMapping mapping : ctx.minecraft().options.keyMappings) {
            if (mapping == null) {
                continue;
            }
            String name = keyName(mapping);
            if ("key.ftbultimine".equals(name)) {
                return mapping;
            }
        }
        return null;
    }

    private static String keyName(KeyMapping mapping) {
        return mapping.getName();
    }

    /** Release the Ultimine key if we're the one holding it. */
    private void releaseUltimineKey() {
        if (ultimineHeld && ultimineKey != null) {
            ultimineKey.setDown(false);
        }
        ultimineHeld = false;
    }

    public BlockBreakHelper getBlockBreakHelper() {
        return blockBreakHelper;
    }
}
