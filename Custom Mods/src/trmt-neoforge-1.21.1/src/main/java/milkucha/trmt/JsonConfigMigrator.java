package milkucha.trmt;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One-shot migrator that imports values from a pre-1.1 {@code trmt.json}
 * into the live {@link TRMTConfig} ModConfigSpec. After a successful
 * migration the JSON file is renamed to {@code trmt.json.migrated.bak}
 * so the user can audit it before deleting.
 *
 * <p>Runs once during mod init via {@link milkucha.trmt.TRMT}. Idempotent —
 * if the JSON file isn't present (clean install / already migrated), it
 * returns silently.
 *
 * <p>Unknown fields in the JSON are ignored. Missing fields fall through
 * to spec defaults. Malformed JSON logs an error and aborts (file is left
 * in place so the user can investigate).
 */
public final class JsonConfigMigrator {

    private JsonConfigMigrator() {}

    public static void migrateIfPresent() {
        Path json = FMLPaths.CONFIGDIR.get().resolve("trmt.json");
        if (!Files.exists(json)) return;

        JsonObject root;
        try (Reader r = Files.newBufferedReader(json)) {
            JsonElement parsed = JsonParser.parseReader(r);
            if (!parsed.isJsonObject()) {
                TRMT.LOGGER.error("[TRMT] Old trmt.json is not a JSON object — skipping migration.");
                return;
            }
            root = parsed.getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            TRMT.LOGGER.error("[TRMT] Failed to parse old trmt.json — leaving file in place for manual recovery.", e);
            return;
        }

        TRMT.LOGGER.info("[TRMT] Migrating pre-1.1 trmt.json → trmt-common.toml ...");

        applyErosionToggles(root.getAsJsonObject("erosion"));
        applyMultipliers(root.getAsJsonObject("erosionMultipliers"));
        applyThresholds(root.getAsJsonObject("erosionThresholds"));
        applyDeErosion(root.getAsJsonObject("deErosionTimeoutDays"));
        applySeasons(root.getAsJsonObject("seasonsMultipliers"));
        applyDimensions(root.getAsJsonObject("dimensions"));

        TRMTConfig.Setters.save();

        Path backup = json.resolveSibling("trmt.json.migrated.bak");
        try {
            Files.move(json, backup, StandardCopyOption.REPLACE_EXISTING);
            TRMT.LOGGER.info("[TRMT] Migration complete. Old config preserved as {}", backup);
        } catch (IOException e) {
            TRMT.LOGGER.warn("[TRMT] Migration completed but couldn't rename trmt.json — delete it manually after confirming trmt-common.toml looks right.", e);
        }
    }

    // ── per-section appliers ───────────────────────────────────────────────

    private static void applyErosionToggles(JsonObject o) {
        if (o == null) return;
        bool(o, "grassEnabled",              TRMTConfig.Setters::grassEnabled);
        bool(o, "dirtEnabled",               TRMTConfig.Setters::dirtEnabled);
        bool(o, "sandEnabled",               TRMTConfig.Setters::sandEnabled);
        bool(o, "leavesEnabled",             TRMTConfig.Setters::leavesEnabled);
        bool(o, "vegetationEnabled",         TRMTConfig.Setters::vegetationEnabled);
        bool(o, "mobTramplingEnabled",       TRMTConfig.Setters::mobTramplingEnabled);
        bool(o, "dirtPathEndpoint",          TRMTConfig.Setters::dirtPathEndpoint);
        bool(o, "pauseDeErosionWhenEmpty",   TRMTConfig.Setters::pauseDeErosionWhenEmpty);
        bool(o, "allowInForcedChunks",       TRMTConfig.Setters::allowInForcedChunks);
    }

