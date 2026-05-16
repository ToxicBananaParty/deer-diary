package milkucha.trmt.erosion;

import milkucha.trmt.TRMTBlocks;
import milkucha.trmt.TRMTConfig;
import milkucha.trmt.api.CanErodeEvent;
import milkucha.trmt.block.ErodedDirtBlock;
import milkucha.trmt.block.ErodedGrassBlock;
import milkucha.trmt.block.ErodedSandBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Shared step-tracking logic used by both player and mob trampling mixins.
 * Handles the "entity stands on a tracked block → maybe transform it" flow.
 * Adjacent-block erosion and vegetation trampling stay in the player mixin
 * (those are player-only effects).
 */
public final class EntityStepHandler {

    private EntityStepHandler() {}

    /**
     * Records a step on {@code groundPos} and runs the erosion transform if the
     * walked-on count is over threshold. Skips if a protected plant sits above
     * (sapling/crop/flower/sweet berry/bamboo/sugar cane/cactus), or if the block
     * isn't a tracked type per the current config.
     */
    public static void handleGroundStep(ServerLevel level, BlockPos groundPos, float mult, long gameTime) {
        BlockState state = level.getBlockState(groundPos);
        Block block = state.getBlock();

        TRMTConfig.ErosionToggles erosion = TRMTConfig.get().erosion;
        boolean tracked = (erosion.grassEnabled && (state.is(Blocks.GRASS_BLOCK) || state.is(TRMTBlocks.ERODED_GRASS_BLOCK.get())))
                || (erosion.dirtEnabled && (state.is(Blocks.DIRT) || state.is(TRMTBlocks.ERODED_DIRT.get()) || state.is(TRMTBlocks.ERODED_DIRT_PATH.get())))
                || (erosion.sandEnabled && (state.is(Blocks.SAND) || state.is(TRMTBlocks.ERODED_SAND.get())))
                || (erosion.leavesEnabled && BlockThresholds.isLeaves(block));
        if (!tracked) return;

        if (hasProtectedPlantAbove(level, groundPos)) return;

        ErosionMapManager manager = ErosionMapManager.getInstance();
        manager.onStep(level, groundPos, block, mult, gameTime);
        tryTransform(level, manager, groundPos);
        manager.broadcastEntryUpdate(level, groundPos, block);
    }

    public static boolean hasProtectedPlantAbove(ServerLevel level, BlockPos groundPos) {
        BlockState above = level.getBlockState(groundPos.above());
        if (above.is(BlockTags.SAPLINGS)) return true;
        if (above.is(BlockTags.CROPS)) return true;
        if (above.is(BlockTags.FLOWERS)) return true;
        Block block = above.getBlock();
        return block == Blocks.SWEET_BERRY_BUSH
            || block == Blocks.BAMBOO
            || block == Blocks.BAMBOO_SAPLING
            || block == Blocks.SUGAR_CANE
            || block == Blocks.CACTUS;
    }

