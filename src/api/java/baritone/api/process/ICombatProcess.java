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

package baritone.api.process;

import net.minecraft.world.entity.Entity;

import java.util.Optional;

/**
 * The combat process — a cheat-style, human-looking fighter that hunts and kills mobs on demand.
 *
 * <p>The caller only decides <i>what</i> to hunt ("zombie", "nearest", "all"); this process decides
 * <i>how</i> — a per-tick state machine that approaches, strikes, kites, and retreats. Baritone is
 * the legs; this process is the fighter.
 *
 * @author Arion port
 */
public interface ICombatProcess extends IBaritoneProcess {

    /**
     * Start hunting mobs matching the argument. Accepts an entity-type name ("zombie",
     * "minecraft:creeper"), or the keywords {@code nearest} / {@code all}. An unknown type
     * logs an error and cancels any active hunt.
     *
     * @param arg the hunt target
     */
    void hunt(String arg);

    /**
     * Convenience: start hunting the nearest hostile mob.
     */
    default void huntNearestHostile() {
        hunt("nearest");
    }

    /**
     * @return the entity currently being fought, if any
     */
    Optional<Entity> currentTarget();

    /**
     * Disengage: cancel targeting, restore the reach attribute buff, and stop pathing.
     */
    void stop();

    /**
     * Set the auto-defend mode. In any non-{@link AutoDefendMode#OFF} mode, the combat process
     * self-activates to clear hostile mobs that threaten the bot (freezing, not cancelling, the
     * running task), then yields control back so the original task resumes.
     *
     * @param mode the new auto-defend mode
     */
    void autoDefendMode(AutoDefendMode mode);

    /**
     * @return the current auto-defend mode
     */
    AutoDefendMode getAutoDefendMode();

    /**
     * Convenience toggle preserved for backward compatibility: {@code true} maps to
     * {@link AutoDefendMode#TASK} (defend while a task is running), {@code false} to
     * {@link AutoDefendMode#OFF}. Prefer {@link #autoDefendMode(AutoDefendMode)} for the
     * always-defend mode.
     *
     * @param enabled whether task-mode auto-defend should be active
     */
    void autoDefend(boolean enabled);

    /**
     * @return whether auto-defend is active in any mode (i.e. not {@link AutoDefendMode#OFF})
     */
    boolean isAutoDefending();
}
