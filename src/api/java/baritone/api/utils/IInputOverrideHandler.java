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

package baritone.api.utils;

import baritone.api.behavior.IBehavior;
import baritone.api.utils.input.Input;

/**
 * @author Brady
 * @since 11/12/2018
 */
public interface IInputOverrideHandler extends IBehavior {

    boolean isInputForcedDown(Input input);

    void setInputForceState(Input input, boolean forced);

    void clearAllKeys();

    /**
     * Whether Baritone is force-holding the item-use key (right-click) this tick. While true, a mixin
     * suppresses vanilla's automatic {@code releaseUsingItem} call in {@code Minecraft.startUseItem}
     * (which fires every tick the real use key is up and would otherwise cancel a bow draw, drop a
     * shield, or abort an eat mid-bite). Baritone releases explicitly via
     * {@code IPlayerController.releaseUsingItem} when it wants to stop.
     */
    boolean isForceUsingItem();

    void setForceUsingItem(boolean force);
}
