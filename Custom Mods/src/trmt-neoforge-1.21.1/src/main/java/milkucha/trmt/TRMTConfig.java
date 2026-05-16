package milkucha.trmt;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mod configuration loaded from {@code config/trmt.json}.
 * Each erodable block type has a min/max step-count range; a random
 * threshold is drawn from that range the first time a position is tracked.
 *
 * <p>Edit the JSON file and restart the server (or world) to apply changes.
 */
public final class TRMTConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "trmt.json";

    // ── nested config types ────────────────────────────────────────────────

    public static class Multipliers {
        public float player   = 0.5f;
        public float mounted  = 1.5f;

        /** @deprecated Since 1.0.0. Use {@link #tramples} keyed by {@code "minecraft:villager"}. Removed in 1.1. */
        @Deprecated public float villager = 0.5f;
        /** @deprecated Since 1.0.0. Use {@link #tramples} keyed by {@code "minecraft:horse"}. Removed in 1.1. */
        @Deprecated public float horse    = 0.5f;

        /**
         * Fallback step multiplier applied to any entity in the
         * {@code #trmt:tramples} tag that doesn't have a per-entity entry in
         * {@link #tramples}.
         */
        public float defaultTrample = 0.5f;

        /**
         * Per-entity step multipliers, keyed by entity-type id (e.g.
         * {@code "minecraft:horse"}, {@code "naturalist:deer"}). Looked up
         * by {@link milkucha.trmt.mixin.MobTramplingMixin} for any entity in
         * the {@code #trmt:tramples} tag.
         * <p>Empty by default; modpack authors fill it in as needed. Missing
         * entries fall back to {@link #defaultTrample}.
         */
        public Map<String, Float> tramples = new HashMap<>();
    }

    public static class MinMax {
        public float min, max;
        MinMax(float min, float max) { this.min = min; this.max = max; }
    }

    public static class ErosionToggles {
        public boolean grassEnabled        = true;
        public boolean dirtEnabled         = true;
        public boolean sandEnabled         = true;
        public boolean leavesEnabled       = true;
        public boolean vegetationEnabled   = true;
        public boolean mobTramplingEnabled = true;
        // When true, eroded_grass_block stage 4 transitions to trmt:eroded_dirt_path
        // (a custom block that looks identical to vanilla dirt path). When false, it
        // falls through to the legacy eroded_dirt → eroded_coarse_dirt chain.
        public boolean dirtPathEndpoint    = true;
        // When true, de-erosion (eroded block reverting toward its vanilla source)
        // is paused while no players are online. Prevents chunk-loaded paths from
        // disappearing overnight on dedicated servers. Erosion (vanilla → eroded)
        // is unaffected — it only fires from player/mob steps, which can't happen
        // with no players online anyway. Grass-spreading from low-stage eroded
        // grass also still fires so the world keeps ticking normally.
        public boolean pauseDeErosionWhenEmpty = true;
        // When false, force-loaded chunks (e.g. via Chunky pre-gen, chunkloaders
        // mod blocks, or /forceload) are excluded from BOTH erosion and de-erosion.
        // Default true preserves existing behavior. Useful for builders who keep
        // their farms force-loaded and don't want quiet ground erosion under
        // automation infrastructure.
        public boolean allowInForcedChunks = true;
    }

    public static class VegetationThreshold extends MinMax {
        public float dropChance;
        VegetationThreshold(float min, float max, float dropChance) {
            super(min, max);
            this.dropChance = dropChance;
        }
    }

    public static class ErosionThresholds {
        public MinMax            grass               = new MinMax(2f, 4f);
        public MinMax            dirt                = new MinMax(8f, 12f);
        public MinMax            coarseDirt          = new MinMax(12f, 20f);
        public MinMax            sand                = new MinMax(4f, 8f);
        public VegetationThreshold vegetation        = new VegetationThreshold(2f, 3f, 0.2f);
        public VegetationThreshold leaves            = new VegetationThreshold(2f, 3f, 0.1f);
    }

    public static class GrassDeErosion {
        public float stage1 = 1f;
        public float stage2 = 2f;
        public float stage3 = 3f;
        public float stage4 = 5f;
        public float stage5 = 8f;
    }

    public static class DirtDeErosion {
        public float erodedDirt       = 13f;
        public float erodedCoarseDirt = 21f;
    }

    public static class SandDeErosion {
        public float stage1 = 1f;
        public float stage2 = 1f;
        public float stage3 = 2f;
        public float stage4 = 3f;
        public float stage5 = 5f;
    }

    public static class DeErosionTimeoutDays {
        public GrassDeErosion grass = new GrassDeErosion();
        public DirtDeErosion  dirt  = new DirtDeErosion();
        public SandDeErosion  sand  = new SandDeErosion();
        // Days before an erosion-derived dirt path (trmt:eroded_dirt_path) reverts
        // to eroded_grass_block stage 4. Defaults to match erodedCoarseDirt — dirt path
        // is the new "fully eroded" end-state when dirtPathEndpoint is enabled.
        public float dirtPath = 21f;
    }

    /**
     * Per-dimension allow/block list for erosion. Default: blocklist with no
     * entries (i.e. erosion happens everywhere). Set {@code mode} to
     * {@code "allowlist"} and populate {@code list} to restrict erosion to
     * specific dimensions (e.g. {@code ["minecraft:overworld"]}).
     */
    public static class Dimensions {
        public enum Mode { ALLOWLIST, BLOCKLIST }
        public Mode mode = Mode.BLOCKLIST;
        public List<String> list = new ArrayList<>();

        public boolean isEnabled(String dimensionId) {
            return switch (mode) {
                case BLOCKLIST -> !list.contains(dimensionId);
                case ALLOWLIST -> list.contains(dimensionId);
            };
        }
    }

    public static class SeasonsMultipliers {
        /** Master switch for SereneSeasons-driven step-multiplier modulation. */
        public boolean enabled = true;
        /** Per-season step multiplier. Lower = slower erosion in that season. */
        public float winter = 0.5f;
        public float spring = 1.0f;
        public float summer = 1.0f;
        public float autumn = 1.2f;
    }

    // ── top-level fields ───────────────────────────────────────────────────

    public ErosionToggles       erosion              = new ErosionToggles();
    public Multipliers          erosionMultipliers   = new Multipliers();
    public ErosionThresholds    erosionThresholds    = new ErosionThresholds();
    public DeErosionTimeoutDays deErosionTimeoutDays = new DeErosionTimeoutDays();
    public SeasonsMultipliers   seasonsMultipliers   = new SeasonsMultipliers();
    public Dimensions           dimensions           = new Dimensions();

    // ── singleton ──────────────────────────────────────────────────────────
    private static TRMTConfig instance = new TRMTConfig();

    private TRMTConfig() {}

    public static TRMTConfig get() {
        return instance;
    }

    // ── load / save ────────────────────────────────────────────────────────

    /**
     * Loads config from disk, or writes a default config if the file does not exist.
     * Called once from the mod constructor.
     */
    public static void load() {
        Path path = configPath();
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                TRMTConfig loaded = GSON.fromJson(reader, TRMTConfig.class);
                if (loaded != null) {
                    instance = loaded;
                    // Save back immediately so any fields added since the last run
                    // are written to disk with their default values.
                    save();
                    TRMT.LOGGER.info("[TRMT] Config loaded from {}", path);
                    return;
                }
            } catch (IOException e) {
                TRMT.LOGGER.error("[TRMT] Failed to read config, using defaults", e);
            }
        }

        // File missing or unreadable — write defaults so the user can edit them.
        save();
        TRMT.LOGGER.info("[TRMT] Default config written to {}", path);
    }

    private static void save() {
        Path path = configPath();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(instance, writer);
            }
        } catch (IOException e) {
            TRMT.LOGGER.error("[TRMT] Failed to write default config", e);
        }
    }

    private static Path configPath() {
        return FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
    }
}
