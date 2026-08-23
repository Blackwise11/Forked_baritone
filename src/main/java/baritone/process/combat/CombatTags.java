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

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.Slime;

/**
 * Tag-based mob classification shared by {@code CombatProcess}, {@code MobTactic}, and
 * {@code HuntFilter} — keeps "#hunt nearest" and auto-defend in agreement on what counts
 * as hostile.
 *
 * <p>Three layers, fastest first:
 * <ol>
 *   <li>Vanilla class check: {@code Monster || Slime || Phantom} — covers the vast
 *       majority with plain instanceof speed and zero behavior change for vanilla mobs.</li>
 *   <li>{@code baritone:hostiles} / {@code baritone:ranged} — custom entity-type tags
 *       shipped in this jar ({@code data/baritone/tags/entity_types/}). Entity-type tags
 *       are part of the synced registry, so these work client-side in multiplayer AND
 *       are extendable by any server datapack or mod: a modpack adding its own mobs to
 *       {@code baritone:hostiles} gets auto-defend coverage with zero code changes.</li>
 *   <li>{@code c:hostiles} — the common-tag convention, if the environment provides it
 *       (absent tags simply match nothing, so this degrades safely).</li>
 * </ol>
 *
 * <p>Why the class layer stays: the custom tag only seeds vanilla hostiles the class check
 * misses (shulker, hoglin, ghast, zoglin — see the JSON); Monster subclasses are already
 * caught faster by instanceof.
 */
public final class CombatTags {

    /** Vanilla-and-modded hostile entity types beyond what {@code Monster} covers. */
    public static final TagKey<EntityType<?>> HOSTILES = TagKey.create(
            Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath("baritone", "hostiles"));

    /** Entities with a ranged attack worth shielding/strafing against. */
    public static final TagKey<EntityType<?>> RANGED = TagKey.create(
            Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath("baritone", "ranged"));

    /** Common-tag convention equivalent of {@link #HOSTILES}, when present. */
    private static final TagKey<EntityType<?>> C_HOSTILES = TagKey.create(
            Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath("c", "hostiles"));

    private CombatTags() {}

    /**
     * Is this entity hostile to the player? See the class javadoc for the layering.
     * NOT {@code Mob.getTarget()}-based — {@code Mob.target} is not synced to the client.
     */
    public static boolean isHostile(Entity e) {
        return e instanceof Monster
                || e instanceof Slime
                || e instanceof Phantom
                || e.getType().is(HOSTILES)
                || e.getType().is(C_HOSTILES);
    }

    /** Does this entity have a ranged attack (skeleton, pillager, witch, blaze, ghast, modded...)? */
    public static boolean isRanged(Entity e) {
        return e.getType().is(RANGED);
    }
}
