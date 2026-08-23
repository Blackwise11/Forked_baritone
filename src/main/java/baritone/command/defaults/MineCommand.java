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

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.helpers.TabCompleteHelper;
import baritone.api.utils.BlockOptionalMeta;
import baritone.utils.SmartMineResolver;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class MineCommand extends Command {

    public MineCommand(IBaritone baritone) {
        super(baritone, "mine");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        int quantity = args.getAsOrDefault(Integer.class, 0);
        args.requireMin(1);
        List<BlockOptionalMeta> boms = new ArrayList<>();
        while (args.hasAny()) {
            // Smart resolution: aliases (log/ore/...), ore-family expansion, fuzzy fallback.
            // Anything unresolved or ambiguous is reported and aborts the command — never guessed.
            SmartMineResolver.Resolution r = SmartMineResolver.resolve(args.getString());
            if (r.error() != null) {
                logDirect(r.error());
                return;
            }
            boms.addAll(r.boms());
            if (r.expansionNote() != null) {
                logDirect(r.expansionNote());
            }
        }
        BaritoneAPI.getProvider().getWorldScanner().repack(ctx);
        logDirect(String.format("Mining %s", boms.toString()));
        baritone.getMineProcess().mine(quantity, boms.toArray(new BlockOptionalMeta[0]));
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        args.getAsOrDefault(Integer.class, 0);
        while (args.has(2)) {
            args.getString();
        }
        String prefix = args.hasAny() ? args.peekString() : "";
        // aliases first, then every block id — matches BlockById's namespaced filtering
        Stream<String> aliases = SmartMineResolver.ALIASES.stream()
                .filter(a -> a.startsWith(prefix.toLowerCase()));
        Stream<String> blocks = new TabCompleteHelper()
                .append(BuiltInRegistries.BLOCK.keySet().stream().map(Object::toString))
                .filterPrefixNamespaced(prefix)
                .sortAlphabetically()
                .stream();
        return Stream.concat(aliases, blocks);
    }

    @Override
    public String getShortDesc() {
        return "Mine some blocks";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The mine command allows you to tell Baritone to search for and mine individual blocks.",
                "",
                "The specified blocks can be ores, or any other block.",
                "",
                "Smart arguments are supported:",
                "> mine log - any log (oak, spruce, ..., bamboo)",
                "> mine ore - any ore",
                "> mine stone - any base stone",
                "> mine iron_ore - also mines deepslate_iron_ore (see mineFamilyExpansion)",
                "",
                "Also see the legitMine settings (see #set l legitMine).",
                "",
                "Usage:",
                "> mine diamond_ore - Mines all diamonds it can find."
        );
    }
}
