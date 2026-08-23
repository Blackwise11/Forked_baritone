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
import baritone.api.event.events.TickEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.input.Input;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Auto-eat + death/respawn bookkeeping. A temporary, high-priority process (like
 * {@link InventoryPauserProcess}) that only seizes control while it has eating work this tick,
 * otherwise defers to whatever else is running. Also registered as a {@link AbstractGameEventListener}
 * so it can record the death position and (optionally) auto-respawn independently of the process
 * lifecycle.
 *
 * <p>Auto-eat replaces Arion's raw {@code new Thread(...)} eat-hold with a tick counter: force the
 * right-click input down via {@link baritone.api.utils.input.IInputOverrideHandler}, count ticks,
 * and release when full or after a cap. Food is selected from the hotbar only (0..8), fixing the
 * latent Arion bug where {@code findFoodSlot} could return a slot past the hotbar.
 */
public final class SurvivalProcess extends BaritoneProcessHelper implements AbstractGameEventListener {

    /** Max ticks to hold an eat before giving up (safety cap; normal food is ~32 ticks). */
    private static final int MAX_EAT_TICKS = 60;
    /** Ticks to wait after death before sending the respawn packet (let the death screen settle). */
    private static final int RESPAWN_DELAY_TICKS = 10;

    private boolean eating = false;
    private int eatTicks = 0;
    private int priorSlot = -1;
    private boolean forceEat = false; // set by "#eat now"

    private BlockPos lastDeathPos = null;
    private long tickCount = 0;
    private long respawnAtTick = -1;

    public SurvivalProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public boolean isTemporary() {
        return true;
    }

    @Override
    public double priority() {
        // above InventoryPauser (5.1) so eating isn't deferred by inventory moves
        return 5.2;
    }

    @Override
    public boolean isActive() {
        return ctx.player() != null && ctx.world() != null && (eating || shouldEat());
    }

    // --------------------------------------------------------------- eating

    private boolean shouldEat() {
        if (ctx.player() == null) return false;
        boolean hungry = ctx.player().getFoodData().getFoodLevel() < Baritone.settings().autoEatBelowFood.value;
        if (forceEat) {
            return findFoodSlot() >= 0;
        }
        return Baritone.settings().autoEat.value && hungry && findFoodSlot() >= 0;
    }