    private static void applyMultipliers(JsonObject o) {
        if (o == null) return;
        dbl(o, "player",         TRMTConfig.Setters::player);
        dbl(o, "mounted",        TRMTConfig.Setters::mounted);
        dbl(o, "defaultTrample", TRMTConfig.Setters::defaultTrample);

        // Translate deprecated villager / horse fields + the pre-1.1 `tramples`
        // map (if present, as a JsonObject of id → float) into the new
        // List<"id=value"> shape.
        List<String> entries = new ArrayList<>();
        if (o.has("tramples") && o.get("tramples").isJsonObject()) {
            JsonObject t = o.getAsJsonObject("tramples");
            for (Map.Entry<String, JsonElement> entry : t.entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    entries.add(entry.getKey() + "=" + entry.getValue().getAsDouble());
                }
            }
        }
        if (o.has("villager") && o.get("villager").isJsonPrimitive()) {
            entries.add("minecraft:villager=" + o.get("villager").getAsDouble());
        }
        if (o.has("horse") && o.get("horse").isJsonPrimitive()) {
            entries.add("minecraft:horse=" + o.get("horse").getAsDouble());
        }
        if (!entries.isEmpty()) TRMTConfig.Setters.tramples(entries);
    }

    private static void applyThresholds(JsonObject o) {
        if (o == null) return;
        JsonObject grass = o.getAsJsonObject("grass");
        if (grass != null) {
            dbl(grass, "min", TRMTConfig.Setters::grassThresholdMin);
            dbl(grass, "max", TRMTConfig.Setters::grassThresholdMax);
        }
        JsonObject dirt = o.getAsJsonObject("dirt");
        if (dirt != null) {
            dbl(dirt, "min", TRMTConfig.Setters::dirtThresholdMin);
            dbl(dirt, "max", TRMTConfig.Setters::dirtThresholdMax);
        }
        JsonObject coarse = o.getAsJsonObject("coarseDirt");
        if (coarse != null) {
            dbl(coarse, "min", TRMTConfig.Setters::coarseDirtThresholdMin);
            dbl(coarse, "max", TRMTConfig.Setters::coarseDirtThresholdMax);
        }
        JsonObject sand = o.getAsJsonObject("sand");
        if (sand != null) {
            dbl(sand, "min", TRMTConfig.Setters::sandThresholdMin);
            dbl(sand, "max", TRMTConfig.Setters::sandThresholdMax);
        }
        JsonObject veg = o.getAsJsonObject("vegetation");
        if (veg != null) {
            dbl(veg, "min",        TRMTConfig.Setters::vegThresholdMin);
            dbl(veg, "max",        TRMTConfig.Setters::vegThresholdMax);
            dbl(veg, "dropChance", TRMTConfig.Setters::vegDropChance);
        }
        JsonObject leaves = o.getAsJsonObject("leaves");
        if (leaves != null) {
            dbl(leaves, "min",        TRMTConfig.Setters::leavesThresholdMin);
            dbl(leaves, "max",        TRMTConfig.Setters::leavesThresholdMax);
            dbl(leaves, "dropChance", TRMTConfig.Setters::leavesDropChance);
        }
    }

    private static void applyDeErosion(JsonObject o) {
        if (o == null) return;
        JsonObject grass = o.getAsJsonObject("grass");
        if (grass != null) {
            dbl(grass, "stage1", TRMTConfig.Setters::grassDeStage1);
            dbl(grass, "stage2", TRMTConfig.Setters::grassDeStage2);
            dbl(grass, "stage3", TRMTConfig.Setters::grassDeStage3);
            dbl(grass, "stage4", TRMTConfig.Setters::grassDeStage4);
            dbl(grass, "stage5", TRMTConfig.Setters::grassDeStage5);
        }
        JsonObject dirt = o.getAsJsonObject("dirt");
        if (dirt != null) {
            dbl(dirt, "erodedDirt",       TRMTConfig.Setters::dirtDeErodedDirt);
            dbl(dirt, "erodedCoarseDirt", TRMTConfig.Setters::dirtDeErodedCoarseDirt);
        }
        JsonObject sand = o.getAsJsonObject("sand");
        if (sand != null) {
            dbl(sand, "stage1", TRMTConfig.Setters::sandDeStage1);
            dbl(sand, "stage2", TRMTConfig.Setters::sandDeStage2);
            dbl(sand, "stage3", TRMTConfig.Setters::sandDeStage3);
            dbl(sand, "stage4", TRMTConfig.Setters::sandDeStage4);
            dbl(sand, "stage5", TRMTConfig.Setters::sandDeStage5);
        }
        dbl(o, "dirtPath", TRMTConfig.Setters::dirtPathDe);
    }

    private static void applySeasons(JsonObject o) {
        if (o == null) return;
        bool(o, "enabled", TRMTConfig.Setters::seasonsEnabled);
        dbl(o, "winter",   TRMTConfig.Setters::seasonWinter);
        dbl(o, "spring",   TRMTConfig.Setters::seasonSpring);
        dbl(o, "summer",   TRMTConfig.Setters::seasonSummer);
        dbl(o, "autumn",   TRMTConfig.Setters::seasonAutumn);
    }

    private static void applyDimensions(JsonObject o) {
        if (o == null) return;
        if (o.has("mode") && o.get("mode").isJsonPrimitive()) {
            try {
                TRMTConfig.Setters.dimensionMode(TRMTConfig.DimensionMode.valueOf(o.get("mode").getAsString()));
            } catch (IllegalArgumentException ignored) {}
        }
        if (o.has("list") && o.get("list").isJsonArray()) {
            List<String> list = new ArrayList<>();
            o.getAsJsonArray("list").forEach(je -> {
                if (je.isJsonPrimitive()) list.add(je.getAsString());
            });
            TRMTConfig.Setters.dimensionList(list);
        }
    }

    // ── tiny helpers ───────────────────────────────────────────────────────

    private interface BoolSetter { void set(boolean v); }
    private interface DoubleSetter { void set(double v); }

    private static void bool(JsonObject o, String key, BoolSetter setter) {
        if (o.has(key) && o.get(key).isJsonPrimitive()) {
            try { setter.set(o.get(key).getAsBoolean()); } catch (UnsupportedOperationException ignored) {}
        }
    }

    private static void dbl(JsonObject o, String key, DoubleSetter setter) {
        if (o.has(key) && o.get(key).isJsonPrimitive()) {
            try { setter.set(o.get(key).getAsDouble()); } catch (UnsupportedOperationException ignored) {}
        }
    }
}
