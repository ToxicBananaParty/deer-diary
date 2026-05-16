package milkucha.trmt;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec.BooleanValue;
import net.neoforged.neoforge.common.ModConfigSpec.ConfigValue;
import net.neoforged.neoforge.common.ModConfigSpec.DoubleValue;
import net.neoforged.neoforge.common.ModConfigSpec.EnumValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * TRMT configuration, backed by NeoForge's {@link ModConfigSpec}.
 *
 * <p>The on-disk file is {@code config/trmt-common.toml}. Edits via the file
 * or via an in-game GUI (Configured / Cloth Config) are auto-applied on save.
 * The mod's gameplay code reads through a cached "view" ({@link #get()}) so
 * call sites keep their compact {@code TRMTConfig.get().erosion.grassEnabled}
 * shape — the cache is refreshed from the spec on every {@link ModConfigEvent}.
 *
 * <p>Pre-1.1 users had a {@code config/trmt.json} file. A one-shot migrator
 * ({@link milkucha.trmt.JsonConfigMigrator}) reads the JSON on first load
 * after upgrade and applies the values into this spec, then renames the JSON
 * to {@code trmt.json.migrated.bak} so the user can audit/delete it.
 */
public final class TRMTConfig {

    public static final ModConfigSpec SPEC;

    // ── ConfigValue declarations ──────────────────────────────────────────

    // erosion toggles
    private static final BooleanValue GRASS_ENABLED;
    private static final BooleanValue DIRT_ENABLED;
    private static final BooleanValue SAND_ENABLED;
    private static final BooleanValue LEAVES_ENABLED;
    private static final BooleanValue VEGETATION_ENABLED;
    private static final BooleanValue MOB_TRAMPLING_ENABLED;
    private static final BooleanValue DIRT_PATH_ENDPOINT;
    private static final BooleanValue PAUSE_DE_EROSION_WHEN_EMPTY;
    private static final BooleanValue ALLOW_IN_FORCED_CHUNKS;

    // multipliers
    private static final DoubleValue PLAYER_MULT;
    private static final DoubleValue MOUNTED_MULT;
    private static final DoubleValue DEFAULT_TRAMPLE_MULT;
    private static final ConfigValue<List<? extends String>> TRAMPLES_LIST;

    // erosion thresholds (each MinMax → two doubles)
    private static final DoubleValue GRASS_THR_MIN;
    private static final DoubleValue GRASS_THR_MAX;
    private static final DoubleValue DIRT_THR_MIN;
    private static final DoubleValue DIRT_THR_MAX;
    private static final DoubleValue COARSE_DIRT_THR_MIN;
    private static final DoubleValue COARSE_DIRT_THR_MAX;
    private static final DoubleValue SAND_THR_MIN;
    private static final DoubleValue SAND_THR_MAX;
    private static final DoubleValue VEG_THR_MIN;
    private static final DoubleValue VEG_THR_MAX;
    private static final DoubleValue VEG_DROP_CHANCE;
    private static final DoubleValue LEAVES_THR_MIN;
    private static final DoubleValue LEAVES_THR_MAX;
    private static final DoubleValue LEAVES_DROP_CHANCE;

    // de-erosion timeouts (in-game days)
    private static final DoubleValue GRASS_DE_STAGE1;
    private static final DoubleValue GRASS_DE_STAGE2;
    private static final DoubleValue GRASS_DE_STAGE3;
    private static final DoubleValue GRASS_DE_STAGE4;
    private static final DoubleValue GRASS_DE_STAGE5;
    private static final DoubleValue DIRT_DE_ERODED_DIRT;
    private static final DoubleValue DIRT_DE_ERODED_COARSE_DIRT;
    private static final DoubleValue SAND_DE_STAGE1;
    private static final DoubleValue SAND_DE_STAGE2;
    private static final DoubleValue SAND_DE_STAGE3;
    private static final DoubleValue SAND_DE_STAGE4;
    private static final DoubleValue SAND_DE_STAGE5;
    private static final DoubleValue DIRT_PATH_DE;

    // seasons
    private static final BooleanValue SEASONS_ENABLED;
    private static final DoubleValue SEASON_WINTER;
    private static final DoubleValue SEASON_SPRING;
    private static final DoubleValue SEASON_SUMMER;
    private static final DoubleValue SEASON_AUTUMN;

