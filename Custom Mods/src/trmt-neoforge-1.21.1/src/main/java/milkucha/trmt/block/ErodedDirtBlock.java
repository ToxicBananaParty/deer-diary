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
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public class ErodedDirtBlock extends Block {

    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 16, 16);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final IntegerProperty STAGE = IntegerProperty.create("stage", 0, 3);

    public ErodedDirtBlock(Properties properties) {
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
        if (BlockThresholds.isDeErosionPausedForEmptyServer(level)) return;

        ErosionMapManager manager = ErosionMapManager.getInstance();
        ChunkErosionMap chunkMap = manager.getChunkMap(level, new ChunkPos(pos));
        ErosionEntry entry = chunkMap != null ? chunkMap.getEntry(pos) : null;

        long currentTime = level.getGameTime();
        long timeout = BlockThresholds.getDirtDeErosionTimeout(state.getBlock());
        if (BlockThresholds.isIsolated(level, pos, manager)) timeout /= 2;
        if (entry != null && currentTime - entry.getLastTouchedGameTime() <= timeout) return;

        Direction facing = state.getValue(FACING);
        Block block = state.getBlock();

        if (block == TRMTBlocks.ERODED_COARSE_DIRT.get()) {
            BlockState next = TRMTBlocks.ERODED_DIRT.get().defaultBlockState()
                .setValue(FACING, facing).setValue(STAGE, 3);
            ErosionFx.crumbleParticles(level, pos, next);
            level.setBlock(pos, next, Block.UPDATE_ALL);
            manager.removeEntry(level, pos);
            manager.writeCooldownEntry(level, pos, TRMTBlocks.ERODED_DIRT.get(), currentTime);
        } else if (block == TRMTBlocks.ERODED_DIRT.get()) {
            int stage = state.getValue(STAGE);
            if (stage > 0) {
                BlockState next = state.setValue(STAGE, stage - 1);
                ErosionFx.crumbleParticles(level, pos, next);
                level.setBlock(pos, next, Block.UPDATE_ALL);
                manager.removeEntry(level, pos);
                manager.writeCooldownEntry(level, pos, TRMTBlocks.ERODED_DIRT.get(), currentTime);
            } else {
                BlockState next = TRMTBlocks.ERODED_GRASS_BLOCK.get().defaultBlockState()
                    .setValue(ErodedGrassBlock.FACING, facing)
                    .setValue(ErodedGrassBlock.STAGE, 4);
                ErosionFx.crumbleParticles(level, pos, next);
                level.setBlock(pos, next, Block.UPDATE_ALL);
                manager.removeEntry(level, pos);
                manager.writeCooldownEntry(level, pos, TRMTBlocks.ERODED_GRASS_BLOCK.get(), currentTime);
            }
        }
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }
}
