# Improvement Plan — Modded Baritone (1.21.1 branch)

Branch: `1.21.1` (MC 1.21.1 / NeoForge 21.1.248), combat + auto-eat port verified working in-game.
Scope: three features, ordered by user request. Each is independently shippable.

---

## Feature 1 — `#mine` + FTB Ultimine integration

**Goal:** when Baritone mines, it "holds" the FTB Ultimine key so one block break veins the
whole deposit, like a vein-miner. Big speedup on ores and logs.

### How FTB Ultimine works (what we're up against)
- It's a keybind-based client mod: hold key while looking at a block → overlay shows the
  vein → breaking the block breaks all of them. The server must also run FTB Ultimine
  (it validates shape + durability/food cost); on servers without it, nothing happens.
- Baritone's `InputOverrideHandler` can only force the vanilla `Input` enum
  (`attack`, `use`, `sneak`, ...). The Ultimine key is a **custom `KeyMapping`** registered
  by the mod, so the existing override system cannot press it.

### Design
1. **New setting**: `useFtbUltimine` (Boolean, default `false` — opt-in, mod may be absent).
2. **Mod detection**: at runtime check whether the Ultimine keybind exists. Find it by
   scanning `Minecraft.getInstance().options.keyMappings` for the mapping whose translation
   key / category belongs to FTB Ultimine (`key.ftbultimine`). Cache the lookup; re-scan on
   world load (options are recreated).
3. **Key hold**: while Baritone is actively breaking a block (`BlockBreakHelper` is holding
   `attack`), call `keyMapping.setDown(true)` each client tick; release when the break ends.
   Hook point: `InputOverrideHandler.onTick()` already runs every tick and knows the break
   state (`getBlockBreakHelper()`), so the cleanest spot is a small addition there —
   keeping all input forcing in one place.
4. **Aiming prerequisite**: Ultimine veins from the block under the crosshair, and Baritone
   already looks at the block it breaks, so aiming is satisfied for free.
5. **Guard rails**:
   - If Ultimine's keybind isn't found → silently no-op (and log once at debug level).
   - Setting off / mod absent → zero behavior change.

### Risks / open questions (resolve during implementation)
- Ultimine has a server-validated cooldown/shape limit per activation. Baritone breaks fast;
  if the mod refuses consecutive activations we may need to pace breaks (e.g. hold the key
  only on the first break of a new vein, or add a small inter-vein delay).
- Durability/hunger cost multiplies per block — worth a note in the setting javadoc so users
  on low durability don't shred their pickaxe. Optional companion setting later
  (`ftbUltimineMinDurability`).
- Alternative researched but deprioritized: FTB's official Ultimine API as a compile-only
  dependency (programmatic shape triggering). Key-hold is dependency-free and works with
  any user keybind; API route only if key-hold proves unreliable.

### Files touched
- `src/api/java/baritone/api/Settings.java` — `useFtbUltimine`
- `src/main/java/baritone/utils/InputOverrideHandler.java` — key-find + hold logic
- `COMBAT_HANDOFF.md` / README-adjacent docs — usage note

### Test plan
- Single-player with Ultimine installed: `#mine iron_ore` → watch overlay appear as Baritone
  breaks; confirm multi-block breaks and durability drain matches manual use.
- Without Ultimine installed: `#mine` behaves exactly as today (no errors in log).
- Multiplayer server without the mod: mining still works normally (client-side no-op).

---

## Feature 2 — Collect drops before moving on (mining, chopping, combat)

**Goal:** don't leave loot on the ground. After finishing a target (block vein, tree, mob),
walk over the drops and pick them up *before* switching to the next target / declaring done.

### What already exists (build on it, don't duplicate)
- `MineProcess` tracks `anticipatedDrops` (positions where blocks were just broken) and has
  `mineScanDroppedItems` (default **true**) which adds live `ItemEntity` positions to the
  mine-goal list, plus `mineDropLoiterDurationMSThanksLouca` (default **250 ms**) that keeps
  those positions "interesting" briefly. So mining already half-collects — but nothing
  *gates completion* on the items actually entering the inventory, and the loiter window is
  tiny.
- `FollowProcess.pickup(Predicate<ItemStack>)` — existing item-follow machinery used by
  `#pickup`.

### Design — mining / chopping
1. **New setting**: `mineCollectDrops` (Boolean, default `false`).
2. When on, `MineProcess` completion logic additionally requires: no *matching* `ItemEntity`
   within `collectRadius` (default ~8 blocks) that is reachable — i.e. after the last block
   is broken, Baritone paths over the drop positions (GoalNear at the item, pickup radius
   does the rest) until they're gone or a timeout hits.
3. Items are picked up by walking within ~1 block, which pathing over the position achieves;
   no new input handling needed.
4. Timeout setting `mineCollectTimeoutSeconds` (default 10) so a drop that fell somewhere
   unreachable (lava, cliff edge) doesn't hang the process — log "left behind: 2 oak_log".

