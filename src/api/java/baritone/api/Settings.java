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

package baritone.api;

import baritone.api.utils.Helper;
import baritone.api.utils.NotificationHelper;
import baritone.api.utils.SettingsUtil;
import baritone.api.utils.TypeUtils;
import baritone.api.utils.gui.BaritoneToast;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Baritone's settings. Settings apply to all Baritone instances.
 *
 * @author leijurv
 */
public final class Settings {
    private static final Logger LOGGER = LoggerFactory.getLogger("Baritone");

    /**
     * Allow Baritone to break blocks
     */
    public final Setting<Boolean> allowBreak = new Setting<>(true);

    /**
     * Blocks that baritone will be allowed to break even with allowBreak set to false
     */
    public final Setting<List<Block>> allowBreakAnyway = new Setting<>(new ArrayList<>());

    /**
     * Allow Baritone to sprint
     */
    public final Setting<Boolean> allowSprint = new Setting<>(true);

    /**
     * Allow Baritone to place blocks
     */
    public final Setting<Boolean> allowPlace = new Setting<>(true);

    /**
     * Allow Baritone to place blocks in fluid source blocks
     */
    public final Setting<Boolean> allowPlaceInFluidsSource = new Setting<>(true);

    /**
     * Allow Baritone to place blocks in flowing fluid
     */
    public final Setting<Boolean> allowPlaceInFluidsFlow = new Setting<>(true);

    /**
     * Allow Baritone to move items in your inventory to your hotbar
     */
    public final Setting<Boolean> allowInventory = new Setting<>(false);

    /**
     * Wait this many ticks between InventoryBehavior moving inventory items
     */
    public final Setting<Integer> ticksBetweenInventoryMoves = new Setting<>(1);

    /**
     * Come to a halt before doing any inventory moves. Intended for anticheat such as 2b2t
     */
    public final Setting<Boolean> inventoryMoveOnlyIfStationary = new Setting<>(false);

    /**
     * Disable baritone's auto-tool at runtime, but still assume that another mod will provide auto tool functionality
     * <p>
     * Specifically, path calculation will still assume that an auto tool will run at execution time, even though
     * Baritone itself will not do that.
     */
    public final Setting<Boolean> assumeExternalAutoTool = new Setting<>(false);

    /**
     * Automatically select the best available tool
     */
    public final Setting<Boolean> autoTool = new Setting<>(true);

    /**
     * It doesn't actually take twenty ticks to place a block, this cost is so high
     * because we want to generally conserve blocks which might be limited.
     * <p>
     * Decrease to make Baritone more often consider paths that would require placing blocks
     */
    public final Setting<Double> blockPlacementPenalty = new Setting<>(20D);

    /**
     * This is just a tiebreaker to make it less likely to break blocks if it can avoid it.
     * For example, fire has a break cost of 0, this makes it nonzero, so all else being equal
     * it will take an otherwise equivalent route that doesn't require it to put out fire.
     */
    public final Setting<Double> blockBreakAdditionalPenalty = new Setting<>(2D);

    /**
     * Additional penalty for hitting the space bar (ascend, pillar, or parkour) because it uses hunger
     */
    public final Setting<Double> jumpPenalty = new Setting<>(2D);

    /**
     * Walking on water uses up hunger really quick, so penalize it
     */
    public final Setting<Double> walkOnWaterOnePenalty = new Setting<>(3D);

    /**
     * Don't allow breaking blocks next to liquids.
     * <p>
     * Enable if you have mods adding custom fluid physics.
     */
    public final Setting<Boolean> strictLiquidCheck = new Setting<>(false);

    /**
     * Allow Baritone to fall arbitrary distances and place a water bucket beneath it.
     * Reliability: questionable.
     */
    public final Setting<Boolean> allowWaterBucketFall = new Setting<>(true);

    /**
     * Allow Baritone to assume it can walk on still water just like any other block.
     * This functionality is assumed to be provided by a separate library that might have imported Baritone.
     * <p>
     * Note: This will prevent some usage of the frostwalker enchantment, like pillaring up from water.
     */
    public final Setting<Boolean> assumeWalkOnWater = new Setting<>(false);

    /**
     * If you have Fire Resistance and Jesus then I guess you could turn this on lol
     */
    public final Setting<Boolean> assumeWalkOnLava = new Setting<>(false);

    /**
     * Assume step functionality; don't jump on an Ascend.
     */
    public final Setting<Boolean> assumeStep = new Setting<>(false);

    /**
     * Assume safe walk functionality; don't sneak on a backplace traverse.
     * <p>
     * Warning: if you do something janky like sneak-backplace from an ender chest, if this is true
     * it won't sneak right click, it'll just right click, which means it'll open the chest instead of placing
     * against it. That's why this defaults to off.
     */
    public final Setting<Boolean> assumeSafeWalk = new Setting<>(false);

    /**
     * If true, parkour is allowed to make jumps when standing on blocks at the maximum height, so player feet is y=256
     * <p>
     * Defaults to false because this fails on constantiam. Please let me know if this is ever disabled. Please.
     */
    public final Setting<Boolean> allowJumpAtBuildLimit = new Setting<>(false);

    /**
     * Just here so mods that use the API don't break. Does nothing.
     */
    @Deprecated
    @JavaOnly
    public final Setting<Boolean> allowJumpAt256 = new Setting<>(false);

    /**
     * This should be monetized it's so good
     * <p>
     * Defaults to true, but only actually takes effect if allowParkour is also true
     */
    public final Setting<Boolean> allowParkourAscend = new Setting<>(true);

    /**
     * Allow descending diagonally
     * <p>
     * Safer than allowParkour yet still slightly unsafe, can make contact with unchecked adjacent blocks, so it's unsafe in the nether.
     * <p>
     * For a generic "take some risks" mode I'd turn on this one, parkour, and parkour place.
     */
    public final Setting<Boolean> allowDiagonalDescend = new Setting<>(false);

    /**
     * Allow diagonal ascending
     * <p>
     * Actually pretty safe, much safer than diagonal descend tbh
     */
    public final Setting<Boolean> allowDiagonalAscend = new Setting<>(false);

    /**
     * Allow mining the block directly beneath its feet
     * <p>
     * Turn this off to force it to make more staircases and less shafts
     */
    public final Setting<Boolean> allowDownward = new Setting<>(true);

    /**
     * Blocks that Baritone is allowed to place (as throwaway, for sneak bridging, pillaring, etc.)
     */
    public final Setting<List<Item>> acceptableThrowawayItems = new Setting<>(new ArrayList<>(Arrays.asList(
            Blocks.DIRT.asItem(),
            Blocks.COBBLESTONE.asItem(),
            Blocks.NETHERRACK.asItem(),
            Blocks.STONE.asItem()
    )));

    /**
     * Blocks that Baritone will attempt to avoid (Used in avoidance)
     */
    public final Setting<List<Block>> blocksToAvoid = new Setting<>(new ArrayList<>(List.of(
            Blocks.TRIPWIRE
    )));

    /**
     * Blocks that Baritone is not allowed to break
     */
    public final Setting<List<Block>> blocksToDisallowBreaking = new Setting<>(new ArrayList<>(
            // Leave Empty by Default
    ));

    /**
     * blocks that baritone shouldn't break, but can if it needs to.
     */
    public final Setting<List<Block>> blocksToAvoidBreaking = new Setting<>(new ArrayList<>(Arrays.asList( // TODO can this be a HashSet or ImmutableSet?
            Blocks.CRAFTING_TABLE,
            Blocks.FURNACE,
            Blocks.CHEST,
            Blocks.TRAPPED_CHEST
    )));

    /**
     * this multiplies the break speed, if set above 1 it's "encourage breaking" instead
     */
    public final Setting<Double> avoidBreakingMultiplier = new Setting<>(.1);

    /**
     * A list of blocks to be treated as if they're air.
     * <p>
     * If a schematic asks for air at a certain position, and that position currently contains a block on this list, it will be treated as correct.
     */
    public final Setting<List<Block>> buildIgnoreBlocks = new Setting<>(new ArrayList<>(Arrays.asList(

    )));

    /**
     * A list of blocks to be treated as correct.
     * <p>
     * If a schematic asks for any block on this list at a certain position, it will be treated as correct, regardless of what it currently is.
     */
    public final Setting<List<Block>> buildSkipBlocks = new Setting<>(new ArrayList<>(Arrays.asList(

    )));

    /**
     * A mapping of blocks to blocks treated as correct in their position
     * <p>
     * If a schematic asks for a block on this mapping, all blocks on the mapped list will be accepted at that location as well
     * <p>
     * Syntax same as <a href="https://baritone.leijurv.com/baritone/api/Settings.html#buildSubstitutes">buildSubstitutes</a>
     */
    public final Setting<Map<Block, List<Block>>> buildValidSubstitutes = new Setting<>(new HashMap<>());

