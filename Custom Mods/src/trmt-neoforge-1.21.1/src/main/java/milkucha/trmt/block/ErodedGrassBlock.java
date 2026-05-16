package milkucha.trmt.block;

import milkucha.trmt.TRMTBlocks;
import milkucha.trmt.erosion.BlockThresholds;
import milkucha.trmt.erosion.ChunkErosionMap;
import milkucha.trmt.erosion.ErosionEntry;
import milkucha.trmt.erosion.ErosionFx;
import milkucha.trmt.erosion.ErosionMapManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

public class ErodedGrassBlock extends Block {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final IntegerProperty STAGE = IntegerProperty.create("stage", 0, 4);

    public ErodedGrassBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
            .setValue(FACING, Direction.SOUTH)
            .setValue(STAGE, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, STAGE);
    }

    @Override
    public void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        int blockStage = state.getValue(STAGE);

        // Low-stage eroded grass still propagates regular grass to neighbouring vanilla
        // dirt — mirrors SpreadingSnowyDirtBlock's randomTick spread loop, but targets
        // only Blocks.DIRT (no eroded variants) and only fires from stages 0–2 so
        // heavily-trampled blocks stay dead.
        if (blockStage <= 2 && level.isAreaLoaded(pos, 3)
                && level.getMaxLocalRawBrightness(pos.above()) >= 9) {
            BlockState grassState = Blocks.GRASS_BLOCK.defaultBlockState();
            for (int i = 0; i < 4; i++) {
                BlockPos targetPos = pos.offset(
                    random.nextInt(3) - 1,
                    random.nextInt(5) - 3,
                    random.nextInt(3) - 1);
                if (level.getBlockState(targetPos).is(Blocks.DIRT)
                        && level.getMaxLocalRawBrightness(targetPos.above()) >= 9
                        && !level.getFluidState(targetPos.above()).is(FluidTags.WATER)) {
                    level.setBlockAndUpdate(targetPos, grassState);
                }
            }
        }

        // Gate de-erosion (but NOT the grass-spread above) when the server is empty —
        // chunk-loaded paths shouldn't quietly disappear overnight on dedicated servers.
        if (BlockThresholds.isDeErosionPausedForEmptyServer(level)) return;

        ErosionMapManager manager = ErosionMapManager.getInstance();
        ChunkErosionMap chunkMap = manager.getChunkMap(level, new ChunkPos(pos));
        ErosionEntry entry = chunkMap != null ? chunkMap.getEntry(pos) : null;
        long currentTime = level.getGameTime();
        long timeout = BlockThresholds.getGrassDeErosionTimeout(blockStage + 1);
        if (BlockThresholds.isIsolated(level, pos, manager)) timeout /= 2;
        if (entry != null && currentTime - entry.getLastTouchedGameTime() <= timeout) return;

        if (blockStage > 0) {
            BlockState next = state.setValue(STAGE, blockStage - 1);
            ErosionFx.crumbleParticles(level, pos, next);
            level.setBlock(pos, next, Block.UPDATE_ALL);
            manager.removeEntry(level, pos);
            manager.writeCooldownEntry(level, pos, TRMTBlocks.ERODED_GRASS_BLOCK.get(), currentTime);
        } else {
            BlockState next = Blocks.GRASS_BLOCK.defaultBlockState();
            ErosionFx.crumbleParticles(level, pos, next);
            level.setBlock(pos, next, Block.UPDATE_ALL);
            manager.removeEntry(level, pos);
        }
    }
}
