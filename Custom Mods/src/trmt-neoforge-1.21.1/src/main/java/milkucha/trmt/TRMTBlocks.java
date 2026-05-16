package milkucha.trmt;

import milkucha.trmt.block.ErodedDirtBlock;
import milkucha.trmt.block.ErodedDirtPathBlock;
import milkucha.trmt.block.ErodedGrassBlock;
import milkucha.trmt.block.ErodedSandBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class TRMTBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(TRMT.MOD_ID);

    public static final DeferredBlock<Block> ERODED_DIRT = BLOCKS.register("eroded_dirt",
        () -> new ErodedDirtBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.DIRT).strength(0.5F).sound(SoundType.GRAVEL).randomTicks()));

    public static final DeferredBlock<Block> ERODED_COARSE_DIRT = BLOCKS.register("eroded_coarse_dirt",
        () -> new ErodedDirtBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.DIRT).strength(0.5F).sound(SoundType.GRAVEL).randomTicks()));

    public static final DeferredBlock<Block> ERODED_GRASS_BLOCK = BLOCKS.register("eroded_grass_block",
        () -> new ErodedGrassBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.DIRT).strength(0.6F).sound(SoundType.GRASS).randomTicks()));

    public static final DeferredBlock<Block> ERODED_SAND = BLOCKS.register("eroded_sand",
        () -> new ErodedSandBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.TERRACOTTA_YELLOW).strength(0.5F).sound(SoundType.SAND).noOcclusion().randomTicks()));

    // Erosion-derived dirt path. ofFullCopy gives us vanilla's hardness/sound/etc, but the loot
    // table is NOT actually inherited: vanilla DIRT_PATH leaves lootTableSupplier null and relies
    // on the default registry-id lookup, so without lootFrom() our block looks up
    // trmt:blocks/eroded_dirt_path (which doesn't exist) and drops nothing. lootFrom() pins the
    // supplier to vanilla DIRT_PATH's loot table so this drops dirt on break, exactly like vanilla.
    // randomTicks() is required because vanilla DirtPathBlock isn't natively random-ticked.
    public static final DeferredBlock<Block> ERODED_DIRT_PATH = BLOCKS.register("eroded_dirt_path",
        () -> new ErodedDirtPathBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.DIRT_PATH)
            .lootFrom(() -> Blocks.DIRT_PATH)
            .randomTicks()));

    private TRMTBlocks() {}
}
