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

package baritone.command.defaults;

import baritone.Baritone;
import baritone.KeepName;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.helpers.TabCompleteHelper;
import baritone.api.process.AutoDefendMode;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Drives the combat process: {@code #hunt zombie}, {@code #hunt nearest}, {@code #hunt all},
 * {@code #hunt stop}, and {@code #hunt autodefend [off|task|always]}. The
 * {@link baritone.api.process.ICombatProcess#hunt(String)} does all the real work (validation,
 * logging, state-machine startup); this just routes the argument.
 */
public class HuntCommand extends Command {

    public HuntCommand(IBaritone baritone) {
        super(baritone, "hunt");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        String arg;
        if (args.hasAny()) {
            arg = args.getString();
        } else {
            arg = HuntKeyword.NEAREST.name().toLowerCase(Locale.US);
        }
        if (arg.equalsIgnoreCase(HuntKeyword.STOP.name())) {
            baritone.getCombatProcess().stop();
            return;
        }
        if (arg.equalsIgnoreCase(HuntKeyword.AUTODEFEND.name())) {
            if (args.hasAny()) {
                String mode = args.getString();
                AutoDefendMode parsed = parseMode(mode);
                if (parsed != null) {
                    baritone.getCombatProcess().autoDefendMode(parsed);
                } else {
                    logDirect("Usage: hunt autodefend [off|task|always]  ('on' = task)");
                }
            } else {
                logDirect("Auto-defend: " + baritone.getCombatProcess().getAutoDefendMode().name().toLowerCase(Locale.US)
                        + ". Use: hunt autodefend off | task | always");
            }
            return;
        }
        if (arg.equalsIgnoreCase(HuntKeyword.BOW.name())) {
            baritone.api.Settings settings = Baritone.settings();
            if (args.hasAny()) {
                String v = args.getString();
                if (v.equalsIgnoreCase("on") || v.equalsIgnoreCase("true")) {
                    settings.combatForceBow.value = true;
                    logDirect("Force-bow ON — will shoot at any range. Start a hunt to test.");
                } else if (v.equalsIgnoreCase("off") || v.equalsIgnoreCase("false")) {
                    settings.combatForceBow.value = false;
                    logDirect("Force-bow OFF.");
                } else {
                    logDirect("Usage: hunt bow [on|off]");
                }
            } else {
                logDirect("Force-bow: " + (settings.combatForceBow.value ? "on" : "off")
                        + ". Use: hunt bow on | off");
            }
            return;
        }
        baritone.getCombatProcess().hunt(arg);
    }

    /** {@code on} maps to TASK for backward compatibility; {@code off}/{@code task}/{@code always} are direct. */
    private static AutoDefendMode parseMode(String s) {
        return switch (s.toLowerCase(Locale.US)) {
            case "off" -> AutoDefendMode.OFF;
            case "on", "task" -> AutoDefendMode.TASK;
            case "always" -> AutoDefendMode.ALWAYS;
            default -> null;
        };
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            return new TabCompleteHelper()
                    .append(HuntKeyword.class)
                    .append(BuiltInRegistries.ENTITY_TYPE.stream().map(Object::toString))
                    .filterPrefix(args.getString())
                    .sortAlphabetically()
                    .stream();
        }
        if (args.hasExactly(2) && args.peekString(0).equalsIgnoreCase(HuntKeyword.AUTODEFEND.name())) {
            return new TabCompleteHelper()
                    .append("off", "task", "always", "on")
                    .filterPrefix(args.peekString(1))
                    .stream();
        }
        if (args.hasExactly(2) && args.peekString(0).equalsIgnoreCase(HuntKeyword.BOW.name())) {
            return new TabCompleteHelper()
                    .append("on", "off")
                    .filterPrefix(args.peekString(1))
                    .stream();
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Hunt and fight nearby mobs";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The hunt command tells Baritone to fight mobs.",
                "",
                "Usage:",
                "> hunt - Hunt the nearest hostile mob.",
                "> hunt nearest - Hunt the nearest hostile mob.",
                "> hunt all - Fight every hostile in range.",
                "> hunt <type> - Hunt a specific mob type (e.g. zombie, skeleton, creeper).",
                "> hunt stop - Stop hunting.",
                "> hunt autodefend [off|task|always] - Auto-defend mode.",
                "    off    - never self-defend (combat only via #hunt).",
                "    task   - self-defend only while another task is running (default-on behavior).",
                "    always - self-defend the moment a hostile is near, even when idle.",
                "> hunt bow [on|off] - Force bow usage at any range (for testing bow aim/draw)."
        );
    }

    /** Keyword arguments. {@code @KeepName} so ProGuard preserves the constant names for tab-complete. */
    @KeepName
    private enum HuntKeyword {
        NEAREST,
        ALL,
        STOP,
        AUTODEFEND,
        BOW
    }
}
