package milkucha.trmt.erosion;

import milkucha.trmt.TRMTBlocks;
import milkucha.trmt.TRMTConfig;
import milkucha.trmt.TRMTTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public final class BlockThresholds {

    public static final Set<Block> VEGETATION = Set.of(
        Blocks.SHORT_GRASS, Blocks.TALL_GRASS,
        Blocks.DANDELION, Blocks.POPPY, Blocks.BLUE_ORCHID, Blocks.ALLIUM,
        Blocks.AZURE_BLUET, Blocks.RED_TULIP, Blocks.ORANGE_TULIP,
        Blocks.WHITE_TULIP, Blocks.PINK_TULIP, Blocks.OXEYE_DAISY,
        Blocks.CORNFLOWER, Blocks.LILY_OF_THE_VALLEY, Blocks.WITHER_ROSE,
        Blocks.SUNFLOWER, Blocks.LILAC, Blocks.ROSE_BUSH, Blocks.PEONY
    );

    private BlockThresholds() {}

    public static boolean isVegetation(Block block) {
        return VEGETATION.contains(block);
    }

    public static boolean isLeaves(Block block) {
        return block instanceof LeavesBlock;
    }

    public static int posRotation(BlockPos pos) {
        int h = (pos.getX() * 1619) ^ (pos.getZ() * 31337);
        return ((h >>> 4) ^ (h >>> 8)) & 3;
    }

    public static float randomThreshold(Block block) {
        BlockState state = block.defaultBlockState();
        TRMTConfig cfg = TRMTConfig.get();
        TRMTConfig.MinMax range;

        // Special-case the two terminal eroded variants whose threshold differs
        // from the "fresh" source.
        if (block == TRMTBlocks.ERODED_COARSE_DIRT.get()) {
            range = cfg.erosionThresholds.coarseDirt;
        } else if (VEGETATION.contains(block)) {
            range = cfg.erosionThresholds.vegetation;
        }
        // Tag-based dispatch: a block can appear in multiple tags, but the order
        // (grass → dirt → sand → leaves) matches the tryTransform dispatch
        // order, so the threshold matches the chain the block would actually
        // erode through.
        else if (state.is(TRMTTags.ERODES_AS_GRASS)) {
            range = cfg.erosionThresholds.grass;
        } else if (state.is(TRMTTags.ERODES_AS_DIRT)) {
            range = cfg.erosionThresholds.dirt;
        } else if (state.is(TRMTTags.ERODES_AS_SAND)) {
            range = cfg.erosionThresholds.sand;
        } else if (state.is(TRMTTags.ERODES_AS_LEAVES) || block instanceof LeavesBlock) {
            range = cfg.erosionThresholds.leaves;
        } else {
            range = cfg.erosionThresholds.grass;
        }
        float min = range.min, max = range.max;
        if (max <= min) return min;
        return min + ThreadLocalRandom.current().nextFloat() * (max - min);
    }

    private static final long TICKS_PER_DAY = 24000L;

    private static final Direction[] HORIZONTALS = {
        Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };

    public static boolean isIsolated(ServerLevel level, BlockPos pos, ErosionMapManager manager) {
        for (Direction dir : HORIZONTALS) {
            for (int dy = -1; dy <= 1; dy++) {
                BlockPos neighbor = pos.relative(dir).above(dy);
                BlockState neighborState = level.getBlockState(neighbor);
                Block neighborBlock = neighborState.getBlock();
                if (neighborBlock == TRMTBlocks.ERODED_GRASS_BLOCK.get()
                        || neighborBlock == TRMTBlocks.ERODED_DIRT.get()
                        || neighborBlock == TRMTBlocks.ERODED_COARSE_DIRT.get()
                        || neighborBlock == TRMTBlocks.ERODED_SAND.get()) {
                    return false;
                }
                if (neighborBlock == Blocks.GRASS_BLOCK) {
                    ChunkErosionMap map = manager.getChunkMap(level, new ChunkPos(neighbor));
                    if (map != null) {
                        ErosionEntry e = map.getEntry(neighbor);
                        if (e != null && e.getErosionStage() > 0) return false;
                    }
                }
            }
        }
        return true;
    }

    public static long getGrassDeErosionTimeout(int stage) {
        TRMTConfig cfg = TRMTConfig.get();
        TRMTConfig.GrassDeErosion g = cfg.deErosionTimeoutDays.grass;
        return switch (stage) {
            case 1  -> (long)(g.stage1 * TICKS_PER_DAY);
            case 2  -> (long)(g.stage2 * TICKS_PER_DAY);
            case 3  -> (long)(g.stage3 * TICKS_PER_DAY);
            case 4  -> (long)(g.stage4 * TICKS_PER_DAY);
            default -> (long)(g.stage5 * TICKS_PER_DAY);
        };
    }

    public static long getSandDeErosionTimeout(int stage) {
        TRMTConfig cfg = TRMTConfig.get();
        TRMTConfig.SandDeErosion s = cfg.deErosionTimeoutDays.sand;
        return (long)((switch (stage) {
            case 0  -> s.stage1;
            case 1  -> s.stage2;
            case 2  -> s.stage3;
            case 3  -> s.stage4;
            default -> s.stage5;
        }) * TICKS_PER_DAY);
    }

    public static long getDirtDeErosionTimeout(Block block) {
        TRMTConfig cfg = TRMTConfig.get();
        TRMTConfig.DirtDeErosion d = cfg.deErosionTimeoutDays.dirt;
        if (block == TRMTBlocks.ERODED_DIRT.get()) return (long)(d.erodedDirt * TICKS_PER_DAY);
        return (long)(d.erodedCoarseDirt * TICKS_PER_DAY);
    }

    /**
     * Returns {@code true} if de-erosion should be skipped right now because
     * the {@code pauseDeErosionWhenEmpty} config flag is enabled and no
     * players are online. Prevents chunk-loaded paths from quietly reverting
     * overnight on dedicated servers when nobody is around to see (or
     * re-walk) them.
     *
     * <p>Erosion (vanilla → eroded) isn't gated separately because it only
     * fires from player/mob steps, which inherently require a live player.
     */
    public static boolean isDeErosionPausedForEmptyServer(ServerLevel level) {
        if (!TRMTConfig.get().erosion.pauseDeErosionWhenEmpty) return false;
        return level.getServer() != null
            && level.getServer().getPlayerList().getPlayerCount() == 0;
    }

    /**
     * Returns {@code true} if this dimension is permitted by the
     * {@code dimensions} allow/block list in {@code trmt.json}. Default
     * config returns {@code true} for every dimension.
     */
    public static boolean isDimensionAllowed(ServerLevel level) {
        return TRMTConfig.get().dimensions.isEnabled(level.dimension().location().toString());
    }

    /**
     * Returns {@code true} if erosion is allowed at this position. Combines
     * the dimension allow/block list with the {@code allowInForcedChunks}
     * toggle. Both apply to both erosion and de-erosion.
     */
    public static boolean isLocationAllowed(ServerLevel level, BlockPos pos) {
        if (!isDimensionAllowed(level)) return false;
        if (!TRMTConfig.get().erosion.allowInForcedChunks) {
            long chunkKey = new ChunkPos(pos).toLong();
            if (level.getForcedChunks().contains(chunkKey)) return false;
        }
        return true;
    }
}
