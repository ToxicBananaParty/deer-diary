package milkucha.trmt;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;

/**
 * Central registry of TRMT's tag identifiers — both block tags and entity-type
 * tags. Modpack authors and other mods can add to these tags via datapack to
 * change TRMT's behavior without code changes.
 *
 * <p><b>API stability:</b> tag names declared in this class are part of the
 * TRMT 1.x public contract. Tags may be added without a major bump; tags
 * may be removed or renamed only in a 2.x release.
 */
public final class TRMTTags {

    private TRMTTags() {}

    // ── Block tags ─────────────────────────────────────────────────────────

    /**
     * Blocks that participate in the grass erosion chain. Walking on a block
     * in this tag accumulates erosion progress; if the block is a "fresh"
     * grass-source (i.e. not one of TRMT's own eroded blocks), the transform
     * step converts it to {@code trmt:eroded_grass_block} stage 0.
     * Default members: {@code minecraft:grass_block},
     * {@code trmt:eroded_grass_block}.
     */
    public static final TagKey<Block> ERODES_AS_GRASS  = blockTag("erodes_as_grass");

    /**
     * Blocks that participate in the dirt erosion chain. Includes
     * {@code minecraft:dirt} (fresh source → eroded_dirt stage 1) and TRMT's
     * own {@code eroded_dirt} / {@code eroded_dirt_path}. Tag inclusion of
     * other blocks (e.g. modded {@code rich_soil}) treats them as fresh
     * dirt sources.
     */
    public static final TagKey<Block> ERODES_AS_DIRT   = blockTag("erodes_as_dirt");

    /**
     * Blocks that participate in the sand erosion chain. Default members:
     * {@code minecraft:sand}, {@code trmt:eroded_sand}.
     */
    public static final TagKey<Block> ERODES_AS_SAND   = blockTag("erodes_as_sand");

    /**
     * Modded "leaves-like" blocks that should be handled by the leaves
     * erosion path (destroy on threshold, optional drops). Default empty —
     * vanilla {@code LeavesBlock} subclasses are recognised via
     * {@code instanceof} as a fallback. Useful for non-{@code LeavesBlock}
     * modded foliage.
     */
    public static final TagKey<Block> ERODES_AS_LEAVES = blockTag("erodes_as_leaves");

    /**
     * Blocks which, when sitting directly above a tracked ground block,
     * prevent that ground block from accumulating erosion progress. Default
     * members: {@code #minecraft:saplings}, {@code #minecraft:crops},
     * {@code #minecraft:flowers}, plus {@code minecraft:sweet_berry_bush},
     * {@code minecraft:bamboo}, {@code minecraft:bamboo_sapling},
     * {@code minecraft:sugar_cane}, {@code minecraft:cactus}.
     *
     * <p>Used by {@link milkucha.trmt.erosion.EntityStepHandler#hasProtectedPlantAbove}.
     * Modpacks can extend this tag to add their own grow-cycle plants.
     */
    public static final TagKey<Block> PROTECTS_BELOW_FROM_EROSION = blockTag("protects_below_from_erosion");

    // ── Entity-type tags ───────────────────────────────────────────────────

    /**
     * Entity types whose ground steps trigger trampling on tracked blocks.
     * Default members: villager, horse, donkey, mule, llama, trader_llama.
     * Multipliers per entity type are configured via
     * {@code erosionMultipliers.tramples} in {@code trmt.json}.
     */
    public static final TagKey<EntityType<?>> TRAMPLES = entityTag("tramples");

    // ── helpers ────────────────────────────────────────────────────────────

    private static TagKey<Block> blockTag(String path) {
        return TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(TRMT.MOD_ID, path));
    }

    private static TagKey<EntityType<?>> entityTag(String path) {
        return TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(TRMT.MOD_ID, path));
    }
}