    /**
     * A mapping of blocks to blocks to be built instead
     * <p>
     * If a schematic asks for a block on this mapping, Baritone will place the first placeable block in the mapped list
     * <p>
     * Usage Syntax:
     * <pre>
     *      sourceblockA->blockToSubstituteA1,blockToSubstituteA2,...blockToSubstituteAN,sourceBlockB->blockToSubstituteB1,blockToSubstituteB2,...blockToSubstituteBN,...sourceBlockX->blockToSubstituteX1,blockToSubstituteX2...blockToSubstituteXN
     * </pre>
     * Example:
     * <pre>
     *     stone->cobblestone,andesite,oak_planks->birch_planks,acacia_planks,glass
     * </pre>
     */
    public final Setting<Map<Block, List<Block>>> buildSubstitutes = new Setting<>(new HashMap<>());

    /**
     * A list of blocks to become air
     * <p>
     * If a schematic asks for a block on this list, only air will be accepted at that location (and nothing on buildIgnoreBlocks)
     */
    public final Setting<List<Block>> okIfAir = new Setting<>(new ArrayList<>(Arrays.asList(

    )));

    /**
     * If this is true, the builder will treat all non-air blocks as correct. It will only place new blocks.
     */
    public final Setting<Boolean> buildIgnoreExisting = new Setting<>(false);

    /**
     * If this is true, the builder will ignore directionality of certain blocks like glazed terracotta.
     */
    public final Setting<Boolean> buildIgnoreDirection = new Setting<>(false);

    /**
     * A list of names of block properties the builder will ignore.
     */
    public final Setting<List<String>> buildIgnoreProperties = new Setting<>(new ArrayList<>(Arrays.asList(
    )));

    /**
     * If this setting is true, Baritone will never break a block that is adjacent to an unsupported falling block.
     * <p>
     * I.E. it will never trigger cascading sand / gravel falls
     */
    public final Setting<Boolean> avoidUpdatingFallingBlocks = new Setting<>(true);

    /**
     * Enables some more advanced vine features. They're honestly just gimmicks and won't ever be needed in real
     * pathing scenarios. And they can cause Baritone to get trapped indefinitely in a strange scenario.
     * <p>
     * Almost never turn this on lol
     */
    public final Setting<Boolean> allowVines = new Setting<>(false);

    /**
     * Slab behavior is complicated, disable this for higher path reliability. Leave enabled if you have bottom slabs
     * everywhere in your base.
     */
    public final Setting<Boolean> allowWalkOnBottomSlab = new Setting<>(true);

    /**
     * You know what it is
     * <p>
     * But it's very unreliable and falls off when cornering like all the time so.
     * <p>
     * It also overshoots the landing pretty much always (making contact with the next block over), so be careful
     */
    public final Setting<Boolean> allowParkour = new Setting<>(false);

    /**
     * Actually pretty reliable.
     * <p>
     * Doesn't make it any more dangerous compared to just normal allowParkour th
     */
    public final Setting<Boolean> allowParkourPlace = new Setting<>(false);

    /**
     * For example, if you have Mining Fatigue or Haste, adjust the costs of breaking blocks accordingly.
     */
    public final Setting<Boolean> considerPotionEffects = new Setting<>(true);

    /**
     * Sprint and jump a block early on ascends wherever possible
     */
    public final Setting<Boolean> sprintAscends = new Setting<>(true);

    /**
     * If we overshoot a traverse and end up one block beyond the destination, mark it as successful anyway.
     * <p>
     * This helps with speed exceeding 20m/s
     */
    public final Setting<Boolean> overshootTraverse = new Setting<>(true);

    /**
     * When breaking blocks for a movement, wait until all falling blocks have settled before continuing
     */
    public final Setting<Boolean> pauseMiningForFallingBlocks = new Setting<>(true);

    /**
     * How many ticks between right clicks are allowed. Default in game is 4
     */
    public final Setting<Integer> rightClickSpeed = new Setting<>(4);

    /**
     * How many degrees to randomize the yaw every tick. Set to 0 to disable
     */
    public final Setting<Double> randomLooking113 = new Setting<>(2d);

    /**
     * Block reach distance
     */
    public final Setting<Float> blockReachDistance = new Setting<>(4.5f);

    /**
     * How many ticks between breaking a block and starting to break the next block. Default in game is 6 ticks.
     * Values under 1 will be clamped. The delay only applies to non-instant (1-tick) breaks.
     */
    public final Setting<Integer> blockBreakSpeed = new Setting<>(6);

    /**
     * How many degrees to randomize the pitch and yaw every tick. Set to 0 to disable
     */
    public final Setting<Double> randomLooking = new Setting<>(0.01d);

    /**
     * This is the big A* setting.
     * As long as your cost heuristic is an *underestimate*, it's guaranteed to find you the best path.
     * 3.5 is always an underestimate, even if you are sprinting.
     * If you're walking only (with allowSprint off) 4.6 is safe.
     * Any value below 3.5 is never worth it. It's just more computation to find the same path, guaranteed.
     * (specifically, it needs to be strictly slightly less than ActionCosts.WALK_ONE_BLOCK_COST, which is about 3.56)
     * <p>
     * Setting it at 3.57 or above with sprinting, or to 4.64 or above without sprinting, will result in
     * faster computation, at the cost of a suboptimal path. Any value above the walk / sprint cost will result
     * in it going straight at its goal, and not investigating alternatives, because the combined cost / heuristic
     * metric gets better and better with each block, instead of slightly worse.
     * <p>
     * Finding the optimal path is worth it, so it's the default.
     */
    public final Setting<Double> costHeuristic = new Setting<>(3.563);

    // a bunch of obscure internal A* settings that you probably don't want to change
    /**
     * The maximum number of times it will fetch outside loaded or cached chunks before assuming that
     * pathing has reached the end of the known area, and should therefore stop.
     */
    public final Setting<Integer> pathingMaxChunkBorderFetch = new Setting<>(50);

    /**
     * Set to 1.0 to effectively disable this feature
     *
     * @see <a href="https://github.com/cabaletta/baritone/issues/18">Issue #18</a>
     */
    public final Setting<Double> backtrackCostFavoringCoefficient = new Setting<>(0.5);

    /**
     * Toggle the following 4 settings
     * <p>
     * They have a noticeable performance impact, so they default off
     * <p>
     * Specifically, building up the avoidance map on the main thread before pathing starts actually takes a noticeable
     * amount of time, especially when there are a lot of mobs around, and your game jitters for like 200ms while doing so
     */
    public final Setting<Boolean> avoidance = new Setting<>(false);

    /**
     * Set to 1.0 to effectively disable this feature
     * <p>
     * Set below 1.0 to go out of your way to walk near mob spawners
     */
    public final Setting<Double> mobSpawnerAvoidanceCoefficient = new Setting<>(2.0);

    /**
     * Distance to avoid mob spawners.
     */
    public final Setting<Integer> mobSpawnerAvoidanceRadius = new Setting<>(16);

    /**
     * Set to 1.0 to effectively disable this feature
     * <p>
     * Set below 1.0 to go out of your way to walk near mobs
     */
    public final Setting<Double> mobAvoidanceCoefficient = new Setting<>(1.5);

    /**
     * Distance to avoid mobs.
     */
    public final Setting<Integer> mobAvoidanceRadius = new Setting<>(8);

    /**
     * When running a goto towards a container block (chest, ender chest, furnace, etc),
     * right click and open it once you arrive.
     */
    public final Setting<Boolean> rightClickContainerOnArrival = new Setting<>(true);

    /**
     * When running a goto towards a nether portal block, walk all the way into the portal
     * instead of stopping one block before.
     */
    public final Setting<Boolean> enterPortal = new Setting<>(true);

    /**
     * Don't repropagate cost improvements below 0.01 ticks. They're all just floating point inaccuracies,
     * and there's no point.
     */
    public final Setting<Boolean> minimumImprovementRepropagation = new Setting<>(true);

    /**
     * After calculating a path (potentially through cached chunks), artificially cut it off to just the part that is
     * entirely within currently loaded chunks. Improves path safety because cached chunks are heavily simplified.
     * <p>
     * This is much safer to leave off now, and makes pathing more efficient. More explanation in the issue.
     *
     * @see <a href="https://github.com/cabaletta/baritone/issues/114">Issue #114</a>
     */
    public final Setting<Boolean> cutoffAtLoadBoundary = new Setting<>(false);

    /**
     * If a movement's cost increases by more than this amount between calculation and execution (due to changes
     * in the environment / world), cancel and recalculate
     */
    public final Setting<Double> maxCostIncrease = new Setting<>(10D);

    /**
     * Stop 5 movements before anything that made the path COST_INF.
     * For example, if lava has spread across the path, don't walk right up to it then recalculate, it might
     * still be spreading lol
     */
    public final Setting<Integer> costVerificationLookahead = new Setting<>(5);

    /**
     * Static cutoff factor. 0.9 means cut off the last 10% of all paths, regardless of chunk load state
     */
    public final Setting<Double> pathCutoffFactor = new Setting<>(0.9);

    /**
     * Only apply static cutoff for paths of at least this length (in terms of number of movements)
     */
    public final Setting<Integer> pathCutoffMinimumLength = new Setting<>(30);

