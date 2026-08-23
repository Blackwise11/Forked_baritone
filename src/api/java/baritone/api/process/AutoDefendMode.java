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

/**
 * When the combat process may self-activate to clear a hostile that threatens the player.
 *
 * <p>In every non-{@link #OFF} mode, auto-defend is a <em>temporary</em> process: it freezes
 * (does not cancel) whatever else was running, fights the threat, then yields control back so the
 * original task resumes — exactly like auto-eat.
 *
 * @see ICombatProcess#autoDefendMode(AutoDefendMode)
 */
public enum AutoDefendMode {
    /**
     * Never self-activate. Combat only runs from an explicit {@code #hunt}.
     */
    OFF,
    /**
     * Self-defend only while another task is actively running (pathing — mining, goto, follow,
     * farm, etc.). When idle, the bot leaves combat to the player. This is the original
     * {@code combatAutoDefend = true} behavior.
     */
    TASK,
    /**
     * Always self-defend the moment a hostile enters {@code combatDefendRange}, even when the bot
     * is otherwise idle.
     */
    ALWAYS
}
