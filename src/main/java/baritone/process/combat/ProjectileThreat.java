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

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Detects inbound projectiles (arrows, fireballs, thrown things) on a collision course with the
 * player, so the combat process can raise a shield or dodge.
 *
 * <p>Uses a <b>step simulation</b> rather than a straight-line closest-approach solve: for each
 * projectile, advance it tick-by-tick (applying per-tick drag and gravity, matching how Minecraft
 * moves arrows) and test intersection against the player's (inflated) bounding box. This accounts
 * for arrow arc and the player's real hitbox, so an arrow that will actually hit triggers a block —
 * the straight-line model missed arcing arrows and arrows aimed at the head/legs rather than center.
 *
 * <p>Projectiles owned by the player (the bot's own arrows) are ignored.
 */
public final class ProjectileThreat {

    /** The projectile that will hit. */
    public final Entity projectile;
    /** Ticks until impact (1..lookahead). */
    public final int ticksToImpact;
    /** Closest approach distance (blocks); ~0 on a predicted hit. */
    public final double closestApproach;

    private ProjectileThreat(Entity projectile, int ticksToImpact, double closestApproach) {
        this.projectile = projectile;
        this.ticksToImpact = ticksToImpact;
        this.closestApproach = closestApproach;
    }

    /**
     * Find the most imminent projectile whose simulated path enters {@code playerBox} within the
     * next {@code lookahead} ticks, or {@code null} if none.
     *
     * @param entities     the world entity stream (only projectiles are considered)
     * @param playerBox    the player's bounding box, already inflated to be generous about near-hits
     * @param selfId       the player's UUID — projectiles owned by the player are ignored
     * @param lookahead    max ticks to simulate forward
     */
    public static ProjectileThreat imminent(Iterable<Entity> entities, AABB playerBox,
                                            java.util.UUID selfId, int lookahead) {
        ProjectileThreat best = null;
        for (Entity e : entities) {
            if (!(e instanceof Projectile)) continue;
            Entity owner;
            try {
                owner = ((Projectile) e).getOwner();
            } catch (Throwable ignored) {
                owner = null;
            }
            if (owner != null && selfId != null && selfId.equals(owner.getUUID())) continue;

            // Only treat projectiles that are actually IN FLIGHT as threats. A landed/stuck arrow
            // (or a dropped thrown item resting on the ground) is still a Projectile entity sitting
            // in the world; with near-zero velocity it never leaves the player's inflated box, so the
            // step-sim "predicts" a permanent tick-1 impact and the shield raises forever ("blocks
            // arrows on the floor"). A flying arrow moves ~3 b/tick; a resting one ~0. This also
            // skips a projectile already touching us that has stopped (e.g. a slow fireball that
            // fizzled) — those are no longer a projectile threat.
            Vec3 v = e.getDeltaMovement();
            if (v.lengthSqr() < 0.04) continue; // < ~0.2 b/tick — not moving with any intent

            Vec3 p = e.position();
            // A projectile already close to the player that is no longer closing in has already
            // arrived — it was blocked or whiffed and is now tumbling/falling to the ground.
            // Re-simulating it each tick keeps "predicting" a tick-1 impact (it's still inside the
            // inflated box) and re-arming the shield hold, so the block stays up until the arrow
            // finally stops moving ("keeps blocking the arrow until it lands on the floor"). Only a
            // projectile still closing on the player is a live threat; once it's receding or merely
            // tangential near us, drop it so the shield comes down promptly after a block.
            if (distToBox(p, playerBox) < 3.0) {
                double cx = (playerBox.minX + playerBox.maxX) * 0.5;
                double cy = (playerBox.minY + playerBox.maxY) * 0.5;
                double cz = (playerBox.minZ + playerBox.maxZ) * 0.5;
                Vec3 toPlayer = new Vec3(cx - p.x, cy - p.y, cz - p.z);
                if (v.dot(toPlayer) <= 0.0) continue; // receding / tangential → post-impact
            }
            double closest = Double.POSITIVE_INFINITY;
            for (int t = 1; t <= lookahead; t++) {
                // advance one tick: arrows apply drag (0.99) then gravity (-0.05); other projectiles
                // use the same approximation, which is close enough over the short windows we simulate
                p = p.add(v);
                v = new Vec3(v.x * 0.99, v.y * 0.99 - 0.05, v.z * 0.99);
                double d = distToBox(p, playerBox);
                if (d < closest) closest = d;
                if (playerBox.contains(p)) {
                    // predicted hit at tick t
                    if (best == null || t < best.ticksToImpact) {
                        best = new ProjectileThreat(e, t, 0.0);
                    }
                    break; // first intersection for this projectile is the impact tick
                }
            }
        }
        return best;
    }

    /** Distance from a point to the nearest face of an AABB (0 if inside). */
    private static double distToBox(Vec3 p, AABB box) {
        double dx = Math.max(box.minX - p.x, Math.max(0, p.x - box.maxX));
        double dy = Math.max(box.minY - p.y, Math.max(0, p.y - box.maxY));
        double dz = Math.max(box.minZ - p.z, Math.max(0, p.z - box.maxZ));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