    // dimensions
    public enum DimensionMode { ALLOWLIST, BLOCKLIST }
    private static final EnumValue<DimensionMode> DIMENSION_MODE;
    private static final ConfigValue<List<? extends String>> DIMENSION_LIST;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        Predicate<Object> isString = s -> s instanceof String;

        b.comment("Master per-block-type toggles. Disabling a toggle stops both new erosion AND de-erosion of that block class.")
            .push("erosion");
        GRASS_ENABLED               = b.comment("Allow grass to erode from foot traffic.").define("grassEnabled", true);
        DIRT_ENABLED                = b.comment("Allow dirt to erode from foot traffic.").define("dirtEnabled", true);
        SAND_ENABLED                = b.comment("Allow sand to erode from foot traffic.").define("sandEnabled", true);
        LEAVES_ENABLED              = b.comment("Allow leaves to break from foot traffic.").define("leavesEnabled", true);
        VEGETATION_ENABLED          = b.comment("Allow short plants (flowers, tall grass) to break when walked through.").define("vegetationEnabled", true);
        MOB_TRAMPLING_ENABLED       = b.comment("Allow mobs (#trmt:tramples) to cause trampling in addition to players.").define("mobTramplingEnabled", true);
        DIRT_PATH_ENDPOINT          = b.comment("When true, eroded_grass_block stage 4 → eroded_dirt_path. When false, falls through to legacy eroded_dirt → eroded_coarse_dirt chain.").define("dirtPathEndpoint", true);
        PAUSE_DE_EROSION_WHEN_EMPTY = b.comment("Pause de-erosion (path healing) while no players are online — prevents chunk-loaded paths from disappearing overnight on dedicated servers.").define("pauseDeErosionWhenEmpty", true);
        ALLOW_IN_FORCED_CHUNKS      = b.comment("When false, skip erosion/de-erosion in force-loaded chunks (Chunky, chunkloaders, /forceload).").define("allowInForcedChunks", true);
        b.pop();

        b.comment("Per-source step multipliers.").push("erosionMultipliers");
        PLAYER_MULT          = b.comment("Player on foot.").defineInRange("player", 0.5, 0.0, 100.0);
        MOUNTED_MULT         = b.comment("Player riding a mob (multiplied on top of the mob's own multiplier).").defineInRange("mounted", 1.5, 0.0, 100.0);
        DEFAULT_TRAMPLE_MULT = b.comment("Fallback multiplier for any mob in #trmt:tramples without an explicit per-entity entry below.").defineInRange("defaultTrample", 0.5, 0.0, 100.0);
        TRAMPLES_LIST = b.comment(
                "Per-entity step multipliers. Format: 'entity_id=multiplier'. Examples: 'minecraft:horse=0.5', 'naturalist:deer=0.7'.",
                "Any mob in #trmt:tramples not listed here uses defaultTrample.")
            .defineListAllowEmpty("tramples", List.of(), isString);
        b.pop();

        b.comment("Step thresholds (min/max) per block type. A per-block random threshold is rolled the first time a position is tracked.")
            .push("erosionThresholds");
        b.push("grass");
            GRASS_THR_MIN = b.defineInRange("min", 2.0, 0.0, 10000.0);
            GRASS_THR_MAX = b.defineInRange("max", 4.0, 0.0, 10000.0);
        b.pop();
        b.push("dirt");
            DIRT_THR_MIN = b.defineInRange("min", 8.0, 0.0, 10000.0);
            DIRT_THR_MAX = b.defineInRange("max", 12.0, 0.0, 10000.0);
        b.pop();
        b.push("coarseDirt");
            COARSE_DIRT_THR_MIN = b.defineInRange("min", 12.0, 0.0, 10000.0);
            COARSE_DIRT_THR_MAX = b.defineInRange("max", 20.0, 0.0, 10000.0);
        b.pop();
        b.push("sand");
            SAND_THR_MIN = b.defineInRange("min", 4.0, 0.0, 10000.0);
            SAND_THR_MAX = b.defineInRange("max", 8.0, 0.0, 10000.0);
        b.pop();
        b.push("vegetation");
            VEG_THR_MIN     = b.defineInRange("min", 2.0, 0.0, 10000.0);
            VEG_THR_MAX     = b.defineInRange("max", 3.0, 0.0, 10000.0);
            VEG_DROP_CHANCE = b.defineInRange("dropChance", 0.2, 0.0, 1.0);
        b.pop();
        b.push("leaves");
            LEAVES_THR_MIN     = b.defineInRange("min", 2.0, 0.0, 10000.0);
            LEAVES_THR_MAX     = b.defineInRange("max", 3.0, 0.0, 10000.0);
            LEAVES_DROP_CHANCE = b.defineInRange("dropChance", 0.1, 0.0, 1.0);
        b.pop();
        b.pop();

