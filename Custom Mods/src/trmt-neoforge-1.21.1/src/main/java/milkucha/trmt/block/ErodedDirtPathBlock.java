package milkucha.trmt.block;

import milkucha.trmt.TRMTBlocks;
import milkucha.trmt.TRMTConfig;
import milkucha.trmt.api.CanDeErodeEvent;
import milkucha.trmt.api.DeErodedEvent;
import milkucha.trmt.erosion.BlockThresholds;
import milkucha.trmt.erosion.ChunkErosionMap;
import milkucha.trmt.erosion.EntityStepHandler;
import milkucha.trmt.erosion.ErosionEntry;
import milkucha.trmt.erosion.ErosionFx;
import milkucha.trmt.erosion.ErosionMapManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirtPathBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Erosion-derived dirt path. Subclasses {@link DirtPathBlock} to inherit every
 * vanilla behaviour (visual height, sound, hardness, "becomes dirt when block
 * placed above", hoe-to-farmland, drops dirt when broken). Adds a random tick
 * that de-erodes the block back into {@code eroded_grass_block} stage 4 once
 * the configured timeout has elapsed since it was last walked on.
 *
 * <p>The "is this an erosion path or a shovel-made path?" disambiguation is
 * automatic: the block class is distinct from {@code minecraft:dirt_path}.
 * Vanilla shovel paths are untouched.
 */
public class ErodedDirtPathBlock extends DirtPathBlock {

    public ErodedDirtPathBlock(Properties properties) {
        super(properties);
    }

    @Override
    public void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        // Vanilla DirtPathBlock doesn't override randomTick, so no super call needed.
        if (!BlockThresholds.isLocationAllowed(level, pos)) return;
        if (BlockThresholds.isDeErosionPausedForEmptyServer(level)) return;

        ErosionMapManager manager = ErosionMapManager.getInstance();
        ChunkErosionMap chunkMap = manager.getChunkMap(level, new ChunkPos(pos));
        ErosionEntry entry = chunkMap != null ? chunkMap.getEntry(pos) : null;

        long currentTime = level.getGameTime();
        long timeout = (long)(TRMTConfig.get().deErosionTimeoutDays.dirtPath * 24000L);
        if (BlockThresholds.isIsolated(level, pos, manager)) timeout /= 2;
        if (entry != null && currentTime - entry.getLastTouchedGameTime() <= timeout) return;

        CanDeErodeEvent event = new CanDeErodeEvent(level, pos, state);
        NeoForge.EVENT_BUS.post(event);
        if (event.isCanceled()) return;

        Direction facing = EntityStepHandler.rotationToFacing(BlockThresholds.posRotation(pos));
        BlockState next = TRMTBlocks.ERODED_GRASS_BLOCK.get().defaultBlockState()
            .setValue(ErodedGrassBlock.FACING, facing)
            .setValue(ErodedGrassBlock.STAGE, 4);
        ErosionFx.crumbleParticles(level, pos, next);
        level.setBlock(pos, next, Block.UPDATE_ALL);
        NeoForge.EVENT_BUS.post(new DeErodedEvent(level, pos, state, next));
        manager.removeEntry(level, pos);
        manager.writeCooldownEntry(level, pos, TRMTBlocks.ERODED_GRASS_BLOCK.get(), currentTime);
    }
}
