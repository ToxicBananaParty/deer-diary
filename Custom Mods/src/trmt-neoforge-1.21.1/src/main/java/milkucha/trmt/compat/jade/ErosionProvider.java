package milkucha.trmt.compat.jade;

import milkucha.trmt.TRMT;
import milkucha.trmt.TRMTBlocks;
import milkucha.trmt.block.ErodedDirtBlock;
import milkucha.trmt.block.ErodedGrassBlock;
import milkucha.trmt.block.ErodedSandBlock;
import milkucha.trmt.client.network.ClientErosionCache;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

public final class ErosionProvider implements IBlockComponentProvider {

    public static final ErosionProvider INSTANCE = new ErosionProvider();
    private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(TRMT.MOD_ID, "erosion");

    private ErosionProvider() {}

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        BlockState state = accessor.getBlockState();
        Block block = state.getBlock();
        ClientErosionCache.Entry entry = ClientErosionCache.getInstance().getEntry(accessor.getPosition());

        // Pull the displayed stage from the block state for eroded blocks — the cache
        // entry's `stage` field is only meaningful for vanilla grass tracked pre-transition
        // and stays stale (always 1) on cooldown entries for already-eroded blocks.
        String stageLine = null;
        if (block == TRMTBlocks.ERODED_GRASS_BLOCK.get()) {
            stageLine = "Erosion stage: " + (state.getValue(ErodedGrassBlock.STAGE) + 1) + " / 5";
        } else if (block == TRMTBlocks.ERODED_DIRT.get()) {
            stageLine = "Erosion stage: " + (state.getValue(ErodedDirtBlock.STAGE) + 1) + " / 4";
        } else if (block == TRMTBlocks.ERODED_COARSE_DIRT.get()) {
            stageLine = "Fully eroded";
        } else if (block == TRMTBlocks.ERODED_SAND.get()) {
            stageLine = "Erosion stage: " + (state.getValue(ErodedSandBlock.STAGE) + 1) + " / 5";
        }

        if (stageLine != null) {
            tooltip.add(Component.literal(stageLine));
        }
        if (entry != null && entry.threshold > 0f && entry.walkedOnCount > 0f) {
            tooltip.add(Component.literal(String.format("Walked: %.1f / %.1f", entry.walkedOnCount, entry.threshold)));
        }
    }
}