    /**
     * Start planning the next path once the remaining movements tick estimates sum up to less than this value
     */
    public final Setting<Integer> planningTickLookahead = new Setting<>(150);

    /**
     * Default size of the Long2ObjectOpenHashMap used in pathing
     */
    public final Setting<Integer> pathingMapDefaultSize = new Setting<>(1024);

    /**
     * Load factor coefficient for the Long2ObjectOpenHashMap used in pathing
     * <p>
     * Decrease for faster map operations, but higher memory usage
     */
    public final Setting<Float> pathingMapLoadFactor = new Setting<>(0.75f);

    /**
     * How far are you allowed to fall onto solid ground (without a water bucket)?
     * 3 won't deal any damage. But if you just want to get down the mountain quickly and you have
     * Feather Falling IV, you might set it a bit higher, like 4 or 5.
     */
    public final Setting<Integer> maxFallHeightNoWater = new Setting<>(3);

    /**
     * How far are you allowed to fall onto solid ground (with a water bucket)?
     * It's not that reliable, so I've set it below what would kill an unarmored player (23)
     */
    public final Setting<Integer> maxFallHeightBucket = new Setting<>(20);

    /**
     * Is it okay to sprint through a descend followed by a diagonal?
     * The player overshoots the landing, but not enough to fall off. And the diagonal ensures that there isn't
     * lava or anything that's !canWalkInto in that space, so it's technically safe, just a little sketchy.
     * <p>
     * Note: this is *not* related to the allowDiagonalDescend setting, that is a completely different thing.
     */
    public final Setting<Boolean> allowOvershootDiagonalDescend = new Setting<>(true);

    /**
     * If your goal is a GoalBlock in an unloaded chunk, assume it's far enough away that the Y coord
     * doesn't matter yet, and replace it with a GoalXZ to the same place before calculating a path.
     * Once a segment ends within chunk load range of the GoalBlock, it will go back to normal behavior
     * of considering the Y coord. The reasoning is that if your X and Z are 10,000 blocks away,
     * your Y coordinate's accuracy doesn't matter at all until you get much much closer.
     */
    public final Setting<Boolean> simplifyUnloadedYCoord = new Setting<>(true);

    /**
     * Whenever a block changes, repack the whole chunk that it's in
     */
    public final Setting<Boolean> repackOnAnyBlockChange = new Setting<>(true);

    /**
     * If a movement takes this many ticks more than its initial cost estimate, cancel it
     */
    public final Setting<Integer> movementTimeoutTicks = new Setting<>(100);

    /**
     * Pathing ends after this amount of time, but only if a path has been found
     * <p>
     * If no valid path (length above the minimum) has been found, pathing continues up until the failure timeout
     */
    public final Setting<Long> primaryTimeoutMS = new Setting<>(500L);

    /**
     * Pathing can never take longer than this, even if that means failing to find any path at all
     */
    public final Setting<Long> failureTimeoutMS = new Setting<>(2000L);

    /**
     * Planning ahead while executing a segment ends after this amount of time, but only if a path has been found
     * <p>
     * If no valid path (length above the minimum) has been found, pathing continues up until the failure timeout
     */
    public final Setting<Long> planAheadPrimaryTimeoutMS = new Setting<>(4000L);

    /**
     * Planning ahead while executing a segment can never take longer than this, even if that means failing to find any path at all
     */
    public final Setting<Long> planAheadFailureTimeoutMS = new Setting<>(5000L);

    /**
     * For debugging, consider nodes much much slower
     */
    public final Setting<Boolean> slowPath = new Setting<>(false);

    /**
     * Milliseconds between each node
     */
    public final Setting<Long> slowPathTimeDelayMS = new Setting<>(100L);

    /**
     * The alternative timeout number when slowPath is on
     */
    public final Setting<Long> slowPathTimeoutMS = new Setting<>(40000L);


    /**
     * allows baritone to save bed waypoints when interacting with beds
     */
    public final Setting<Boolean> doBedWaypoints = new Setting<>(true);

    /**
     * allows baritone to save death waypoints
     */
    public final Setting<Boolean> doDeathWaypoints = new Setting<>(true);

    /**
     * The big one. Download all chunks in simplified 2-bit format and save them for better very-long-distance pathing.
     */
    public final Setting<Boolean> chunkCaching = new Setting<>(true);

    /**
     * On save, delete from RAM any cached regions that are more than 1024 blocks away from the player
     * <p>
     * Temporarily disabled
     * <p>
     * Temporarily reenabled
     *
     * @see <a href="https://github.com/cabaletta/baritone/issues/248">Issue #248</a>
     */
    public final Setting<Boolean> pruneRegionsFromRAM = new Setting<>(true);

    /**
     * The chunk packer queue can never grow to larger than this, if it does, the oldest chunks are discarded
     * <p>
     * The newest chunks are kept, so that if you're moving in a straight line quickly then stop, your immediate render distance is still included
     */
    public final Setting<Integer> chunkPackerQueueMaxSize = new Setting<>(2000);

    /**
     * Fill in blocks behind you
     */
    public final Setting<Boolean> backfill = new Setting<>(false);

    /**
     * Shows popup message in the upper right corner, similarly to when you make an advancement
     */
    public final Setting<Boolean> logAsToast = new Setting<>(false);

    /**
     * The time of how long the message in the pop-up will display
     * <p>
     * If below 1000L (1sec), it's better to disable this
     */
    public final Setting<Long> toastTimer = new Setting<>(5000L);

    /**
     * Print all the debug messages to chat
     */
    public final Setting<Boolean> chatDebug = new Setting<>(false);

    /**
     * Allow chat based control of Baritone. Most likely should be disabled when Baritone is imported for use in
     * something else
     */
    public final Setting<Boolean> chatControl = new Setting<>(true);

    /**
     * Some clients like Impact try to force chatControl to off, so here's a second setting to do it anyway
     */
    public final Setting<Boolean> chatControlAnyway = new Setting<>(false);

    /**
     * Render the path
     */
    public final Setting<Boolean> renderPath = new Setting<>(true);

    /**
     * Render the path as a line instead of a frickin thingy
     */
    public final Setting<Boolean> renderPathAsLine = new Setting<>(false);

    /**
     * Render the goal
     */
    public final Setting<Boolean> renderGoal = new Setting<>(true);

    /**
     * Render the goal as a sick animated thingy instead of just a box
     * (also controls animation of GoalXZ if {@link #renderGoalXZBeacon} is enabled)
     */
    public final Setting<Boolean> renderGoalAnimated = new Setting<>(true);

    /**
     * Render selection boxes
     */
    public final Setting<Boolean> renderSelectionBoxes = new Setting<>(true);

    /**
     * Ignore depth when rendering the goal
     */
    public final Setting<Boolean> renderGoalIgnoreDepth = new Setting<>(true);

    /**
     * Renders X/Z type Goals with the vanilla beacon beam effect. Combining this with
     * {@link #renderGoalIgnoreDepth} will cause strange render clipping.
     */
    public final Setting<Boolean> renderGoalXZBeacon = new Setting<>(false);

    /**
     * Ignore depth when rendering the selection boxes (to break, to place, to walk into)
     */
    public final Setting<Boolean> renderSelectionBoxesIgnoreDepth = new Setting<>(true);

    /**
     * Ignore depth when rendering the path
     */
    public final Setting<Boolean> renderPathIgnoreDepth = new Setting<>(true);

    /**
     * Line width of the path when rendered, in pixels
     */
    public final Setting<Float> pathRenderLineWidthPixels = new Setting<>(5F);

    /**
     * Line width of the goal when rendered, in pixels
     */
    public final Setting<Float> goalRenderLineWidthPixels = new Setting<>(3F);

    /**
     * Start fading out the path at 20 movements ahead, and stop rendering it entirely 30 movements ahead.
     * Improves FPS.
     */
    public final Setting<Boolean> fadePath = new Setting<>(false);

    /**
     * Move without having to force the client-sided rotations
     */
    public final Setting<Boolean> freeLook = new Setting<>(true);

    /**
     * Break and place blocks without having to force the client-sided rotations. Requires {@link #freeLook}.
     */
    public final Setting<Boolean> blockFreeLook = new Setting<>(false);

    /**
     * Automatically elytra fly without having to force the client-sided rotations.
     */
    public final Setting<Boolean> elytraFreeLook = new Setting<>(true);

    /**
     * Forces the client-sided yaw rotation to an average of the last {@link #smoothLookTicks} of server-sided rotations.
     */
    public final Setting<Boolean> smoothLook = new Setting<>(false);

    /**
     * Same as {@link #smoothLook} but for elytra flying.
     */
    public final Setting<Boolean> elytraSmoothLook = new Setting<>(false);

    /**
     * The number of ticks to average across for {@link #smoothLook};
     */
    public final Setting<Integer> smoothLookTicks = new Setting<>(5);