        b.comment("In-game days before each stage de-erodes back toward its source. Isolated (no neighbors) blocks de-erode in half the time.")
            .push("deErosionTimeoutDays");
        b.push("grass");
            GRASS_DE_STAGE1 = b.defineInRange("stage1", 1.0, 0.0, 365.0);
            GRASS_DE_STAGE2 = b.defineInRange("stage2", 2.0, 0.0, 365.0);
            GRASS_DE_STAGE3 = b.defineInRange("stage3", 3.0, 0.0, 365.0);
            GRASS_DE_STAGE4 = b.defineInRange("stage4", 5.0, 0.0, 365.0);
            GRASS_DE_STAGE5 = b.defineInRange("stage5", 8.0, 0.0, 365.0);
        b.pop();
        b.push("dirt");
            DIRT_DE_ERODED_DIRT        = b.defineInRange("erodedDirt", 13.0, 0.0, 365.0);
            DIRT_DE_ERODED_COARSE_DIRT = b.defineInRange("erodedCoarseDirt", 21.0, 0.0, 365.0);
        b.pop();
        b.push("sand");
            SAND_DE_STAGE1 = b.defineInRange("stage1", 1.0, 0.0, 365.0);
            SAND_DE_STAGE2 = b.defineInRange("stage2", 1.0, 0.0, 365.0);
            SAND_DE_STAGE3 = b.defineInRange("stage3", 2.0, 0.0, 365.0);
            SAND_DE_STAGE4 = b.defineInRange("stage4", 3.0, 0.0, 365.0);
            SAND_DE_STAGE5 = b.defineInRange("stage5", 5.0, 0.0, 365.0);
        b.pop();
        DIRT_PATH_DE = b.comment("Days before eroded_dirt_path reverts to eroded_grass_block stage 4.").defineInRange("dirtPath", 21.0, 0.0, 365.0);
        b.pop();

        b.comment("Seasons compat — only used if SereneSeasons is installed.").push("seasonsMultipliers");
        SEASONS_ENABLED = b.comment("Master switch for season-based erosion-rate modulation.").define("enabled", true);
        SEASON_WINTER   = b.defineInRange("winter", 0.5, 0.0, 100.0);
        SEASON_SPRING   = b.defineInRange("spring", 1.0, 0.0, 100.0);
        SEASON_SUMMER   = b.defineInRange("summer", 1.0, 0.0, 100.0);
        SEASON_AUTUMN   = b.defineInRange("autumn", 1.2, 0.0, 100.0);
        b.pop();

        b.comment("Per-dimension allow/block list. Default BLOCKLIST + empty = unrestricted.").push("dimensions");
        DIMENSION_MODE = b.comment("ALLOWLIST or BLOCKLIST.").defineEnum("mode", DimensionMode.BLOCKLIST);
        DIMENSION_LIST = b.comment("Dimension IDs (e.g. 'minecraft:overworld', 'minecraft:the_nether').")
            .defineListAllowEmpty("list", List.of(), isString);
        b.pop();

