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

package baritone.utils;

import baritone.Baritone;
import baritone.api.utils.BlockOptionalMeta;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turns a human-friendly {@code #mine} token into a list of blocks to mine.
 *
 * <p>Resolution order:
 * <ol>
 *   <li><b>Exact id</b> — {@code iron_ore}, {@code minecraft:iron_ore}, or a
 *       {@code block[prop=value]} selector passes through unchanged.</li>
 *   <li><b>Tag alias</b> — {@code log}/{@code wood} (any log), {@code ore} (any ore),
 *       {@code planks}, {@code leaves}, {@code stone} (any base stone).</li>
 *   <li><b>Family expansion</b> — {@code iron_ore} also mines {@code deepslate_iron_ore};
 *       {@code gold_ore} also {@code nether_gold_ore}. Both directions. Gated on
 *       {@link Settings#mineFamilyExpansion}.</li>
 *   <li><b>Fuzzy fallback</b> — a unique substring match on block ids fills in
 *       ({@code diamnd} → {@code diamond_ore}); an ambiguous token lists the options
 *       instead of guessing.</li>
 * </ol>
 *
 * <p>Every expansion is reported back via {@link Resolution#expansionNote} so the caller
 * can log it — the bot never silently guesses what to mine.
 */
public final class SmartMineResolver {

    /** Aliases offered first in tab completion, in display order. */
    public static final List<String> ALIASES = List.of("log", "logs", "wood", "ore", "ores", "planks", "leaves", "stone");

    /** Ore families with Nether variants (deepslate variants are handled generically). */
    private static final Set<String> NETHER_ORE_FAMILIES = Set.of("gold");

    private SmartMineResolver() {}

    /**
     * Outcome of resolving one token. Exactly one of {@code error} / the
     * {@code boms}+{@code expansionNote} pair is meaningful.
     *
     * @param boms           blocks to mine (empty iff error)
     * @param expansionNote  human-readable expansion summary, or null when the token
     *                       resolved to exactly what the user typed
     * @param error          failure message for chat, or null on success
     */
    public record Resolution(List<BlockOptionalMeta> boms, String expansionNote, String error) {

        static Resolution ok(List<BlockOptionalMeta> boms, String note) {
            return new Resolution(boms, note, null);
        }

        static Resolution fail(String error) {
            return new Resolution(List.of(), null, error);
        }
    }

    public static Resolution resolve(String token) {
        String s = token.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return Resolution.fail("empty block name");
        }

        // Property selectors (block[prop=value]) are exact-form only — no expansion applies.
        if (s.contains("[")) {
            return exactSelector(token);
        }

        // 1. Tag aliases
        Optional<List<Block>> alias = aliasBlocks(s);
        if (alias.isPresent()) {
            List<Block> expanded = new ArrayList<>(alias.get());
            String note = describe(s, expanded, false);
            return Resolution.ok(toBoms(expanded), note);
        }

        // 2. Exact block id
        ResourceLocation id = s.contains(":")
                ? ResourceLocation.tryParse(s)
                : ResourceLocation.fromNamespaceAndPath("minecraft", s);
        if (id != null) {
            Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(id);
            if (block.isPresent()) {
                List<Block> expanded = new ArrayList<>();
                expanded.add(block.get());
                boolean family = Baritone.settings().mineFamilyExpansion.value && expandFamily(id, expanded);
                if (!family) {
                    return Resolution.ok(List.of(new BlockOptionalMeta(block.get())), null);
                }
                return Resolution.ok(toBoms(expanded), describe(s, expanded, true));
            }
        }

        // 3. Fuzzy fallback — unique substring match
        List<ResourceLocation> matches = BuiltInRegistries.BLOCK.keySet().stream()
                .filter(rl -> rl.getPath().contains(s) || rl.toString().contains(s))
                .sorted()
                .collect(Collectors.toList());
        if (matches.isEmpty()) {
            return Resolution.fail("no block found matching '" + token + "'");
        }
        if (matches.size() > 1) {
            String options = matches.stream()
                    .limit(12)
                    .map(ResourceLocation::toString)
                    .collect(Collectors.joining(", "));
            String more = matches.size() > 12 ? " ... (" + matches.size() + " total)" : "";
            return Resolution.fail("'" + token + "' is ambiguous. Be more specific: " + options + more);
        }
        ResourceLocation match = matches.get(0);
        List<Block> expanded = new ArrayList<>();
        expanded.add(BuiltInRegistries.BLOCK.get(match));
        boolean family = Baritone.settings().mineFamilyExpansion.value && expandFamily(match, expanded);
        return Resolution.ok(toBoms(expanded), describe(s, expanded, family));
    }

    /** Exact-form selector (e.g. {@code chest[north=true]}) — parse via BlockOptionalMeta. */
    private static Resolution exactSelector(String token) {
        try {
            return Resolution.ok(List.of(new BlockOptionalMeta(token)), null);
        } catch (Exception e) {
            return Resolution.fail("invalid block selector '" + token + "'");
        }
    }

    /** Alias token → tag contents, or empty if the token isn't an alias. */
    private static Optional<List<Block>> aliasBlocks(String s) {
        switch (s) {
            case "log":
            case "logs":
            case "wood":
                return Optional.of(tagBlocks(BlockTags.LOGS, BlockTags.BAMBOO_BLOCKS));
            case "ore":
            case "ores":
                return Optional.of(oreBlocks());
            case "planks":
                return Optional.of(tagBlocks(BlockTags.PLANKS));
            case "leaves":
                return Optional.of(tagBlocks(BlockTags.LEAVES));
            case "stone":
                return Optional.of(tagBlocks(BlockTags.BASE_STONE_OVERWORLD, BlockTags.BASE_STONE_NETHER));
            default:
                return Optional.empty();
        }
    }

    /** All blocks in the {@code c:ores} common tag, falling back to {@code *_ore} ids. */
    private static List<Block> oreBlocks() {
        TagKey<Block> cOres = TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("c", "ores"));
        List<Block> fromTag = tagBlocks(cOres);
        if (!fromTag.isEmpty()) {
            return fromTag;
        }
        // No common tag in this environment (unexpected on NeoForge, possible elsewhere)
        return BuiltInRegistries.BLOCK.keySet().stream()
                .filter(rl -> rl.getPath().endsWith("_ore"))
                .map(BuiltInRegistries.BLOCK::get)
                .sorted()
                .collect(Collectors.toList());
    }

    /** Union of the given tags' contents (minecraft namespace only for the vanilla tags). */
    private static List<Block> tagBlocks(TagKey<Block>... tags) {
        Set<Block> blocks = new LinkedHashSet<>();
        for (TagKey<Block> tag : tags) {
            BuiltInRegistries.BLOCK.getTag(tag).ifPresent(named -> named.forEach(h -> blocks.add(h.value())));
        }
        return new ArrayList<>(blocks);
    }

    /**
     * Add ore-family siblings to {@code out}: {@code X_ore} ↔ {@code deepslate_X_ore},
     * plus Nether variants ({@code gold_ore} → {@code nether_gold_ore}). Data-driven ores
     * and modded ores with matching id patterns are picked up for free.
     *
     * @return true if anything was added
     */
    private static boolean expandFamily(ResourceLocation id, List<Block> out) {
        String path = id.getPath();
        String family = null;
        boolean deepslate = false;
        if (path.endsWith("_ore")) {
            family = path.substring(0, path.length() - 4);
        } else if (path.startsWith("deepslate_") && path.endsWith("_ore")) {
            family = path.substring("deepslate_".length(), path.length() - 4);
            deepslate = true;
        }
        if (family == null) {
            return false;
        }

        boolean added = false;
        // Overworld ↔ deepslate
        if (!deepslate) {
            added |= addSibling("deepslate_" + family + "_ore", out);
        } else {
            added |= addSibling(family + "_ore", out);
        }
        // Nether variants
        if (NETHER_ORE_FAMILIES.contains(family)) {
            added |= addSibling("nether_" + family + "_ore", out);
        }
        return added;
    }

    private static boolean addSibling(String path, List<Block> out) {
        Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.fromNamespaceAndPath("minecraft", path));
        if (block.isPresent() && !out.contains(block.get())) {
            out.add(block.get());
            return true;
        }
        return false;
    }

    private static List<BlockOptionalMeta> toBoms(List<Block> blocks) {
        return blocks.stream().map(BlockOptionalMeta::new).collect(Collectors.toList());
    }

    /** {@code mine <token> -> [ids] (N blocks)} */
    private static String describe(String token, List<Block> blocks, boolean family) {
        if (blocks.size() == 1 && !family) {
            return null;
        }
        String ids = blocks.stream()
                .limit(12)
                .map(b -> BuiltInRegistries.BLOCK.getKey(b).toString())
                .sorted()
                .collect(Collectors.joining(", "));
        String more = blocks.size() > 12 ? ", ... " + (blocks.size() - 12) + " more" : "";
        return String.format("mine %s -> %s%s (%d blocks)", token, ids, more, blocks.size());
    }
}