    /**
     * Whether to humanize camera turning for look targets that opt into easing (combat aiming, and
     * pathing when {@code true}). When off, Baritone hard-sets the desired rotation every tick —
     * a one-tick snap that reads as a bot, especially the hard flicks combat does onto a target.
     *
     * <p>This is a <b>per-target</b> layer separate from {@link #smoothLook}: block-break/block-place
     * and other raytrace-precise interactions never ease (they need pixel-exact aim via
     * {@code objectMouseOver}); only targets flagged {@code ease} are run through the easing below.
     *
     * <p>Easing rules (per axis, shortest-angle):
     * <ul>
     *   <li><b>Track</b> (small delta, &lt; {@link #cameraFlickThreshold}): step = delta *
     *       {@link #cameraSmallDeltaRate}. A fraction of the gap closes each tick — a human-like
     *       ease rather than a full snap on small adjustments.</li>
     *   <li><b>Flick</b> (large delta): step = sign(delta) * {@link #cameraMaxTurnRate}. A capped
     *       fast turn — humans do flick on wide angles, but not instantaneously.</li>
     *   <li><b>Arrival</b>: once the remaining gap is &lt; {@link #cameraMinTurnRate}, snap to the
     *       desired rotation so it doesn't hover forever a fraction of a degree off.</li>
     * </ul>
     * Pitch eases with the same rules but a tighter max rate (half of yaw's flick rate), since
     * vertical flick is more visibly robotic. Elytra is never eased.
     *
     * <p>Defaults off — opt in only when you want the humanized look.
     */
    public final Setting<Boolean> humanizeCamera = new Setting<>(false);

    /**
     * Maximum degrees of yaw the eased camera will turn in a single tick during a flick (large
     * delta). Caps how fast a wide-angle turn completes. Only used when {@link #humanizeCamera}
     * is on.
     */
    public final Setting<Double> cameraMaxTurnRate = new Setting<>(60.0);

    /**
     * Below this remaining yaw/pitch gap (degrees), the eased camera snaps directly to the desired
     * rotation instead of creeping the last fraction of a degree. Prevents a permanent tiny offset
     * and the "never quite looking at it" wobble. Only used when {@link #humanizeCamera} is on.
     */
    public final Setting<Double> cameraMinTurnRate = new Setting<>(2.0);

    /**
     * Fraction of the remaining yaw/pitch gap closed per tick during a track (small delta below
     * {@link #cameraFlickThreshold}). Lower = slower, smoother creep; 1.0 = snap. Only used when
     * {@link #humanizeCamera} is on.
     */
    public final Setting<Double> cameraSmallDeltaRate = new Setting<>(0.3);

    /**
     * Yaw delta (degrees) above which the eased camera flicks at {@link #cameraMaxTurnRate} instead
     * of tracking at {@link #cameraSmallDeltaRate}. The small-angle/large-angle boundary. Only used
     * when {@link #humanizeCamera} is on.
     */
    public final Setting<Double> cameraFlickThreshold = new Setting<>(40.0);

    /**
     * When true, the player will remain with its existing look direction as often as possible.
     * Although, in some cases this can get it stuck, hence this setting to disable that behavior.
     */
    public final Setting<Boolean> remainWithExistingLookDirection = new Setting<>(true);

    /**
     * Will cause some minor behavioral differences to ensure that Baritone works on anticheats.
     * <p>
     * At the moment this will silently set the player's rotations when using freeLook so you're not sprinting in
     * directions other than forward, which is picken up by more "advanced" anticheats like AAC, but not NCP.
     */
    public final Setting<Boolean> antiCheatCompatibility = new Setting<>(true);

    /**
     * Exclusively use cached chunks for pathing
     * <p>
     * Never turn this on
     */
    public final Setting<Boolean> pathThroughCachedOnly = new Setting<>(false);

    /**
     * Continue sprinting while in water
     */
    public final Setting<Boolean> sprintInWater = new Setting<>(true);

    /**
     * When GetToBlockProcess or MineProcess fails to calculate a path, instead of just giving up, mark the closest instance
     * of that block as "unreachable" and go towards the next closest. GetToBlock expands this search to the whole "vein"; MineProcess does not.
     * This is because MineProcess finds individual impossible blocks (like one block in a vein that has gravel on top then lava, so it can't break)
     * Whereas GetToBlock should blacklist the whole "vein" if it can't get to any of them.
     */
    public final Setting<Boolean> blacklistClosestOnFailure = new Setting<>(true);

    /**
     * 😎 Render cached chunks as semitransparent. Doesn't work with OptiFine 😭 Rarely randomly crashes, see <a href="https://github.com/cabaletta/baritone/issues/327">this issue</a>.
     * <p>
     * Can be very useful on servers with low render distance. After enabling, you may need to reload the world in order for it to have an effect
     * (e.g. disconnect and reconnect, enter then exit the nether, die and respawn, etc). This may literally kill your FPS and CPU because
     * every chunk gets recompiled twice as much as normal, since the cached version comes into range, then the normal one comes from the server for real.
     * <p>
     * Note that flowing water is cached as AVOID, which is rendered as lava. As you get closer, you may therefore see lava falls being replaced with water falls.
     * <p>
     * SOLID is rendered as stone in the overworld, netherrack in the nether, and end stone in the end
     */
    public final Setting<Boolean> renderCachedChunks = new Setting<>(false);

    /**
     * 0.0f = not visible, fully transparent (instead of setting this to 0, turn off renderCachedChunks)
     * 1.0f = fully opaque
     */
    public final Setting<Float> cachedChunksOpacity = new Setting<>(0.5f);

    /**
     * Whether or not to allow you to run Baritone commands with the prefix
     */
    public final Setting<Boolean> prefixControl = new Setting<>(true);

    /**
     * The command prefix for chat control
     */
    public final Setting<String> prefix = new Setting<>("#");

    /**
     * Use a short Baritone prefix [B] instead of [Baritone] when logging to chat
     */
    public final Setting<Boolean> shortBaritonePrefix = new Setting<>(false);

    /**
     * Use a modern message tag instead of a prefix when logging to chat
     */
    public final Setting<Boolean> useMessageTag = new Setting<>(false);

    /**
     * Echo commands to chat when they are run
     */
    public final Setting<Boolean> echoCommands = new Setting<>(true);

    /**
     * Censor coordinates in goals and block positions
     */
    public final Setting<Boolean> censorCoordinates = new Setting<>(false);

    /**
     * Censor arguments to ran commands, to hide, for example, coordinates to #goal
     */
    public final Setting<Boolean> censorRanCommands = new Setting<>(false);

    /**
     * Stop using tools just before they are going to break.
     */
    public final Setting<Boolean> itemSaver = new Setting<>(false);

    /**
     * Durability to leave on the tool when using itemSaver
     */
    public final Setting<Integer> itemSaverThreshold = new Setting<>(10);

    /**
     * Always prefer silk touch tools over regular tools. This will not sacrifice speed, but it will always prefer silk
     * touch tools over other tools of the same speed. This includes always choosing ANY silk touch tool over your hand.
     */
    public final Setting<Boolean> preferSilkTouch = new Setting<>(false);

    /**
     * Don't stop walking forward when you need to break blocks in your way
     */
    public final Setting<Boolean> walkWhileBreaking = new Setting<>(true);

    /**
     * When a new segment is calculated that doesn't overlap with the current one, but simply begins where the current segment ends,
     * splice it on and make a longer combined path. If this setting is off, any planned segment will not be spliced and will instead
     * be the "next path" in PathingBehavior, and will only start after this one ends. Turning this off hurts planning ahead,
     * because the next segment will exist even if it's very short.
     *
     * @see #planningTickLookahead
     */
    public final Setting<Boolean> splicePath = new Setting<>(true);

    /**
     * If we are more than 300 movements into the current path, discard the oldest segments, as they are no longer useful
     */
    public final Setting<Integer> maxPathHistoryLength = new Setting<>(300);

    /**
     * If the current path is too long, cut off this many movements from the beginning.
     */
    public final Setting<Integer> pathHistoryCutoffAmount = new Setting<>(50);

    /**
     * Rescan for the goal once every 5 ticks.
     * Set to 0 to disable.
     */
    public final Setting<Integer> mineGoalUpdateInterval = new Setting<>(5);

    /**
     * When true, {@code #mine} on an ore automatically includes its siblings:
     * {@code iron_ore} also mines {@code deepslate_iron_ore}, {@code gold_ore} also
     * {@code nether_gold_ore} (and deepslate, plus the reverse directions). Off restores
     * the literal single-block behavior.
     */
    public final Setting<Boolean> mineFamilyExpansion = new Setting<>(true);

    /**
     * When true, {@code #mine} paths over dropped matching items before declaring the job
     * done, so the bot doesn't move on with loot still on the ground. Items in lava or on
     * fire are skipped (they're usually gone anyway), and collection is abandoned with a
     * log message when the inventory is full or {@link #mineCollectTimeoutSeconds} elapses.
     */
    public final Setting<Boolean> mineCollectDrops = new Setting<>(false);

    /**
     * How long {@code #mine} spends trying to collect dropped items before giving up and
     * moving on (seconds).
     */
    public final Setting<Integer> mineCollectTimeoutSeconds = new Setting<>(10);