    public static void tryTransform(ServerLevel level, ErosionMapManager manager, BlockPos pos) {
        BlockState state = level.getBlockState(pos);

        ChunkErosionMap map = manager.getChunkMap(level, new ChunkPos(pos));
        if (map == null) return;
        ErosionEntry entry = map.getEntry(pos);
        if (entry == null || entry.getWalkedOnCount() < entry.getThreshold()) return;

        CanErodeEvent event = new CanErodeEvent(level, pos, state);
        NeoForge.EVENT_BUS.post(event);
        if (event.isCanceled()) return;

        if (state.is(Blocks.SAND)) {
            Direction erodedFacing = rotationToFacing(BlockThresholds.posRotation(pos));
            ErosionFx.crumbleParticles(level, pos, state);
            level.setBlock(pos,
                TRMTBlocks.ERODED_SAND.get().defaultBlockState()
                    .setValue(ErodedSandBlock.FACING, erodedFacing)
                    .setValue(ErodedSandBlock.STAGE, 0),
                Block.UPDATE_ALL);
            manager.removeEntry(level, pos);
            manager.writeCooldownEntry(level, pos, TRMTBlocks.ERODED_SAND.get(), level.getGameTime());
            return;
        }

        if (state.is(TRMTBlocks.ERODED_SAND.get())) {
            int stage = state.getValue(ErodedSandBlock.STAGE);
            if (stage < 4) {
                ErosionFx.crumbleParticles(level, pos, state);
                level.setBlock(pos, state.setValue(ErodedSandBlock.STAGE, stage + 1), Block.UPDATE_ALL);
            }
            manager.removeEntry(level, pos);
            manager.writeCooldownEntry(level, pos, TRMTBlocks.ERODED_SAND.get(), level.getGameTime());
            return;
        }

        if (BlockThresholds.isLeaves(state.getBlock())) {
            float dropChance = TRMTConfig.get().erosionThresholds.leaves.dropChance;
            boolean drops = dropChance >= 1.0f || (dropChance > 0.0f && ThreadLocalRandom.current().nextFloat() < dropChance);
            level.destroyBlock(pos, drops);
            manager.removeEntry(level, pos);
            return;
        }

        if (state.is(Blocks.GRASS_BLOCK)) {
            Direction erodedFacing = rotationToFacing(BlockThresholds.posRotation(pos));
            ErosionFx.crumbleParticles(level, pos, state);
            level.setBlock(pos,
                TRMTBlocks.ERODED_GRASS_BLOCK.get().defaultBlockState()
                    .setValue(ErodedGrassBlock.FACING, erodedFacing)
                    .setValue(ErodedGrassBlock.STAGE, 0),
                Block.UPDATE_ALL);
            manager.removeEntry(level, pos);
            manager.writeCooldownEntry(level, pos, TRMTBlocks.ERODED_GRASS_BLOCK.get(), level.getGameTime());
            return;
        }

        if (state.is(TRMTBlocks.ERODED_GRASS_BLOCK.get())) {
            Direction facing = state.getValue(ErodedGrassBlock.FACING);
            int currentStage = state.getValue(ErodedGrassBlock.STAGE);
            if (currentStage < 4) {
                ErosionFx.crumbleParticles(level, pos, state);
                level.setBlock(pos, state.setValue(ErodedGrassBlock.STAGE, currentStage + 1), Block.UPDATE_ALL);
                manager.removeEntry(level, pos);
                manager.writeCooldownEntry(level, pos, TRMTBlocks.ERODED_GRASS_BLOCK.get(), level.getGameTime());
                return;
            }
            // Stage 4 → next form. By default that's eroded_dirt_path (looks like vanilla
            // dirt path, drops dirt, de-erodes back to grass over time). Toggle via
            // erosion.dirtPathEndpoint to fall back to the legacy eroded_dirt → eroded_coarse_dirt chain.
            if (TRMTConfig.get().erosion.dirtPathEndpoint) {
                ErosionFx.crumbleParticles(level, pos, state);
                level.setBlock(pos, TRMTBlocks.ERODED_DIRT_PATH.get().defaultBlockState(), Block.UPDATE_ALL);
                manager.removeEntry(level, pos);
                manager.writeCooldownEntry(level, pos, TRMTBlocks.ERODED_DIRT_PATH.get(), level.getGameTime());
            } else {
                ErosionFx.crumbleParticles(level, pos, state);
                level.setBlock(pos,
                    TRMTBlocks.ERODED_DIRT.get().defaultBlockState().setValue(ErodedDirtBlock.FACING, facing),
                    Block.UPDATE_ALL);
                manager.removeEntry(level, pos);
            }
            return;
        }

        if (state.is(TRMTBlocks.ERODED_DIRT.get())) {
            Direction facing = state.getValue(ErodedDirtBlock.FACING);
            int currentStage = state.getValue(ErodedDirtBlock.STAGE);
            if (currentStage < 3) {
                ErosionFx.crumbleParticles(level, pos, state);
                level.setBlock(pos, state.setValue(ErodedDirtBlock.STAGE, currentStage + 1), Block.UPDATE_ALL);
                manager.removeEntry(level, pos);
                return;
            }
            ErosionFx.crumbleParticles(level, pos, state);
            level.setBlock(pos,
                TRMTBlocks.ERODED_COARSE_DIRT.get().defaultBlockState().setValue(ErodedDirtBlock.FACING, facing),
                Block.UPDATE_ALL);
            manager.removeEntry(level, pos);
            return;
        }

        if (!state.is(Blocks.DIRT)) return;
        Direction erodedFacing = rotationToFacing(BlockThresholds.posRotation(pos));
        ErosionFx.crumbleParticles(level, pos, state);
        level.setBlock(pos,
            TRMTBlocks.ERODED_DIRT.get().defaultBlockState()
                .setValue(ErodedDirtBlock.FACING, erodedFacing)
                .setValue(ErodedDirtBlock.STAGE, 1),
            Block.UPDATE_ALL);
        manager.removeEntry(level, pos);
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
