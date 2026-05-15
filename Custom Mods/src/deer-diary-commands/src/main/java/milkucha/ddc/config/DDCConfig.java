package milkucha.ddc.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import milkucha.ddc.DeerDiaryCommands;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Single JSON config file at {@code config/deer_diary_commands.json}.
 * Following trmt's convention: hand-edited Gson-backed config, no
 * ModConfigSpec. Reload on next server start.
 */
public final class DDCConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "deer_diary_commands.json";

    private static DDCConfig INSTANCE = new DDCConfig();

    public Rtp rtp = new Rtp();

    public static final class Rtp {
        public int cooldown_seconds = 600;
        public int min_distance = 500;
        public int max_distance = 12500;
        public int max_tries = 100;
        public List<String> dimension_whitelist = List.of("minecraft:overworld");
        public List<String> dimension_blacklist = List.of("minecraft:the_end");
    }

    public static DDCConfig get() {
        return INSTANCE;
    }

    public static void load() {
        Path path = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
        if (!Files.isRegularFile(path)) {
            INSTANCE = new DDCConfig();
            save(path, INSTANCE);
            DeerDiaryCommands.LOGGER.info("[DDC] Wrote default config to {}", path);
            return;
        }
        try {
            String json = Files.readString(path);
            DDCConfig parsed = GSON.fromJson(json, DDCConfig.class);
            if (parsed == null) parsed = new DDCConfig();
            INSTANCE = parsed;
            DeerDiaryCommands.LOGGER.info("[DDC] Loaded config from {}", path);
        } catch (JsonSyntaxException | IOException e) {
            DeerDiaryCommands.LOGGER.error("[DDC] Failed to read config at {} — using defaults", path, e);
            INSTANCE = new DDCConfig();
        }
    }

    private static void save(Path path, DDCConfig cfg) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(cfg));
        } catch (IOException e) {
            DeerDiaryCommands.LOGGER.error("[DDC] Failed to write default config to {}", path, e);
        }
    }
}