    /**
     * Hold the FTB Ultimine keybind while Baritone breaks blocks, so one break veins the
     * whole deposit (requires FTB Ultimine installed client-side AND server-side — the mod
     * validates activations on the server). No hard dependency: when the keybind isn't
     * found, this silently does nothing. Note Ultimine multiplies durability/hunger cost
     * per vein, and the server enforces its own cooldown between activations.
     */
    public final Setting<Boolean> useFtbUltimine = new Setting<>(false);

    /**
     * After finding this many instances of the target block in the cache, it will stop expanding outward the chunk search.
     */
    public final Setting<Integer> maxCachedWorldScanCount = new Setting<>(10);

    /**
     * Mine will not scan for or remember more than this many target locations.
     * Note that the number of locations retrieved from cache is additionaly
     * limited by {@link #maxCachedWorldScanCount}.
     */
    public final Setting<Integer> mineMaxOreLocationsCount = new Setting<>(64);

    /**
     * Sets the minimum y level whilst mining - set to 0 to turn off.
     * if world has negative y values, subtract the min world height to get the value to put here
     */
    public final Setting<Integer> minYLevelWhileMining = new Setting<>(0);

    /**
     * Sets the maximum y level to mine ores at.
     */
    public final Setting<Integer> maxYLevelWhileMining = new Setting<>(2031);

    /**
     * This will only allow baritone to mine exposed ores, can be used to stop ore obfuscators on servers that use them.
     */
    public final Setting<Boolean> allowOnlyExposedOres = new Setting<>(false);

    /**
     * When allowOnlyExposedOres is enabled this is the distance around to search.
     * <p>
     * It is recommended to keep this value low, as it dramatically increases calculation times.
     */
    public final Setting<Integer> allowOnlyExposedOresDistance = new Setting<>(1);

    /**
     * When GetToBlock or non-legit Mine doesn't know any locations for the desired block, explore randomly instead of giving up.
     */
    public final Setting<Boolean> exploreForBlocks = new Setting<>(true);

    /**
     * While exploring the world, offset the closest unloaded chunk by this much in both axes.
     * <p>
     * This can result in more efficient loading, if you set this to the render distance.
     */
    public final Setting<Integer> worldExploringChunkOffset = new Setting<>(0);

    /**
     * Take the 10 closest chunks, even if they aren't strictly tied for distance metric from origin.
     */
    public final Setting<Integer> exploreChunkSetMinimumSize = new Setting<>(10);

    /**
     * Attempt to maintain Y coordinate while exploring
     * <p>
     * -1 to disable
     */
    public final Setting<Integer> exploreMaintainY = new Setting<>(64);

    /**
     * Replant normal Crops while farming and leave cactus and sugarcane to regrow
     */
    public final Setting<Boolean> replantCrops = new Setting<>(true);

    /**
     * Replant nether wart while farming. This setting only has an effect when replantCrops is also enabled
     */
    public final Setting<Boolean> replantNetherWart = new Setting<>(false);

    /**
     * Farming will scan for at most this many blocks.
     */
    public final Setting<Integer> farmMaxScanSize = new Setting<>(256);

    /**
     * When the cache scan gives less blocks than the maximum threshold (but still above zero), scan the main world too.
     * <p>
     * Only if you have a beefy CPU and automatically mine blocks that are in cache
     */
    public final Setting<Boolean> extendCacheOnThreshold = new Setting<>(false);

    /**
     * Don't consider the next layer in builder until the current one is done
     */
    public final Setting<Boolean> buildInLayers = new Setting<>(false);

    /**
     * false = build from bottom to top
     * <p>
     * true = build from top to bottom
     */
    public final Setting<Boolean> layerOrder = new Setting<>(false);

    /**
     * How high should the individual layers be?
     */
    public final Setting<Integer> layerHeight = new Setting<>(1);

    /**
     * Start building the schematic at a specific layer.
     * Can help on larger builds when schematic wants to break things its already built
     */
    public final Setting<Integer> startAtLayer = new Setting<>(0);

    /**
     * If a layer is unable to be constructed, just skip it.
     */
    public final Setting<Boolean> skipFailedLayers = new Setting<>(false);

    /**
     * Only build the selected part of schematics
     */
    public final Setting<Boolean> buildOnlySelection = new Setting<>(false);

    /**
     * How far to move before repeating the build. 0 to disable repeating on a certain axis, 0,0,0 to disable entirely
     */
    public final Setting<Vec3i> buildRepeat = new Setting<>(new Vec3i(0, 0, 0));

    /**
     * How many times to buildrepeat. -1 for infinite.
     */
    public final Setting<Integer> buildRepeatCount = new Setting<>(-1);

    /**
     * Don't notify schematics that they are moved.
     * e.g. replacing will replace the same spots for every repetition
     * Mainly for backward compatibility.
     */
    public final Setting<Boolean> buildRepeatSneaky = new Setting<>(true);

    /**
     * Allow standing above a block while mining it, in BuilderProcess
     * <p>
     * Experimental
     */
    public final Setting<Boolean> breakFromAbove = new Setting<>(false);

    /**
     * As well as breaking from above, set a goal to up and to the side of all blocks to break.
     * <p>
     * Never turn this on without also turning on breakFromAbove.
     */
    public final Setting<Boolean> goalBreakFromAbove = new Setting<>(false);

    /**
     * Build in map art mode, which makes baritone only care about the top block in each column
     */
    public final Setting<Boolean> mapArtMode = new Setting<>(false);

    /**
     * Override builder's behavior to not attempt to correct blocks that are currently water
     */
    public final Setting<Boolean> okIfWater = new Setting<>(false);

    /**
     * The set of incorrect blocks can never grow beyond this size
     */
    public final Setting<Integer> incorrectSize = new Setting<>(100);

    /**
     * Multiply the cost of breaking a block that's correct in the builder's schematic by this coefficient
     */
    public final Setting<Double> breakCorrectBlockPenaltyMultiplier = new Setting<>(10d);

    /**
     * Multiply the cost of placing a block that's incorrect in the builder's schematic by this coefficient
     */
    public final Setting<Double> placeIncorrectBlockPenaltyMultiplier = new Setting<>(2d);

    /**
     * When this setting is true, build a schematic with the highest X coordinate being the origin, instead of the lowest
     */
    public final Setting<Boolean> schematicOrientationX = new Setting<>(false);

    /**
     * When this setting is true, build a schematic with the highest Y coordinate being the origin, instead of the lowest
     */
    public final Setting<Boolean> schematicOrientationY = new Setting<>(false);

    /**
     * When this setting is true, build a schematic with the highest Z coordinate being the origin, instead of the lowest
     */
    public final Setting<Boolean> schematicOrientationZ = new Setting<>(false);

    /**
     * Rotates the schematic before building it.
     * Possible values are
     * <ul>
     *  <li> NONE - No rotation </li>
     *  <li> CLOCKWISE_90 - Rotate 90° clockwise </li>
     *  <li> CLOCKWISE_180 - Rotate 180° clockwise </li>
     *  <li> COUNTERCLOCKWISE_90 - Rotate 270° clockwise </li>
     * </ul>
     */
    public final Setting<Rotation> buildSchematicRotation = new Setting<>(Rotation.NONE);

    /**
     * Mirrors the schematic before building it.
     * Possible values are
     * <ul>
     *  <li> FRONT_BACK - mirror the schematic along its local x axis </li>
     *  <li> LEFT_RIGHT - mirror the schematic along its local z axis </li>
     * </ul>
     */
    public final Setting<Mirror> buildSchematicMirror = new Setting<>(Mirror.NONE);

    /**
     * The fallback used by the build command when no extension is specified. This may be useful if schematics of a
     * particular format are used often, and the user does not wish to have to specify the extension with every usage.
     */
    public final Setting<String> schematicFallbackExtension = new Setting<>("schematic");

    /**
     * Distance to scan every tick for updates. Expanding this beyond player reach distance (i.e. setting it to 6 or above)
     * is only necessary in very large schematics where rescanning the whole thing is costly.
     */
    public final Setting<Integer> builderTickScanRadius = new Setting<>(5);

    /**
     * While mining, should it also consider dropped items of the correct type as a pathing destination (as well as ore blocks)?
     */
    public final Setting<Boolean> mineScanDroppedItems = new Setting<>(true);

    /**
     * While mining, wait this number of milliseconds after mining an ore to see if it will drop an item
     * instead of immediately going onto the next one
     * <p>
     * Thanks Louca
     */
    public final Setting<Long> mineDropLoiterDurationMSThanksLouca = new Setting<>(250L);

    /**
     * Trim incorrect positions too far away, helps performance but hurts reliability in very large schematics
     */
    public final Setting<Boolean> distanceTrim = new Setting<>(true);

