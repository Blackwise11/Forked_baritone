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

package baritone.api.behavior;

import baritone.api.Settings;
import baritone.api.behavior.look.IAimProcessor;
import baritone.api.utils.Rotation;

/**
 * @author Brady
 * @since 9/23/2018
 */
public interface ILookBehavior extends IBehavior {

    /**
     * Updates the current {@link ILookBehavior} target to target the specified rotations on the next tick. If any sort
     * of block interaction is required, {@code blockInteract} should be {@code true}. It is not guaranteed that the
     * rotations set by the caller will be the exact rotations expressed by the client (This is due to settings like
     * {@link Settings#randomLooking}). If the rotations produced by this behavior are required, then the
     * {@link #getAimProcessor() aim processor} should be used.
     *
     * <p>Equivalent to {@link #updateTarget(Rotation, boolean, boolean) updateTarget(rotation, blockInteract, false)}
     * — the rotation is applied exactly (subject only to the aim processor), with no easing.
     *
     * @param rotation      The target rotations
     * @param blockInteract Whether the target rotations are needed for a block interaction
     */
    void updateTarget(Rotation rotation, boolean blockInteract);

    /**
     * Updates the current {@link ILookBehavior} target, optionally requesting that the camera
     * <b>eases</b> toward the target rotation over several ticks instead of snapping to it in one.
     *
     * <p>Easing is a per-target opt-in: pass {@code ease = true} for look targets that should turn
     * humanly (e.g. combat aiming at a mob). Pass {@code false} — or use the two-arg overload — for
     * targets that must be pixel-exact this tick (block break/place, which rely on
     * {@code objectMouseOver} raytracing the exact aim). Easing only takes effect when
     * {@link Settings#humanizeCamera} is on and the target resolves to a client-visible rotation;
     * otherwise it is ignored and the behavior is identical to the two-arg call.
     *
     * @param rotation      The target rotations
     * @param blockInteract Whether the target rotations are needed for a block interaction
     * @param ease          Whether to humanize the turn toward this target (only when
     *                      {@link Settings#humanizeCamera} is on)
     */
    void updateTarget(Rotation rotation, boolean blockInteract, boolean ease);

    /**
     * The aim processor instance for this {@link ILookBehavior}, which is responsible for applying additional,
     * deterministic transformations to the target rotation set by {@link #updateTarget}.
     *
     * @return The aim processor
     * @see IAimProcessor#fork
     */
    IAimProcessor getAimProcessor();
}
