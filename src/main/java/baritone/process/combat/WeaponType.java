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
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;

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
 * <p>Classification is <b>tag-first</b> so modded weapons work: both {@code forge:} (1.20.1 Forge)
 * and {@code c:} convention tags are checked first, with instance/id checks as fallbacks.
 */
public enum WeaponType {
    MELEE,
    BOW,
    VELOCITY_SCALED;

    /** Convention tags for ranged weapons (bow, crossbow, modded ranged). */
    private static final TagKey<Item> C_RANGED_WEAPONS = TagKey.create(
            Registries.ITEM, new ResourceLocation("c", "tools/ranged_weapon"));
    private static final TagKey<Item> FORGE_BOWS = TagKey.create(
            Registries.ITEM, new ResourceLocation("forge", "tools/bows"));
    private static final TagKey<Item> FORGE_CROSSBOWS = TagKey.create(
            Registries.ITEM, new ResourceLocation("forge", "tools/crossbows"));

    /** Convention tags for spears (vanilla trident + modded spears). */
    private static final TagKey<Item> C_SPEARS = TagKey.create(
            Registries.ITEM, new ResourceLocation("c", "tools/spear"));
    private static final TagKey<Item> FORGE_TRIDENTS = TagKey.create(
            Registries.ITEM, new ResourceLocation("forge", "tools/tridents"));

    /** Convention tags for maces. */
    private static final TagKey<Item> C_MACES = TagKey.create(
            Registries.ITEM, new ResourceLocation("c", "tools/mace"));

    /** True if the stack is a mace (by tag or item id). */
    public static boolean isMace(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (stack.is(C_MACES)) {
            return true;
        }
        try {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id != null) {
                String path = id.getPath();
                return path.equals("mace") || path.endsWith("_mace");
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** Classify the weapon in a hotbar slot. Empty stacks are {@link #MELEE} (no weapon). */
    public static WeaponType forStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return MELEE;
        }
        // mace first: fall-momentum smash
        if (isMace(stack)) {
            return VELOCITY_SCALED;
        }
        // spears next: trident + modded spears — momentum-scaled strikes
        if (stack.is(C_SPEARS) || stack.is(FORGE_TRIDENTS) || stack.getItem() instanceof TridentItem) {
            return VELOCITY_SCALED;
        }
        // ranged: vanilla bow + crossbow and any modded ranged weapon that tags itself
        if (stack.is(C_RANGED_WEAPONS) || stack.is(FORGE_BOWS) || stack.is(FORGE_CROSSBOWS)) {
            return BOW;
        }
        // fallbacks for untagged mods: vanilla class check, then item id heuristics
        if (stack.getItem() instanceof BowItem || stack.getItem() instanceof CrossbowItem) {
            return BOW;
        }
        try {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id != null) {
                String path = id.getPath();
                if (path.endsWith("_bow") || path.equals("bow") || path.endsWith("_crossbow")) {
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
