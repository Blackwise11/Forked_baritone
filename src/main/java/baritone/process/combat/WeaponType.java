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
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * How a held weapon wants to be used. Drives which strike routine the combat process runs.
 *
 * <ul>
 *   <li>{@link #MELEE} — swords/axes/etc. Swing on cooldown at melee reach. The default.</li>
 *   <li>{@link #BOW} — a bow, crossbow, or modded ranged weapon. Drawn and released to fire
 *       projectiles from range.</li>
 *   <li>{@link #VELOCITY_SCALED} — mace or a (modded) spear. Damage scales with the player's
 *       velocity, so the strike is timed to coincide with a fall (mace smash) or sprint momentum
 *       (spear) rather than swung on a fixed cooldown.</li>
 * </ul>
 *
 * <p>Classification is <b>tag-first</b> so modded weapons work: the {@code c:} convention tags
 * NeoForge ships ({@code c:tools/ranged_weapon}, {@code c:tools/spear}, {@code c:tools/mace})
 * are the primary signal — well-behaved mods tag their weapons into them. Instance/id checks
 * remain only as fallbacks for mods that don't tag. Note the vanilla trident is in both
 * {@code c:tools/melee_weapon} and {@code c:tools/ranged_weapon}, but the spear check runs
 * first and trident IS in {@code c:tools/spear}, so it classifies VELOCITY_SCALED — matching
 * its momentum-scaled riptide-style playstyle.
 */
public enum WeaponType {
    MELEE,
    BOW,
    VELOCITY_SCALED;

    /** NeoForge convention tag for ranged weapons (bow, crossbow, trident, modded ranged). */
    private static final TagKey<Item> RANGED_WEAPONS = TagKey.create(
            Registries.ITEM, ResourceLocation.fromNamespaceAndPath("c", "tools/ranged_weapon"));

    /** NeoForge convention tag for spears (vanilla trident + modded spears). */
    private static final TagKey<Item> SPEARS = TagKey.create(
            Registries.ITEM, ResourceLocation.fromNamespaceAndPath("c", "tools/spear"));

    /** NeoForge convention tag for the mace. */
    private static final TagKey<Item> MACES = TagKey.create(
            Registries.ITEM, ResourceLocation.fromNamespaceAndPath("c", "tools/mace"));

    /** Classify the weapon in a hotbar slot. Empty stacks are {@link #MELEE} (no weapon). */
    public static WeaponType forStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return MELEE;
        }
        // mace first: fall-momentum smash
        if (stack.is(MACES)) {
            return VELOCITY_SCALED;
        }
        // spears next: trident + modded spears — momentum-scaled strikes
        if (stack.is(SPEARS)) {
            return VELOCITY_SCALED;
        }
        // ranged: vanilla bow + crossbow and any modded ranged weapon that tags itself
        if (stack.is(RANGED_WEAPONS)) {
            return BOW;
        }
        // fallbacks for untagged mods: vanilla class check, then item id heuristics
        if (stack.getItem() instanceof BowItem) {
            return BOW;
        }
        try {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id != null) {
                String path = id.getPath();
                if (path.endsWith("_bow") || path.equals("bow")) {
                    return BOW;
                }
                if (path.contains("spear")) {
                    return VELOCITY_SCALED;
                }
            }
        } catch (Throwable ignored) {
            // registry lookup should never fail for a real item, but never crash combat over it
        }
        return MELEE;
    }
}