    /**
     * Cancel the current path if the goal has changed, and the path originally ended in the goal but doesn't anymore.
     * <p>
     * Currently only runs when either MineBehavior or FollowBehavior is active.
     * <p>
     * For example, if Baritone is doing "mine iron_ore", the instant it breaks the ore (and it becomes air), that location
     * is no longer a goal. This means that if this setting is true, it will stop there. If this setting were off, it would
     * continue with its path, and walk into that location. The tradeoff is if this setting is true, it mines ores much faster
     * since it doesn't waste any time getting into locations that no longer contain ores, but on the other hand, it misses
     * some drops, and continues on without ever picking them up.
     * <p>
     * Also on cosmic prisons this should be set to true since you don't actually mine the ore it just gets replaced with stone.
     */
    public final Setting<Boolean> cancelOnGoalInvalidation = new Setting<>(true);

    /**
     * The "axis" command (aka GoalAxis) will go to a axis, or diagonal axis, at this Y level.
     */
    public final Setting<Integer> axisHeight = new Setting<>(120);

    /**
     * Disconnect from the server upon arriving at your goal
     */
    public final Setting<Boolean> disconnectOnArrival = new Setting<>(false);

    /**
     * Disallow MineBehavior from using X-Ray to see where the ores are. Turn this option on to force it to mine "legit"
     * where it will only mine an ore once it can actually see it, so it won't do or know anything that a normal player
     * couldn't. If you don't want it to look like you're X-Raying, turn this on
     * This will always explore, regardless of exploreForBlocks
     */
    public final Setting<Boolean> legitMine = new Setting<>(false);

    /**
     * What Y level to go to for legit strip mining
     */
    public final Setting<Integer> legitMineYLevel = new Setting<>(-59);

    /**
     * Magically see ores that are separated diagonally from existing ores. Basically like mining around the ores that it finds
     * in case there's one there touching it diagonally, except it checks it un-legit-ly without having the mine blocks to see it.
     * You can decide whether this looks plausible or not.
     * <p>
     * This is disabled because it results in some weird behavior. For example, it can """see""" the top block of a vein of iron_ore
     * through a lava lake. This isn't an issue normally since it won't consider anything touching lava, so it just ignores it.
     * However, this setting expands that and allows it to see the entire vein so it'll mine under the lava lake to get the iron that
     * it can reach without mining blocks adjacent to lava. This really defeats the purpose of legitMine since a player could never
     * do that lol, so thats one reason why its disabled
     */
    public final Setting<Boolean> legitMineIncludeDiagonals = new Setting<>(false);

    /**
     * When mining block of a certain type, try to mine two at once instead of one.
     * If the block above is also a goal block, set GoalBlock instead of GoalTwoBlocks
     * If the block below is also a goal block, set GoalBlock to the position one down instead of GoalTwoBlocks
     */
    public final Setting<Boolean> forceInternalMining = new Setting<>(true);

    /**
     * Modification to the previous setting, only has effect if forceInternalMining is true
     * If true, only apply the previous setting if the block adjacent to the goal isn't air.
     */
    public final Setting<Boolean> internalMiningAirException = new Setting<>(true);

    /**
     * The actual GoalNear is set this distance away from the entity you're following
     * <p>
     * For example, set followOffsetDistance to 5 and followRadius to 0 to always stay precisely 5 blocks north of your follow target.
     */
    public final Setting<Double> followOffsetDistance = new Setting<>(0D);

    /**
     * The actual GoalNear is set in this direction from the entity you're following. This value is in degrees.
     */
    public final Setting<Float> followOffsetDirection = new Setting<>(0F);

    /**
     * The radius (for the GoalNear) of how close to your target position you actually have to be
     */
    public final Setting<Integer> followRadius = new Setting<>(3);

    /**
     * The maximum distance to the entity you're following
     */
    public final Setting<Integer> followTargetMaxDistance = new Setting<>(0);

    /**
     * Turn this on if your exploration filter is enormous, you don't want it to check if it's done,
     * and you are just fine with it just hanging on completion
     */
    public final Setting<Boolean> disableCompletionCheck = new Setting<>(false);

    /**
     * Cached chunks (regardless of if they're in RAM or saved to disk) expire and are deleted after this number of seconds
     * -1 to disable
     * <p>
     * I would highly suggest leaving this setting disabled (-1).
     * <p>
     * The only valid reason I can think of enable this setting is if you are extremely low on disk space and you play on multiplayer,
     * and can't take (average) 300kb saved for every 512x512 area. (note that more complicated terrain is less compressible and will take more space)
     * <p>
     * However, simply discarding old chunks because they are old is inadvisable. Baritone is extremely good at correcting
     * itself and its paths as it learns new information, as new chunks load. There is no scenario in which having an
     * incorrect cache can cause Baritone to get stuck, take damage, or perform any action it wouldn't otherwise, everything
     * is rechecked once the real chunk is in range.
     * <p>
     * Having a robust cache greatly improves long distance pathfinding, as it's able to go around large scale obstacles
     * before they're in render distance. In fact, when the chunkCaching setting is disabled and Baritone starts anew
     * every time, or when you enter a completely new and very complicated area, it backtracks far more often because it
     * has to build up that cache from scratch. But after it's gone through an area just once, the next time will have zero
     * backtracking, since the entire area is now known and cached.
     */
    public final Setting<Long> cachedChunksExpirySeconds = new Setting<>(-1L);

    /**
     * The function that is called when Baritone will log to chat. This function can be added to
     * via {@link Consumer#andThen(Consumer)} or it can completely be overriden via setting
     * {@link Setting#value};
     */
    @JavaOnly
    public final Setting<Consumer<Component>> logger = new Setting<>((msg) -> {
        try {
            final GuiMessageTag tag = useMessageTag.value ? Helper.MESSAGE_TAG : null;
            Minecraft.getInstance().gui.getChat().addMessage(msg, null, tag);
        } catch (Throwable t) {
            LOGGER.warn("Failed to log message to chat: " + msg.getString(), t);
        }
    });

    /**
     * The function that is called when Baritone will send a desktop notification. This function can be added to
     * via {@link Consumer#andThen(Consumer)} or it can completely be overriden via setting
     * {@link Setting#value};
     */
    @JavaOnly
    public final Setting<BiConsumer<String, Boolean>> notifier = new Setting<>(NotificationHelper::notify);

    /**
     * The function that is called when Baritone will show a toast. This function can be added to
     * via {@link Consumer#andThen(Consumer)} or it can completely be overriden via setting
     * {@link Setting#value};
     */
    @JavaOnly
    public final Setting<BiConsumer<Component, Component>> toaster = new Setting<>(BaritoneToast::addOrUpdate);

    /**
     * Print out ALL command exceptions as a stack trace to stdout, even simple syntax errors
     */
    public final Setting<Boolean> verboseCommandExceptions = new Setting<>(false);

    /**
     * The size of the box that is rendered when the current goal is a GoalYLevel
     */
    public final Setting<Double> yLevelBoxSize = new Setting<>(15D);

    /**
     * The color of the current path
     */
    public final Setting<Color> colorCurrentPath = new Setting<>(Color.RED);

    /**
     * The color of the next path
     */
    public final Setting<Color> colorNextPath = new Setting<>(Color.MAGENTA);

    /**
     * The color of the blocks to break
     */
    public final Setting<Color> colorBlocksToBreak = new Setting<>(Color.RED);

    /**
     * The color of the blocks to place
     */
    public final Setting<Color> colorBlocksToPlace = new Setting<>(Color.GREEN);

    /**
     * The color of the blocks to walk into
     */
    public final Setting<Color> colorBlocksToWalkInto = new Setting<>(Color.MAGENTA);

    /**
     * The color of the best path so far
     */
    public final Setting<Color> colorBestPathSoFar = new Setting<>(Color.BLUE);

    /**
     * The color of the path to the most recent considered node
     */
    public final Setting<Color> colorMostRecentConsidered = new Setting<>(Color.CYAN);

    /**
     * The color of the goal box
     */
    public final Setting<Color> colorGoalBox = new Setting<>(Color.GREEN);

    /**
     * The color of the goal box when it's inverted
     */
    public final Setting<Color> colorInvertedGoalBox = new Setting<>(Color.RED);

    /**
     * The color of all selections
     */
    public final Setting<Color> colorSelection = new Setting<>(Color.CYAN);

    /**
     * The color of the selection pos 1
     */
    public final Setting<Color> colorSelectionPos1 = new Setting<>(Color.BLACK);

    /**
     * The color of the selection pos 2
     */
    public final Setting<Color> colorSelectionPos2 = new Setting<>(Color.ORANGE);

    /**
     * The opacity of the selection. 0 is completely transparent, 1 is completely opaque
     */
    public final Setting<Float> selectionOpacity = new Setting<>(.5f);

    /**
     * Line width of the goal when rendered, in pixels
     */
    public final Setting<Float> selectionLineWidth = new Setting<>(2F);

    /**
     * Render selections
     */
    public final Setting<Boolean> renderSelection = new Setting<>(true);

    /**
     * Ignore depth when rendering selections
     */
    public final Setting<Boolean> renderSelectionIgnoreDepth = new Setting<>(true);

