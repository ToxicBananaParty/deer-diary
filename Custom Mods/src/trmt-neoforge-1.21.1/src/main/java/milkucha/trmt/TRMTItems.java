package milkucha.trmt;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class TRMTItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(TRMT.MOD_ID);

    public static final DeferredItem<BlockItem> ERODED_DIRT = ITEMS.registerSimpleBlockItem(TRMTBlocks.ERODED_DIRT);
    public static final DeferredItem<BlockItem> ERODED_COARSE_DIRT = ITEMS.registerSimpleBlockItem(TRMTBlocks.ERODED_COARSE_DIRT);
    public static final DeferredItem<BlockItem> ERODED_GRASS_BLOCK = ITEMS.registerSimpleBlockItem(TRMTBlocks.ERODED_GRASS_BLOCK);
    public static final DeferredItem<BlockItem> ERODED_SAND = ITEMS.registerSimpleBlockItem(TRMTBlocks.ERODED_SAND);

    private TRMTItems() {}
}
