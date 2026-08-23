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

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Drives the survival process's auto-eat: {@code #eat now} eats once immediately, {@code #eat on}
 * / {@code #eat off} toggle the {@code autoEat} setting, and bare {@code #eat} reports the current
 * state. Auto-eat is opt-in (default off) so the bot won't eat your food unprompted.
 */
public class EatCommand extends Command {

    public EatCommand(IBaritone baritone) {
        super(baritone, "eat");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        String arg = args.hasAny() ? args.getString() : EatKeyword.STATUS.name().toLowerCase(Locale.US);
        switch (arg.toLowerCase(Locale.US)) {
            case "now" -> {
                ((Baritone) baritone).getSurvivalProcess().eatNow();
                logDirect("Eating now (if food is available).");
            }
            case "on" -> {
                Baritone.settings().autoEat.value = true;
                logDirect("Auto-eat enabled.");
            }
            case "off" -> {
                Baritone.settings().autoEat.value = false;
                logDirect("Auto-eat disabled.");
            }
            default -> logDirect("Auto-eat is " + (Baritone.settings().autoEat.value ? "ON" : "OFF")
                    + ". Use: eat now | eat on | eat off");
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            return new TabCompleteHelper()
                    .append(EatKeyword.class)
                    .filterPrefix(args.getString())
                    .sortAlphabetically()
                    .stream();
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Eat food / toggle auto-eat";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The eat command makes Baritone eat food.",
                "",
                "Usage:",
                "> eat now - Eat once immediately.",
                "> eat on - Enable auto-eat (eats when hunger drops below autoEatBelowFood).",
                "> eat off - Disable auto-eat.",
                "> eat - Show the current auto-eat state."
        );
    }

    /** Keyword arguments. {@code @KeepName} so ProGuard preserves the constant names for tab-complete. */
    @KeepName
    private enum EatKeyword {
        NOW,
        ON,
        OFF,
        STATUS
    }
}
