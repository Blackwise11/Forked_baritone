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

package baritone.launch.mixins;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.utils.InputOverrideHandler;
import baritone.utils.accessor.IPlayerControllerMP;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiPlayerGameMode.class)
public abstract class MixinPlayerController implements IPlayerControllerMP {

    @Accessor("isDestroying")
    @Override
    public abstract void setIsHittingBlock(boolean isHittingBlock);

    @Accessor("isDestroying")
    @Override
    public abstract boolean isHittingBlock();

    @Accessor("destroyBlockPos")
    @Override
    public abstract BlockPos getCurrentBlock();

    @Invoker("ensureHasSentCarriedItem")
    @Override
    public abstract void callSyncCurrentPlayItem();

    @Accessor("destroyDelay")
    @Override
    public abstract void setDestroyDelay(int destroyDelay);

    /**
     * Suppress vanilla's automatic item-use release while Baritone is force-holding the use key.
     * <p>
     * Vanilla calls {@code releaseUsingItem} every tick from {@code Minecraft.startUseItem} when the
     * player is using an item but the real use key isn't down. That cancels any sustained use — a bow
     * draw never charges, a shield drops instantly, an eat aborts mid-bite — because Baritone can't
     * keep the real {@code KeyMapping} down (its polling overwrites {@code setDown(true)} each tick).
     * <p>
     * While {@link InputOverrideHandler#isForceUsingItem()} is true, cancel the release here so the
     * use continues (it sustains via {@code useItemRemainingTicks}). Baritone's OWN intentional
     * release — to fire a bow or drop a shield — clears the flag <em>first</em> (see
     * {@code CombatProcess.releaseUse} / {@code SurvivalProcess.finishEating}), so when it calls
     * {@code releaseUsingItem} the flag is already false and this inject lets it through. This makes
     * the cancel scope exactly "release while Baritone is mid-forced-use," which is what we want.
     * <p>
     * Injecting at the HEAD of a stable public method (rather than redirecting a specific call site
     * inside the NeoForge-patched {@code startUseItem}) is robust to patches — the previous call-site
     * {@code @Redirect} matched zero targets under NeoForge and crashed on load.
     */
    @Inject(
            method = "releaseUsingItem",
            at = @At("HEAD"),
            cancellable = true
    )
    private void baritone$suppressAutoRelease(Player player, CallbackInfo ci) {
        try {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone != null
                    && baritone.getPlayerContext().player() == player
                    && ((InputOverrideHandler) baritone.getInputOverrideHandler()).isForceUsingItem()) {
                ci.cancel(); // Baritone is holding the use key — don't auto-release
            }
        } catch (Throwable ignored) {
            // a mixin must never throw — fall through to vanilla behavior
        }
    }
}