    /** Trigger a one-shot eat on the next tick, regardless of hunger. */
    public void eatNow() {
        this.forceEat = true;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (eating) {
            return continueEating();
        }
        if (shouldEat()) {
            startEating();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        return new PathingCommand(null, PathingCommandType.DEFER);
    }

    private void startEating() {
        int foodSlot = findFoodSlot();
        if (foodSlot < 0) {
            forceEat = false;
            return;
        }
        priorSlot = ctx.player().getInventory().getSelectedSlot();
        ctx.player().getInventory().setSelectedSlot(foodSlot);
        // Kick off the use. processRightClick sends the use-item packet and calls startUsingItem,
        // setting useItemRemainingTicks. But that alone only lasts one tick: vanilla handleKeybinds
        // sees options.keyUse as released (Baritone's Input.CLICK_RIGHT force does NOT feed the use
        // key — only BlockPlaceHelper, which bails when not aimed at a block) and calls
        // stopUsingItem() the very next tick. So we ALSO hold the real use key down via setDown;
        // that keeps handleKeybinds sustaining the use until the food is consumed.
        ctx.playerController().processRightClick(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND);
        holdUseKey(true);
        eating = true;
        eatTicks = 0;
    }

    private PathingCommand continueEating() {
        Player player = ctx.player();
        if (player == null) {
            finishEating();
            return new PathingCommand(null, PathingCommandType.DEFER);
        }
        boolean full = player.getFoodData().getFoodLevel() >= 20;
        boolean stackGone = !isCurrentItemFood();
        if (full || stackGone) {
            finishEating();
            return new PathingCommand(null, PathingCommandType.DEFER);
        }
        // Re-assert the use key every tick (it can be reset by the game's input polling) and restart
        // the use if it dropped (e.g. we took damage mid-bite). Counting eatTicks only while actually
        // using means the safety cap measures real eating, not stalled ticks.
        holdUseKey(true);
        if (!player.isUsingItem()) {
            ctx.playerController().processRightClick(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND);
        }
        if (player.isUsingItem()) {
            eatTicks++;
        }
        if (eatTicks >= MAX_EAT_TICKS) {
            finishEating();
            return new PathingCommand(null, PathingCommandType.DEFER);
        }
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private void finishEating() {
        // Clear the force-hold, then release via releaseUsingItem so the server sees the use end
        // (sends RELEASE_USE_ITEM). For food this completes the eat server-side; stopUsingItem()
        // alone would desync. No-op if we weren't actually using.
        holdUseKey(false);
        if (ctx.player() != null && ctx.player().isUsingItem()) {
            try {
                ctx.playerController().releaseUsingItem(ctx.player());
            } catch (Throwable ignored) {
                // never crash eating over a release packet
            }
        }
        if (priorSlot >= 0 && ctx.player() != null) {
            ctx.player().getInventory().setSelectedSlot(priorSlot);
        }
        priorSlot = -1;
        eating = false;
        forceEat = false;
        eatTicks = 0;
    }

    /**
     * Hold/release the item-use key for a sustained eat. Sets Baritone's force-use flag, which a mixin
     * ({@code MixinMinecraft#baritone$suppressAutoRelease}) checks to suppress vanilla's per-tick
     * auto-{@code releaseUsingItem} — the call that would otherwise abort the eat every tick because
     * the real use key isn't held. Stopped via {@code releaseUsingItem} in {@link #finishEating}.
     */
    private void holdUseKey(boolean down) {
        baritone.getInputOverrideHandler().setForceUsingItem(down);
    }

    private boolean isCurrentItemFood() {
        if (ctx.player() == null) return false;
        ItemStack stack = ctx.player().getMainHandItem();
        FoodProperties food = stack.get(DataComponents.FOOD);
        return food != null && food.nutrition() > 0;
    }

    /**
     * First hotbar slot (0..8) holding edible food, or -1. Hotbar-only: the offhand and the main
     * inventory grid past slot 8 can't be selected with {@code setSelectedSlot}, so returning one
     * would be a silent no-op.
     */
    private int findFoodSlot() {
        if (ctx.player() == null) return -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = ctx.player().getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            FoodProperties food = stack.get(DataComponents.FOOD);
            if (food != null && food.nutrition() > 0) {
                return i;
            }
        }
        return -1;
    }

    // ----------------------------------------------------- death / respawn

    @Override
    public void onTick(TickEvent event) {
        tickCount++;
        if (respawnAtTick > 0 && tickCount >= respawnAtTick) {
            respawnAtTick = -1;
            if (Baritone.settings().autoResumeAfterDeath.value
                    && ctx.minecraft().getConnection() != null) {
                // best-effort: send the perform-respawn packet directly
                ctx.minecraft().getConnection().send(
                        new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
                logDirect("Sent respawn.");
            }
        }
    }

    @Override
    public void onPlayerDeath() {
        if (ctx.player() != null) {
            lastDeathPos = ctx.player().blockPosition().immutable();
            logDirect("Died at " + lastDeathPos + ".");
        }
        if (Baritone.settings().autoResumeAfterDeath.value) {
            respawnAtTick = tickCount + RESPAWN_DELAY_TICKS;
        }
    }

    /** The last recorded death position, or null. Exposed for consumers (e.g. a "return to death" command). */
    public BlockPos lastDeathPos() {
        return lastDeathPos;
    }

    public void clearDeathPos() {
        lastDeathPos = null;
    }

    // ----------------------------------------------------------------- misc

    @Override
    public void onLostControl() {
        if (eating) {
            finishEating();
        }
    }

    @Override
    public String displayName0() {
        return eating ? "eating" : "auto-eat";
    }
}