    /**
     * Render selection corners
     */
    public final Setting<Boolean> renderSelectionCorners = new Setting<>(true);

    /**
     * Use sword to mine.
     */
    public final Setting<Boolean> useSwordToMine = new Setting<>(true);

    /**
     * Desktop notifications
     */
    public final Setting<Boolean> desktopNotifications = new Setting<>(false);

    /**
     * Desktop notification on path complete
     */
    public final Setting<Boolean> notificationOnPathComplete = new Setting<>(true);

    /**
     * Desktop notification on farm fail
     */
    public final Setting<Boolean> notificationOnFarmFail = new Setting<>(true);

    /**
     * Desktop notification on build finished
     */
    public final Setting<Boolean> notificationOnBuildFinished = new Setting<>(true);

    /**
     * Desktop notification on explore finished
     */
    public final Setting<Boolean> notificationOnExploreFinished = new Setting<>(true);

    /**
     * Desktop notification on mine fail
     */
    public final Setting<Boolean> notificationOnMineFail = new Setting<>(true);

    /**
     * The number of ticks of elytra movement to simulate while firework boost is not active. Higher values are
     * computationally more expensive.
     */
    public final Setting<Integer> elytraSimulationTicks = new Setting<>(20);

    /**
     * The maximum allowed deviation in pitch from a direct line-of-sight to the flight target. Higher values are
     * computationally more expensive.
     */
    public final Setting<Integer> elytraPitchRange = new Setting<>(25);

    /**
     * The minimum speed that the player can drop to (in blocks/tick) before a firework is automatically deployed.
     */
    public final Setting<Double> elytraFireworkSpeed = new Setting<>(1.2);

    /**
     * The delay after the player's position is set-back by the server that a firework may be automatically deployed.
     * Value is in ticks.
     */
    public final Setting<Integer> elytraFireworkSetbackUseDelay = new Setting<>(15);

    /**
     * The minimum padding value that is added to the player's hitbox when considering which point to fly to on the
     * path. High values can result in points not being considered which are otherwise safe to fly to. Low values can
     * result in flight paths which are extremely tight, and there's the possibility of crashing due to getting too low
     * to the ground.
     */
    public final Setting<Double> elytraMinimumAvoidance = new Setting<>(0.2);

    /**
     * If enabled, avoids using fireworks when descending along the flight path.
     */
    public final Setting<Boolean> elytraConserveFireworks = new Setting<>(false);

    /**
     * Renders the raytraces that are performed by the elytra fly calculation.
     */
    public final Setting<Boolean> elytraRenderRaytraces = new Setting<>(false);

    /**
     * Renders the raytraces that are used in the hitbox part of the elytra fly calculation.
     * Requires {@link #elytraRenderRaytraces}.
     */
    public final Setting<Boolean> elytraRenderHitboxRaytraces = new Setting<>(false);

    /**
     * Renders the best elytra flight path that was simulated each tick.
     */
    public final Setting<Boolean> elytraRenderSimulation = new Setting<>(true);

    /**
     * Automatically path to and jump off of ledges to initiate elytra flight when grounded.
     */
    public final Setting<Boolean> elytraAutoJump = new Setting<>(false);

    /**
     * The seed used to generate chunks for long distance elytra path-finding in the nether.
     * Defaults to 2b2t's nether seed.
     */
    public final Setting<Long> elytraNetherSeed = new Setting<>(146008555100680L);

    /**
     * Whether nether-pathfinder should generate terrain based on {@link #elytraNetherSeed}.
     * If false all chunks that haven't been loaded are assumed to be air.
     */
    public final Setting<Boolean> elytraPredictTerrain = new Setting<>(false);

    /**
     * Automatically swap the current elytra with a new one when the durability gets too low
     */
    public final Setting<Boolean> elytraAutoSwap = new Setting<>(true);

    /**
     * The minimum durability an elytra can have before being swapped
     */
    public final Setting<Integer> elytraMinimumDurability = new Setting<>(5);

    /**
     * The minimum fireworks before landing early for safety
     */
    public final Setting<Integer> elytraMinFireworksBeforeLanding = new Setting<>(5);

    /**
     * Automatically land when elytra is almost out of durability, or almost out of fireworks
     */
    public final Setting<Boolean> elytraAllowEmergencyLand = new Setting<>(true);

    /**
     * Time between culling far away chunks from the nether pathfinder chunk cache
     */
    public final Setting<Long> elytraTimeBetweenCacheCullSecs = new Setting<>(TimeUnit.MINUTES.toSeconds(3));

    /**
     * Maximum distance chunks can be before being culled from the nether pathfinder chunk cache
     */
    public final Setting<Integer> elytraCacheCullDistance = new Setting<>(5000);

    /**
     * Should elytra consider nether brick a valid landing block
     */
    public final Setting<Boolean> elytraAllowLandOnNetherFortress = new Setting<>(false);

    /**
     * Has the user read and understood the elytra terms and conditions
     */
    public final Setting<Boolean> elytraTermsAccepted = new Setting<>(false);

    /**
     * Verbose chat logging in elytra mode
     */
    public final Setting<Boolean> elytraChatSpam = new Setting<>(false);

    /**
     * May reduce memory usage by using a custom allocator for pathfinding
     */
    public final Setting<Boolean> elytraCustomAllocator = new Setting<>(true);

    /**
     * Allow the pathfinder to attempt flight in tighter spaces, useful in caves but can be dangerous.
     */
    public final Setting<Boolean> elytraAllowTightSpaces = new Setting<>(false);

    /**
     * Allow the pathfinder to fly above y 128 in the nether.
     */
    public final Setting<Boolean> elytraAllowAboveRoof = new Setting<>(false);

    /**
     * Allow the pathfinder to access the baritone cache to improve pathing
     */
    public final Setting<Boolean> elytraUseCache = new Setting<>(true);

    /**
     * Allow the pathfinder to fly above the build limit in the overworld and end.
     */
    public final Setting<Boolean> elytraAllowAboveBuildLimit = new Setting<>(true);

    /**
     * Minimum distance in blocks of an elytra trip before the pathfinder will try to fly above build limit. (Minimum: 32). Requires {@link #elytraAllowAboveBuildLimit} to be enabled.
     */
    public final Setting<Integer> elytraLongDistanceThreshold = new Setting<>(500);

    /**
     * Sneak when magma blocks are under feet
     */
    public final Setting<Boolean> allowWalkOnMagmaBlocks = new Setting<>(false);

    // ----------------------------- combat -----------------------------
    // See combat_module.md. Cheat-style, human-looking fighter. Personal/single-player use.

    /**
     * Master toggle for the combat process. When false, {@code #hunt} will refuse to start.
     */
    public final Setting<Boolean> combatEnabled = new Setting<>(true);

    /**
     * Reach attribute buff applied to {@link net.minecraft.world.entity.ai.attributes.Attributes#ENTITY_INTERACTION_RANGE}
     * while fighting, so we out-range melee mobs. Set to 3.0 (vanilla) to disable the buff.
     * Applied to the client player and, on an integrated server, the matching server player.
     */
    public final Setting<Double> combatReach = new Setting<>(4.5);

    /**
     * When true, aim is eased through LookBehavior and the attack respects the swing-cooldown
     * (lower DPS, looks human). When false, snaps and strikes every ready tick for max DPS.
     */
    public final Setting<Boolean> combatHumanizeAim = new Setting<>(true);

    /**
     * Disengage and retreat below this many HP, then re-engage once healed.
     */
    public final Setting<Double> combatRetreatHealth = new Setting<>(6.0);

    /**
     * Retreat if more hostiles than this are within reaction range.
     */
    public final Setting<Integer> combatMaxAttackers = new Setting<>(3);

    /**
     * How long to wander searching for a target before giving up the hunt, in seconds.
     */
    public final Setting<Integer> combatSearchTimeoutSeconds = new Setting<>(60);

    /**
     * Hard cap on a single hunt's duration in seconds. 0 = no cap.
     */
    public final Setting<Integer> combatMaxDurationSeconds = new Setting<>(300);

    /**
     * After a kill on a manual hunt, walk over the loot within {@link #combatLootRadius}
     * before moving to the next target. Skipped entirely in auto-defend mode (survival
     * first — the frozen task resumes instead). Items in lava or on fire are ignored.
     */
    public final Setting<Boolean> combatCollectLoot = new Setting<>(true);

    /**
     * Radius (blocks) around the player scanned for loot after a kill.
     */
    public final Setting<Double> combatLootRadius = new Setting<>(8.0);

    /**
     * How long combat spends collecting loot after a kill before giving up (seconds).
     */
    public final Setting<Integer> combatLootTimeoutSeconds = new Setting<>(8);

    /**
     * When (and whether) the combat process self-activates to clear hostile mobs that wander within
     * {@link #combatDefendRange}. {@link baritone.api.process.AutoDefendMode#TASK} defends only while
     * another task is actively pathing (the original behavior); {@link AutoDefendMode#ALWAYS} defends
     * even when idle; {@link AutoDefendMode#OFF} disables it. Behaves like auto-eat: a temporary
     * process that freezes — not cancels — the underlying task. Off by default.
     */
    public final Setting<baritone.api.process.AutoDefendMode> combatAutoDefendMode =
            new Setting<>(baritone.api.process.AutoDefendMode.OFF);

