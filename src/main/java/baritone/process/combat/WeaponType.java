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

package baritone.process.combat;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * How a held weapon wants to be used. Drives which strike routine the combat process runs.
 *
 * <ul>
 *   <li>{@link #MELEE} — swords/axes/etc. Swing on cooldown at melee reach. The default.</li>
 *   <li>{@link #BOW} — a bow. Drawn and released to fire arrows from range.</li>
 *   <li>{@link #VELOCITY_SCALED} — mace or a (modded) spear. Damage scales with the player's
 *       velocity, so the strike is timed to coincide with a fall (mace smash) or sprint momentum
 *       (spear) rather than swung on a fixed cooldown.</li>
 * </ul>
 *
 * <p>"Spear" is not a vanilla item, so it is matched by item id containing {@code spear}
 * (case-insensitive), which covers the common modded spears. The mace is matched by
 * {@link Items#MACE}.
 */
public enum WeaponType {
    MELEE,
    BOW,
    VELOCITY_SCALED;

    /** Classify the weapon in a hotbar slot. Empty stacks are {@link #MELEE} (no weapon). */
    public static WeaponType forStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return MELEE;
        }
        if (stack.getItem() instanceof BowItem) {
            return BOW;
        }
        if (stack.is(Items.MACE)) {
            return VELOCITY_SCALED;
        }
        // modded spears: match by registered item id path (e.g. "minecraft:trident" is excluded by
        // the "not trident" requirement — trident's id is "trident", which does not contain "spear")
        try {
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
            if (id != null && id.contains("spear")) {
                return VELOCITY_SCALED;
            }
        } catch (Throwable ignored) {
            // registry lookup should never fail for a real item, but never crash combat over it
        }
        return MELEE;
    }
}
