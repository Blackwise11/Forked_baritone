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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * Which mobs to hunt. Built from a hunt request (a type name, "nearest", or "all").
 * Validated against {@link BuiltInRegistries#ENTITY_TYPE} so a typo fails cleanly.
 */
public sealed interface HuntFilter {

    /** The predicate to match candidate entities (already alive + living). */
    Predicate<Entity> predicate();

    /** Human-readable description for chat. */
    String describe();

    // ------------------------------------------------------------ variants

    /** A specific entity type: "zombie", "creeper", "skeleton", "cow". */
    record ByType(EntityType<?> type) implements HuntFilter {
        @Override public Predicate<Entity> predicate() {
            return e -> e.getType() == type && e instanceof LivingEntity;
        }
        @Override public String describe() {
            return BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
        }
    }

    /** Nearest hostile within range. Same hostility definition as auto-defend ({@link CombatTags}). */
    record Nearest() implements HuntFilter {
        @Override public Predicate<Entity> predicate() {
            return CombatTags::isHostile;
        }
        @Override public String describe() { return "nearest hostile"; }
    }

    /** Every hostile in range (clear an area). Same hostility definition as auto-defend ({@link CombatTags}). */
    record All() implements HuntFilter {
        @Override public Predicate<Entity> predicate() {
            return CombatTags::isHostile;
        }
        @Override public String describe() { return "all hostiles"; }
    }

    // ------------------------------------------------------------ parsing

    /**
     * Parse a hunt argument ("zombie", "nearest", "all") into a filter.
     * Returns empty on an unknown entity type.
     */
    static Optional<HuntFilter> parse(String arg) {
        String s = arg.trim().toLowerCase();
        if (s.isEmpty() || s.equals("nearest")) return Optional.of(new Nearest());
        if (s.equals("all")) return Optional.of(new All());
        // accept "zombie" or "minecraft:zombie"
        ResourceLocation id = s.contains(":")
                ? ResourceLocation.tryParse(s)
                : ResourceLocation.fromNamespaceAndPath("minecraft", s);
        if (id == null) return Optional.empty();
        return BuiltInRegistries.ENTITY_TYPE.getOptional(id)
                .map(type -> (HuntFilter) new ByType(type));
    }
}