    /**
     * Radius (blocks) in which a hostile mob triggers auto-defend. Anything hostile inside this
     * is treated as an immediate threat.
     */
    public final Setting<Double> combatDefendRange = new Setting<>(10.0);

    /**
     * Auto-defend pursuit leash: how far to chase a hostile before giving up and yielding back
     * to the original task. Greater than {@link #combatDefendRange} so a kill that drifts can
     * finish, but the bot won't pursue across the map.
     */
    public final Setting<Double> combatDefendPursueRange = new Setting<>(18.0);

    /**
     * Raise a shield (if one is equipped) to block incoming arrows and other projectiles, and to
     * turtle against imminent melee hits. The shield is only held while a hit is imminent, then
     * released so attacking/movement can resume.
     */
    public final Setting<Boolean> combatUseShield = new Setting<>(true);

    /**
     * Look-ahead (ticks) used to decide an incoming projectile will hit the player. A projectile
     * whose trajectory passes within {@code combatShieldRadius} of the player within this many
     * ticks triggers a shield raise.
     */
    public final Setting<Integer> combatProjectileLookahead = new Setting<>(10);

    /**
     * How many blocks to inflate the player's bounding box when testing whether an inbound projectile
     * will hit (and thus trigger a shield raise). Larger = block more generously (catches arrows that
     * would just clip the hitbox), smaller = only block near-certain hits. The step simulation tests
     * the projectile's arcing path against this inflated box.
     */
    public final Setting<Double> combatShieldRadius = new Setting<>(0.5);

    /**
     * Raise the shield this many ticks before a projectile's predicted impact — enough lead that the
     * block is fully up by the time the arrow lands, instead of raising too late and taking the hit.
     * The shield is then held a few ticks past impact so a flicker doesn't drop it early.
     */
    public final Setting<Integer> combatShieldLeadTicks = new Setting<>(5);

    /**
     * Predictive melee shield blocking. When a melee mob is inside its attack reach (we can't kite
     * outside it), the bot tracks each mob's swing rhythm and raises the shield just before the mob's
     * next predicted swing, then drops it the moment the mob is on its attack cooldown — opening a
     * strike window. This is selective (block the imminent hit, strike on the cooldown), NOT a held
     * block: holding the shield against a clump suppresses our own offense and drains the shield.
     *
     * <p>A mob that just swung is on cooldown whether or not its hit landed (a blocked hit still
     * spends the mob's swing) — that cooldown is the engage window. Only active for melee mobs we
     * can't kite (inside their reach); kite-able fights are unaffected.
     */
    public final Setting<Boolean> combatMeleeBlock = new Setting<>(true);

    /**
     * Hard cap (ticks) on how long the bot will hold the shield against melee without landing a
     * strike. If exceeded (a swing we couldn't detect, or a clump re-acquiring faster than we can
     * exploit), the bot drops the shield, retreats to reset, and re-engages — so it can never pin
     * the shield up until it breaks. ~40 ticks ≈ 2 seconds.
     */
    public final Setting<Integer> combatMeleeShieldMaxHold = new Setting<>(40);

    /**
     * Dodge perpendicular to a ranged mob's aim when its look vector lines up on the player (the
     * F3+B entity-look line). Uses key-based strafing rather than path goals, so it is responsive
     * enough to make arrows whiff.
     */
    public final Setting<Boolean> combatDodgeRanged = new Setting<>(true);

    /**
     * Aim-cone half-angle (degrees) within which a ranged mob counts as "aiming at us" and triggers
     * a dodge. Smaller = only dodge when truly lined up.
     */
    public final Setting<Double> combatDodgeAngle = new Setting<>(18.0);

    /**
     * Use a bow against targets that are out of melee reach. Requires a bow in the hotbar and at
     * least one arrow in the inventory. The bot switches back to a melee weapon once the target
     * closes inside {@code combatBowMinRange}.
     */
    public final Setting<Boolean> combatUseBow = new Setting<>(true);

    /**
     * Don't fire a bow at targets closer than this (blocks) — switch to melee instead. Should be
     * inside the mob's own melee reach so we don't plink point-blank.
     */
    public final Setting<Double> combatBowMinRange = new Setting<>(6.0);

    /**
     * Ticks to draw a bow before releasing (vanilla full draw is ~20 ticks for max damage).
     */
    public final Setting<Integer> combatBowDrawTicks = new Setting<>(20);

    /**
     * When true, force bow usage in combat regardless of distance — the bot will shoot even at
     * point-blank range instead of switching to melee. Intended for testing bow aim/draw timing;
     * toggle via {@code #hunt bow on|off}. Auto-clears when a hunt stops.
     */
    public final Setting<Boolean> combatForceBow = new Setting<>(false);

    /**
     * For velocity-scaled weapons (mace, spear), time the strike to coincide with high player
     * velocity: the mace smash-attacks on a fall, and spears benefit from sprint momentum. When
     * false, they are swung like a normal melee weapon.
     */
    public final Setting<Boolean> combatVelocityStrikes = new Setting<>(true);

    // ----------------------------- survival -----------------------------

    /**
     * When true, the survival process will auto-eat when hunger drops below {@link #autoEatBelowFood}.
     * Off by default so the bot doesn't consume your food unexpectedly.
     */
    public final Setting<Boolean> autoEat = new Setting<>(false);

    /**
     * Auto-eat when the food level drops below this (0-20).
     */
    public final Setting<Double> autoEatBelowFood = new Setting<>(14.0);

    /**
     * When true, the bot auto-respawns after dying (best-effort) and records the death position.
     */
    public final Setting<Boolean> autoResumeAfterDeath = new Setting<>(false);

    /**
     * A map of lowercase setting field names to their respective setting
     */
    public final Map<String, Setting<?>> byLowerName;

    /**
     * A list of all settings
     */
    public final List<Setting<?>> allSettings;

    public final Map<Setting<?>, Type> settingTypes;

    public final class Setting<T> {

        public T value;
        public final T defaultValue;
        private String name;
        private boolean javaOnly;

        @SuppressWarnings("unchecked")
        private Setting(T value) {
            if (value == null) {
                throw new IllegalArgumentException("Cannot determine value type class from null");
            }
            this.value = value;
            this.defaultValue = value;
            this.javaOnly = false;
        }

        /**
         * Deprecated! Please use .value directly instead
         *
         * @return the current setting value
         */
        @Deprecated
        public final T get() {
            return value;
        }

        public final String getName() {
            return name;
        }

        public Class<T> getValueClass() {
            // noinspection unchecked
            return (Class<T>) TypeUtils.resolveBaseClass(getType());
        }

        @Override
        public String toString() {
            return SettingsUtil.settingToString(this);
        }

        /**
         * Reset this setting to its default value
         */
        public void reset() {
            value = defaultValue;
        }

        public final Type getType() {
            return settingTypes.get(this);
        }

        /**
         * This should always be the same as whether the setting can be parsed from or serialized to a string; in other
         * words, the only way to modify it is by writing to {@link #value} programatically.
         *
         * @return {@code true} if the setting can not be set or read by the user
         */
        public boolean isJavaOnly() {
            return javaOnly;
        }
    }

    /**
     * Marks a {@link Setting} field as being {@link Setting#isJavaOnly() Java-only}
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    private @interface JavaOnly {}

    // here be dragons

    Settings() {
        Field[] temp = getClass().getFields();

        Map<String, Setting<?>> tmpByName = new HashMap<>();
        List<Setting<?>> tmpAll = new ArrayList<>();
        Map<Setting<?>, Type> tmpSettingTypes = new HashMap<>();

        try {
            for (Field field : temp) {
                if (field.getType().equals(Setting.class)) {
                    Setting<?> setting = (Setting<?>) field.get(this);
                    String name = field.getName();
                    setting.name = name;
                    setting.javaOnly = field.isAnnotationPresent(JavaOnly.class);
                    name = name.toLowerCase();
                    if (tmpByName.containsKey(name)) {
                        throw new IllegalStateException("Duplicate setting name");
                    }
                    tmpByName.put(name, setting);
                    tmpAll.add(setting);
                    tmpSettingTypes.put(setting, ((ParameterizedType) field.getGenericType()).getActualTypeArguments()[0]);
                }
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
        byLowerName = Collections.unmodifiableMap(tmpByName);
        allSettings = Collections.unmodifiableList(tmpAll);
        settingTypes = Collections.unmodifiableMap(tmpSettingTypes);
    }

    @SuppressWarnings("unchecked")
    public <T> List<Setting<T>> getAllValuesByType(Class<T> cla$$) {
        List<Setting<T>> result = new ArrayList<>();
        for (Setting<?> setting : allSettings) {
            if (setting.getValueClass().equals(cla$$)) {
                result.add((Setting<T>) setting);
            }
        }
        return result;
    }
}