        SPEC = b.build();
    }

    // ── Cached "view" types (preserve old call-site API) ───────────────────

    public static class ErosionToggles {
        public boolean grassEnabled, dirtEnabled, sandEnabled, leavesEnabled,
                vegetationEnabled, mobTramplingEnabled, dirtPathEndpoint,
                pauseDeErosionWhenEmpty, allowInForcedChunks;
    }

    public static class Multipliers {
        public float player, mounted, defaultTrample;
        /** Per-entity-id multipliers. Rebuilt from {@link #TRAMPLES_LIST} on each config refresh. */
        public Map<String, Float> tramples = new HashMap<>();
    }

    public static class MinMax {
        public float min, max;
    }

    public static class VegetationThreshold extends MinMax {
        public float dropChance;
    }

    public static class ErosionThresholds {
        public final MinMax grass = new MinMax();
        public final MinMax dirt = new MinMax();
        public final MinMax coarseDirt = new MinMax();
        public final MinMax sand = new MinMax();
        public final VegetationThreshold vegetation = new VegetationThreshold();
        public final VegetationThreshold leaves = new VegetationThreshold();
    }

    public static class GrassDeErosion {
        public float stage1, stage2, stage3, stage4, stage5;
    }
    public static class DirtDeErosion {
        public float erodedDirt, erodedCoarseDirt;
    }
    public static class SandDeErosion {
        public float stage1, stage2, stage3, stage4, stage5;
    }
    public static class DeErosionTimeoutDays {
        public final GrassDeErosion grass = new GrassDeErosion();
        public final DirtDeErosion dirt = new DirtDeErosion();
        public final SandDeErosion sand = new SandDeErosion();
        public float dirtPath;
    }

    public static class SeasonsMultipliers {
        public boolean enabled;
        public float winter, spring, summer, autumn;
    }

    public static class Dimensions {
        public DimensionMode mode = DimensionMode.BLOCKLIST;
        public List<String> list = new ArrayList<>();
        public boolean isEnabled(String dimensionId) {
            return switch (mode) {
                case BLOCKLIST -> !list.contains(dimensionId);
                case ALLOWLIST -> list.contains(dimensionId);
            };
        }
    }

    public final ErosionToggles       erosion              = new ErosionToggles();
    public final Multipliers          erosionMultipliers   = new Multipliers();
    public final ErosionThresholds    erosionThresholds    = new ErosionThresholds();
    public final DeErosionTimeoutDays deErosionTimeoutDays = new DeErosionTimeoutDays();
    public final SeasonsMultipliers   seasonsMultipliers   = new SeasonsMultipliers();
    public final Dimensions           dimensions           = new Dimensions();

    private static final TRMTConfig INSTANCE = new TRMTConfig();
    public static TRMTConfig get() {
        return INSTANCE;
    }

    private TRMTConfig() {}

    // ── Event-driven cache refresh ─────────────────────────────────────────

    /**
     * Fires on {@link ModConfigEvent.Loading} (initial load + file edit
     * reload) and {@link ModConfigEvent.Reloading} (programmatic). Copies
     * every ConfigValue into the cached view so the rest of the codebase
     * keeps using the old {@code TRMTConfig.get().X.Y} access pattern.
     */
    @SubscribeEvent
    public static void onModConfigEvent(ModConfigEvent event) {
        if (event.getConfig().getSpec() != SPEC) return;
        refresh();
    }

    /**
     * Backwards-compat for code that used to call {@code TRMTConfig.load()}.
     * The spec is now loaded automatically by NeoForge; this just refreshes
     * the cache (useful for {@code /trmt reloadconfig} after a manual edit).
     */
    public static void load() {
        refresh();
    }

    private static void refresh() {
        ErosionToggles e = INSTANCE.erosion;
        e.grassEnabled              = GRASS_ENABLED.get();
        e.dirtEnabled               = DIRT_ENABLED.get();
        e.sandEnabled               = SAND_ENABLED.get();
        e.leavesEnabled             = LEAVES_ENABLED.get();
        e.vegetationEnabled         = VEGETATION_ENABLED.get();
        e.mobTramplingEnabled       = MOB_TRAMPLING_ENABLED.get();
        e.dirtPathEndpoint          = DIRT_PATH_ENDPOINT.get();
        e.pauseDeErosionWhenEmpty   = PAUSE_DE_EROSION_WHEN_EMPTY.get();
        e.allowInForcedChunks       = ALLOW_IN_FORCED_CHUNKS.get();

        Multipliers m = INSTANCE.erosionMultipliers;
        m.player          = PLAYER_MULT.get().floatValue();
        m.mounted         = MOUNTED_MULT.get().floatValue();
        m.defaultTrample  = DEFAULT_TRAMPLE_MULT.get().floatValue();
        Map<String, Float> newTramples = new HashMap<>();
        for (String entry : TRAMPLES_LIST.get()) {
            int eq = entry.indexOf('=');
            if (eq < 1) continue;
            try {
                newTramples.put(entry.substring(0, eq).trim(), Float.parseFloat(entry.substring(eq + 1).trim()));
            } catch (NumberFormatException nfe) {
                TRMT.LOGGER.warn("[TRMT] Skipping malformed tramples entry '{}': {}", entry, nfe.getMessage());
            }
        }
        m.tramples = newTramples;

        ErosionThresholds th = INSTANCE.erosionThresholds;
        th.grass.min       = GRASS_THR_MIN.get().floatValue();
        th.grass.max       = GRASS_THR_MAX.get().floatValue();
        th.dirt.min        = DIRT_THR_MIN.get().floatValue();
        th.dirt.max        = DIRT_THR_MAX.get().floatValue();
        th.coarseDirt.min  = COARSE_DIRT_THR_MIN.get().floatValue();
        th.coarseDirt.max  = COARSE_DIRT_THR_MAX.get().floatValue();
        th.sand.min        = SAND_THR_MIN.get().floatValue();
        th.sand.max        = SAND_THR_MAX.get().floatValue();
        th.vegetation.min  = VEG_THR_MIN.get().floatValue();
        th.vegetation.max  = VEG_THR_MAX.get().floatValue();
        th.vegetation.dropChance = VEG_DROP_CHANCE.get().floatValue();
        th.leaves.min      = LEAVES_THR_MIN.get().floatValue();
        th.leaves.max      = LEAVES_THR_MAX.get().floatValue();
        th.leaves.dropChance = LEAVES_DROP_CHANCE.get().floatValue();

        DeErosionTimeoutDays dt = INSTANCE.deErosionTimeoutDays;
        dt.grass.stage1 = GRASS_DE_STAGE1.get().floatValue();
        dt.grass.stage2 = GRASS_DE_STAGE2.get().floatValue();
        dt.grass.stage3 = GRASS_DE_STAGE3.get().floatValue();
        dt.grass.stage4 = GRASS_DE_STAGE4.get().floatValue();
        dt.grass.stage5 = GRASS_DE_STAGE5.get().floatValue();
        dt.dirt.erodedDirt       = DIRT_DE_ERODED_DIRT.get().floatValue();
        dt.dirt.erodedCoarseDirt = DIRT_DE_ERODED_COARSE_DIRT.get().floatValue();
        dt.sand.stage1 = SAND_DE_STAGE1.get().floatValue();
        dt.sand.stage2 = SAND_DE_STAGE2.get().floatValue();
        dt.sand.stage3 = SAND_DE_STAGE3.get().floatValue();
        dt.sand.stage4 = SAND_DE_STAGE4.get().floatValue();
        dt.sand.stage5 = SAND_DE_STAGE5.get().floatValue();
        dt.dirtPath = DIRT_PATH_DE.get().floatValue();

        SeasonsMultipliers s = INSTANCE.seasonsMultipliers;
        s.enabled = SEASONS_ENABLED.get();
        s.winter  = SEASON_WINTER.get().floatValue();
        s.spring  = SEASON_SPRING.get().floatValue();
        s.summer  = SEASON_SUMMER.get().floatValue();
        s.autumn  = SEASON_AUTUMN.get().floatValue();

        Dimensions d = INSTANCE.dimensions;
        d.mode = DIMENSION_MODE.get();
        List<String> newList = new ArrayList<>(DIMENSION_LIST.get().size());
        for (String s2 : DIMENSION_LIST.get()) newList.add(s2);
        d.list = newList;
    }

    // ── Setters used by the JSON migrator ──────────────────────────────────

    /**
     * Internal: used by {@link JsonConfigMigrator} to apply values from a
     * pre-1.1 {@code trmt.json} into the live spec. Set-then-save semantics
     * — each call triggers a TOML write, so prefer batching.
     */
    static final class Setters {
        private Setters() {}
        static void grassEnabled(boolean v)             { GRASS_ENABLED.set(v); }
        static void dirtEnabled(boolean v)              { DIRT_ENABLED.set(v); }
        static void sandEnabled(boolean v)              { SAND_ENABLED.set(v); }
        static void leavesEnabled(boolean v)            { LEAVES_ENABLED.set(v); }
        static void vegetationEnabled(boolean v)        { VEGETATION_ENABLED.set(v); }
        static void mobTramplingEnabled(boolean v)      { MOB_TRAMPLING_ENABLED.set(v); }
        static void dirtPathEndpoint(boolean v)         { DIRT_PATH_ENDPOINT.set(v); }
        static void pauseDeErosionWhenEmpty(boolean v)  { PAUSE_DE_EROSION_WHEN_EMPTY.set(v); }
        static void allowInForcedChunks(boolean v)      { ALLOW_IN_FORCED_CHUNKS.set(v); }

        static void player(double v)         { PLAYER_MULT.set(v); }
        static void mounted(double v)        { MOUNTED_MULT.set(v); }
        static void defaultTrample(double v) { DEFAULT_TRAMPLE_MULT.set(v); }
        static void tramples(List<String> v) { TRAMPLES_LIST.set(v); }

        static void grassThresholdMin(double v)      { GRASS_THR_MIN.set(v); }
        static void grassThresholdMax(double v)      { GRASS_THR_MAX.set(v); }
        static void dirtThresholdMin(double v)       { DIRT_THR_MIN.set(v); }
        static void dirtThresholdMax(double v)       { DIRT_THR_MAX.set(v); }
        static void coarseDirtThresholdMin(double v) { COARSE_DIRT_THR_MIN.set(v); }
        static void coarseDirtThresholdMax(double v) { COARSE_DIRT_THR_MAX.set(v); }
        static void sandThresholdMin(double v)       { SAND_THR_MIN.set(v); }
        static void sandThresholdMax(double v)       { SAND_THR_MAX.set(v); }
        static void vegThresholdMin(double v)        { VEG_THR_MIN.set(v); }
        static void vegThresholdMax(double v)        { VEG_THR_MAX.set(v); }
        static void vegDropChance(double v)          { VEG_DROP_CHANCE.set(v); }
        static void leavesThresholdMin(double v)     { LEAVES_THR_MIN.set(v); }
        static void leavesThresholdMax(double v)     { LEAVES_THR_MAX.set(v); }
        static void leavesDropChance(double v)       { LEAVES_DROP_CHANCE.set(v); }

        static void grassDeStage1(double v) { GRASS_DE_STAGE1.set(v); }
        static void grassDeStage2(double v) { GRASS_DE_STAGE2.set(v); }
        static void grassDeStage3(double v) { GRASS_DE_STAGE3.set(v); }
        static void grassDeStage4(double v) { GRASS_DE_STAGE4.set(v); }
        static void grassDeStage5(double v) { GRASS_DE_STAGE5.set(v); }
        static void dirtDeErodedDirt(double v)       { DIRT_DE_ERODED_DIRT.set(v); }
        static void dirtDeErodedCoarseDirt(double v) { DIRT_DE_ERODED_COARSE_DIRT.set(v); }
        static void sandDeStage1(double v) { SAND_DE_STAGE1.set(v); }
        static void sandDeStage2(double v) { SAND_DE_STAGE2.set(v); }
        static void sandDeStage3(double v) { SAND_DE_STAGE3.set(v); }
        static void sandDeStage4(double v) { SAND_DE_STAGE4.set(v); }
        static void sandDeStage5(double v) { SAND_DE_STAGE5.set(v); }
        static void dirtPathDe(double v)   { DIRT_PATH_DE.set(v); }

        static void seasonsEnabled(boolean v) { SEASONS_ENABLED.set(v); }
        static void seasonWinter(double v) { SEASON_WINTER.set(v); }
        static void seasonSpring(double v) { SEASON_SPRING.set(v); }
        static void seasonSummer(double v) { SEASON_SUMMER.set(v); }
        static void seasonAutumn(double v) { SEASON_AUTUMN.set(v); }

        static void dimensionMode(DimensionMode v) { DIMENSION_MODE.set(v); }
        static void dimensionList(List<String> v)  { DIMENSION_LIST.set(v); }

        static void save() { SPEC.save(); }
    }
}
