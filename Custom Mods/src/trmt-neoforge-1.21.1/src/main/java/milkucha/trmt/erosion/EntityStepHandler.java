package milkucha.trmt.erosion;

import milkucha.trmt.TRMTBlocks;
import milkucha.trmt.TRMTConfig;
import milkucha.trmt.TRMTTags;
import milkucha.trmt.api.CanErodeEvent;
import milkucha.trmt.api.ErodedEvent;
import milkucha.trmt.block.ErodedDirtBlock;
import milkucha.trmt.block.ErodedGrassBlock;
import milkucha.trmt.block.ErodedSandBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.ToDoubleFunction;

/**
 * Shared step-tracking logic used by both player and mob trampling mixins.
 * Handles the "entity stands on a tracked block → maybe transform it" flow.
 * Adjacent-block erosion and vegetation trampling stay in the player mixin
 * (those are player-only effects).
 *
 * <p>Tracked-block membership is data-driven via TRMT's block tags
 * ({@link TRMTTags#ERODES_AS_GRASS} etc.) — modpacks can add modded blocks to
 * those tags to have them participate in the erosion chains without code
 * changes.
 */
public final class EntityStepHandler {

    private EntityStepHandler() {}

    /**
     * Multiplicative modifiers applied to the per-step erosion count before
     * {@link ErosionMapManager#onStep} is called. Used by soft-compat modules
     * (SereneSeasons, etc.) to adjust erosion rate based on world state.
     * Internal — call {@link #addStepMultiplierModifier} to add one.
     */
    private static final List<ToDoubleFunction<ServerLevel>> STEP_MULTIPLIER_MODIFIERS = new CopyOnWriteArrayList<>();

    /**
     * Register a modifier that scales the per-step erosion mult based on the
     * level. All registered modifiers are multiplied together. Identity is
     * 1.0; lower values slow erosion, higher values accelerate.
     */
    public static void addStepMultiplierModifier(ToDoubleFunction<ServerLevel> modifier) {
        STEP_MULTIPLIER_MODIFIERS.add(modifier);
    }

    /**
     * Applies all registered step-multiplier modifiers to {@code baseMult}.
     * Used by {@link #handleGroundStep} and by the player mixin's adjacent /
     * vegetation onStep calls.
     */
    public static float applyStepMultiplierModifiers(float baseMult, ServerLevel level) {
        if (STEP_MULTIPLIER_MODIFIERS.isEmpty()) return baseMult;
        float m = baseMult;
        for (ToDoubleFunction<ServerLevel> mod : STEP_MULTIPLIER_MODIFIERS) {
            m *= (float) mod.applyAsDouble(level);
        }
        return m;
    }

    /** Backwards-compat — same as the five-arg overload with {@code null} player. */
    public static void handleGroundStep(ServerLevel level, BlockPos groundPos, float mult, long gameTime) {
        handleGroundStep(level, groundPos, mult, gameTime, null);
    }

    /**
     * Records a step on {@code groundPos} and runs the erosion transform if the
     * walked-on count is over threshold. Skips if a protected plant sits above
     * (see {@link TRMTTags#PROTECTS_BELOW_FROM_EROSION}), or if the block isn't
     * a tracked type per the current config + tag membership.
     */
    public static void handleGroundStep(ServerLevel level, BlockPos groundPos, float mult, long gameTime,
                                        @Nullable ServerPlayer player) {
        if (!BlockThresholds.isLocationAllowed(level, groundPos)) return;

        BlockState state = level.getBlockState(groundPos);
        Block block = state.getBlock();

        if (!isTrackedForErosion(state)) return;
        if (hasProtectedPlantAbove(level, groundPos)) return;

        float effectiveMult = applyStepMultiplierModifiers(mult, level);

        ErosionMapManager manager = ErosionMapManager.getInstance();
        manager.onStep(level, groundPos, block, effectiveMult, gameTime);
        tryTransform(level, manager, groundPos, player);
        manager.broadcastEntryUpdate(level, groundPos, block);
    }