### Design — combat loot
1. **New state** in `CombatProcess`: `LOOT`, entered after `RECOVER` when the target died
   (not when it fled): scan `ItemEntity` within `combatLootRadius` (default 8.0), path to
   each in turn (nearest first), wait until collected.
2. Exit conditions: no loot left in radius, or `combatLootTimeoutSeconds` (default 8)
   elapsed. Then → next target (chain hunting continues) or `IDLE`.
3. Under `AUTODEFEND` mode, skip LOOT if a new threat appears — survival beats loot.
   (The threat scan in `isActive()` already re-triggers; LOOT must not block it.)
4. Optional filter setting `combatLootAll` (default `true`; if false, only pick up drops
   from the hunted entity type's loot table... simplest v1: pick up everything nearby).

### Files touched
- `Settings.java` — `mineCollectDrops`, `mineCollectTimeoutSeconds`, `combatLootRadius`,
  `combatLootTimeoutSeconds`
- `MineProcess.java` — completion gate + drop-walk phase
- `CombatProcess.java` — `LOOT` state (~40 lines: scan, path, timeout)

### Test plan
- `#mine oak_log` against a tree: last log broken → bot walks over the dropped logs →
  inventory count increases → only then "done" / next target.
- `#hunt zombie`: kill → walks onto the drops → resumes chain hunt.
- Drop near lava: timeout fires, warning logged, process continues.

---

## Feature 3 — Smarter `#mine` arguments

**Goal:** `#mine log` = any log nearby. `#mine ore` = any ore nearby. `#mine iron_ore`
= **both** iron ore and deepslate iron ore (family expansion). Today the command demands
exact block ids, and vanilla splits ore families across two ids.

### Design — a resolver layer in front of the existing parser
New helper (e.g. `src/main/java/baritone/utils/SmartMineResolver.java`) that turns one user
token into a list of `BlockOptionalMeta`, applied in `MineCommand.execute()` before the
existing `ForBlockOptionalMeta` datatype parsing runs. Resolution order:

1. **Exact id** (`iron_ore`, `minecraft:iron_ore`) → that block only (current behavior,
   unchanged).
2. **Tag alias** — a curated map, resolved against `BuiltInRegistries.BLOCK` tags:
   - `log` / `logs` / `wood` → `minecraft:logs` (+ optionally `minecraft:bamboo_blocks`)
   - `ore` / `ores` → every block in tag `c:ores` (fallback: every id ending `_ore`)
   - `planks` → `minecraft:planks`; `leaves` → `minecraft:leaves`; `stone` →
     `minecraft:stone`… start with the high-value ones, list stays in one constant map.
3. **Family expansion** (setting `mineFamilyExpansion`, default `true`): for any resolved
   ore block, also include sibling variants:
   - `X_ore` → `deepslate_X_ore`
   - `gold_ore` → also `nether_gold_ore` (same rule keyed off a small family table:
     iron, gold, copper, diamond, emerald, redstone, lapis, coal)
   - `ancient_debris` family-safe (no siblings) — table-driven so it's data, not code.
4. **Fuzzy fallback**: token is not an id or alias → unique substring/suffix match against
   block ids (`diamond` → `diamond_ore`? ambiguous with `diamond_block` → if multiple
   matches, list them and ask the user to be specific; if exactly one, use it).

**Transparency requirement:** whenever the resolver expands anything, log the expansion:
`mine log -> oak_log, dark_oak_log, spruce_log, ... (10 blocks)`. The bot must never
silently guess.

### Interaction with the other features
- Feature 1 (Ultimine) pairs perfectly with `#mine ore` — vein mining + any-ore resolution.
- Feature 2's "collect before moving on" makes `#mine ore` actually keep what it breaks.

### Files touched
- `src/main/java/baritone/utils/SmartMineResolver.java` — new (~150 lines with the tables)
- `src/main/java/baritone/command/defaults/MineCommand.java` — call resolver, feed expanded
  list into `mine(quantity, ...)`
- `Settings.java` — `mineFamilyExpansion`
- `MineCommand` tab-complete: offer alias tokens + block ids (aliases first)

### Test plan
- `#mine log` in a forest → mines any log type, expansion message lists the set.
- `#mine iron_ore` → mines both iron_ore and deepslate_iron_ore; toggle
  `mineFamilyExpansion` off → old behavior.
- `#mine ore` → mines any ore it can find.
- `#mine diamnd` (typo) → fuzzy finds `diamond_ore`... only if unique; `#mine diamond` →
  ambiguous with diamond_block → lists options instead of guessing.

---

## Suggested order & effort

| # | Feature | Depends on | Rough effort |
|---|---------|-----------|--------------|
| 3 | Smart mine arguments | none | smallest — pure parsing, easy to test |
| 2 | Drop collection | none | medium — two processes, timeout logic |
| 1 | FTB Ultimine | best after 3 (`#mine ore` + veins is the showcase) | medium — key-hold + cooldown tuning |

Each feature: implement → single-player test → commit → push to `modded` remote.
