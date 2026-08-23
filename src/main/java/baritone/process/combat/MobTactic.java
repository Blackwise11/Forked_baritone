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

import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.entity.monster.Spider;

/**
 * How to fight one kind of mob. The "skill" of the combat module lives in these
 * per-mob numbers and flags.
 *
 * @param engageRange     how close we must be to hit (≈ our reach)
 * @param dangerRange     inside this we take countermeasures (retreat/strafe)
 * @param retreatOnWindup back off when the mob starts a fuse/wind-up (creeper)
 * @param strafeSpeed     how fast to strafe vs ranged attackers
 * @param ranged          true for mobs with a ranged attack (skeleton/blaze/witch)
 */
public record MobTactic(double engageRange, double dangerRange, boolean retreatOnWindup,
                        double strafeSpeed, boolean ranged) {

    /** Generic melee fallback for any mob without a hand-tuned entry. */
    public static final MobTactic GENERIC = new MobTactic(4.5, 2.0, false, 0.6, false);

    /**
     * Per-mob tactics. Dispatch is tag-first where a vanilla tag covers the family
     * ({@link EntityTypeTags#SKELETONS} — all skeleton variants + modded skeletons), with a
     * {@link CombatTags#RANGED} catch-all so modded ranged mobs (added to the tag by a
     * datapack or their mod) get strafe/shield treatment for free. Numeric tactics can't be
     * tagged — the class checks here stay for supplying hand-tuned numbers, not classification.
     *
     * <p>Note {@code EntityTypeTags.RAIDERS} deliberately isn't used for "ranged":
     * Vindicator is a melee raider, so raiders→ranged would regress it.
     */
    public static MobTactic forEntity(Entity e) {
        if (e instanceof Creeper)                       return new MobTactic(4.5, 7.0, true, 0.6, false);
        if (e.getType().is(EntityTypeTags.SKELETONS))   return new MobTactic(4.5, 8.0, false, 1.0, true);
        if (e instanceof Pillager)                      return new MobTactic(4.5, 8.0, false, 1.0, true);
        if (e instanceof Spider)                        return new MobTactic(4.5, 2.5, false, 0.7, false);
        if (e instanceof EnderMan)                      return new MobTactic(4.0, 2.0, false, 0.7, false);
        if (e instanceof Witch)                         return new MobTactic(4.5, 8.0, false, 1.0, true);
        if (e instanceof Blaze)                         return new MobTactic(4.5, 8.0, false, 1.0, true);
        if (e instanceof Slime)                         return new MobTactic(4.5, 3.0, false, 0.6, false);
        if (CombatTags.isRanged(e))                     return new MobTactic(4.5, 8.0, false, 1.0, true);
        // zombies, husks, drowned, piglins, and everything else → generic melee
        return GENERIC;
    }

    /**
     * Distance to hold during a melee hit-and-run: just inside our reach (so we can still strike)
     * but outside the mob's vanilla reach (~3.0), so it whiffs while our swing recovers.
     * Roughly {@code engageRange - 1.0} ≈ 3.5 for a buffed 4.5 reach.
     */
    public double kiteRange() {
        return Math.max(2.0, engageRange - 1.0);
    }
}