    /**
     * Returns {@code true} if {@code state} is a block whose erosion is
     * currently enabled and which has tag membership in one of the
     * {@code erodes_as_*} families. Used both at the step-tracking entry point
     * and by adjacent-block tracking in the player mixin.
     */
    public static boolean isTrackedForErosion(BlockState state) {
        TRMTConfig.ErosionToggles erosion = TRMTConfig.get().erosion;
        if (erosion.grassEnabled && state.is(TRMTTags.ERODES_AS_GRASS)) return true;
        if (erosion.dirtEnabled  && state.is(TRMTTags.ERODES_AS_DIRT))  return true;
        if (erosion.sandEnabled  && state.is(TRMTTags.ERODES_AS_SAND))  return true;
        if (erosion.leavesEnabled
            && (state.is(TRMTTags.ERODES_AS_LEAVES) || BlockThresholds.isLeaves(state.getBlock()))) return true;
        return false;
    }

    /**
     * Returns {@code true} if the block directly above {@code groundPos}
     * should protect the ground from erosion (saplings/crops/flowers/
     * sweet berry/bamboo/sugar cane/cactus, plus any modded entry in
     * {@link TRMTTags#PROTECTS_BELOW_FROM_EROSION}).
     */
    public static boolean hasProtectedPlantAbove(ServerLevel level, BlockPos groundPos) {
        BlockState above = level.getBlockState(groundPos.above());
        return above.is(TRMTTags.PROTECTS_BELOW_FROM_EROSION);
    }

    /** Backwards-compat — same as the four-arg overload with {@code null} player. */
    public static void tryTransform(ServerLevel level, ErosionMapManager manager, BlockPos pos) {
        tryTransform(level, manager, pos, null);
    }

    public static void tryTransform(ServerLevel level, ErosionMapManager manager, BlockPos pos,
                                    @Nullable ServerPlayer player) {
        BlockState state = level.getBlockState(pos);

        ChunkErosionMap map = manager.getChunkMap(level, new ChunkPos(pos));
        if (map == null) return;
        ErosionEntry entry = map.getEntry(pos);
        if (entry == null || entry.getWalkedOnCount() < entry.getThreshold()) return;

        CanErodeEvent event = new CanErodeEvent(level, pos, state, player);
        NeoForge.EVENT_BUS.post(event);
        if (event.isCanceled()) return;

        // --- Specific-block branches: handle our own eroded blocks (stage progression)
        //     and the no-op terminal cases. Always check these BEFORE the tag-based
        //     fresh-conversion fallbacks below.

        if (state.is(TRMTBlocks.ERODED_SAND.get())) {
            int stage = state.getValue(ErodedSandBlock.STAGE);
            if (stage < 4) {
                BlockState next = state.setValue(ErodedSandBlock.STAGE, stage + 1);
                applyErosion(level, pos, state, next, player);
            }
            manager.removeEntry(level, pos);
            manager.writeCooldownEntry(level, pos, TRMTBlocks.ERODED_SAND.get(), level.getGameTime());
            return;
        }

        if (BlockThresholds.isLeaves(state.getBlock()) || state.is(TRMTTags.ERODES_AS_LEAVES)) {
            float dropChance = TRMTConfig.get().erosionThresholds.leaves.dropChance;
            boolean drops = dropChance >= 1.0f || (dropChance > 0.0f && ThreadLocalRandom.current().nextFloat() < dropChance);
            level.destroyBlock(pos, drops);
            NeoForge.EVENT_BUS.post(new ErodedEvent(level, pos, state, Blocks.AIR.defaultBlockState(), player));
            manager.removeEntry(level, pos);
            return;
        }

        if (state.is(TRMTBlocks.ERODED_GRASS_BLOCK.get())) {
            Direction facing = state.getValue(ErodedGrassBlock.FACING);
            int currentStage = state.getValue(ErodedGrassBlock.STAGE);
            if (currentStage < 4) {
                BlockState next = state.setValue(ErodedGrassBlock.STAGE, currentStage + 1);
                applyErosion(level, pos, state, next, player);
                manager.removeEntry(level, pos);
                manager.writeCooldownEntry(level, pos, TRMTBlocks.ERODED_GRASS_BLOCK.get(), level.getGameTime());
                return;
            }
            // Stage 4 → next form. By default that's eroded_dirt_path (looks like vanilla
            // dirt path, drops dirt, de-erodes back to grass over time). Toggle via
            // erosion.dirtPathEndpoint to fall back to the legacy eroded_dirt → eroded_coarse_dirt chain.
            if (TRMTConfig.get().erosion.dirtPathEndpoint) {
                BlockState next = TRMTBlocks.ERODED_DIRT_PATH.get().defaultBlockState();
                applyErosion(level, pos, state, next, player);
                manager.removeEntry(level, pos);
                manager.writeCooldownEntry(level, pos, TRMTBlocks.ERODED_DIRT_PATH.get(), level.getGameTime());
            } else {
                BlockState next = TRMTBlocks.ERODED_DIRT.get().defaultBlockState().setValue(ErodedDirtBlock.FACING, facing);
                applyErosion(level, pos, state, next, player);
                manager.removeEntry(level, pos);
            }
            return;
        }

        if (state.is(TRMTBlocks.ERODED_DIRT.get())) {
            Direction facing = state.getValue(ErodedDirtBlock.FACING);
            int currentStage = state.getValue(ErodedDirtBlock.STAGE);
            if (currentStage < 3) {
                BlockState next = state.setValue(ErodedDirtBlock.STAGE, currentStage + 1);
                applyErosion(level, pos, state, next, player);
                manager.removeEntry(level, pos);
                return;
            }
            BlockState next = TRMTBlocks.ERODED_COARSE_DIRT.get().defaultBlockState().setValue(ErodedDirtBlock.FACING, facing);
            applyErosion(level, pos, state, next, player);
            manager.removeEntry(level, pos);
            return;
        }

        if (state.is(TRMTBlocks.ERODED_DIRT_PATH.get())) {
            // Terminal block — walking on it accumulates lastTouchedGameTime (to keep the
            // de-erosion timer fresh) but doesn't transform further.
            return;
        }

        // --- Tag-based fresh-source conversion: vanilla blocks AND modded blocks
        //     added by datapack into the erodes_as_* tags. Order: sand → grass → dirt.

        if (state.is(TRMTTags.ERODES_AS_SAND)) {
            Direction erodedFacing = rotationToFacing(BlockThresholds.posRotation(pos));
            BlockState next = TRMTBlocks.ERODED_SAND.get().defaultBlockState()
                    .setValue(ErodedSandBlock.FACING, erodedFacing)
                    .setValue(ErodedSandBlock.STAGE, 0);
            applyErosion(level, pos, state, next, player);
            manager.removeEntry(level, pos);
            manager.writeCooldownEntry(level, pos, TRMTBlocks.ERODED_SAND.get(), level.getGameTime());
            return;
        }

        if (state.is(TRMTTags.ERODES_AS_GRASS)) {
            Direction erodedFacing = rotationToFacing(BlockThresholds.posRotation(pos));
            BlockState next = TRMTBlocks.ERODED_GRASS_BLOCK.get().defaultBlockState()
                    .setValue(ErodedGrassBlock.FACING, erodedFacing)
                    .setValue(ErodedGrassBlock.STAGE, 0);
            applyErosion(level, pos, state, next, player);
            manager.removeEntry(level, pos);
            manager.writeCooldownEntry(level, pos, TRMTBlocks.ERODED_GRASS_BLOCK.get(), level.getGameTime());
            return;
        }

        if (state.is(TRMTTags.ERODES_AS_DIRT)) {
            Direction erodedFacing = rotationToFacing(BlockThresholds.posRotation(pos));
            BlockState next = TRMTBlocks.ERODED_DIRT.get().defaultBlockState()
                    .setValue(ErodedDirtBlock.FACING, erodedFacing)
                    .setValue(ErodedDirtBlock.STAGE, 1);
            applyErosion(level, pos, state, next, player);
            manager.removeEntry(level, pos);
        }
    }

    /**
     * Applies a successful erosion transform: spawns the crumble particles,
     * writes the new block state to the world, and posts {@link ErodedEvent}
     * so subscribers can react.
     */
    private static void applyErosion(ServerLevel level, BlockPos pos, BlockState fromState, BlockState toState,
                                     @Nullable ServerPlayer player) {
        ErosionFx.crumbleParticles(level, pos, toState);
        level.setBlock(pos, toState, Block.UPDATE_ALL);
        NeoForge.EVENT_BUS.post(new ErodedEvent(level, pos, fromState, toState, player));
    }

    public static Direction rotationToFacing(int rotation) {
        return switch (rotation) {
            case 1  -> Direction.WEST;
            case 2  -> Direction.NORTH;
            case 3  -> Direction.EAST;
            default -> Direction.SOUTH;
        };
    }
}
