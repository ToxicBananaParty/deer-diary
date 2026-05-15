package milkucha.ddc.state;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import milkucha.ddc.DeerDiaryCommands;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-wide list of muted player UUIDs with optional expiry timestamps
 * (epoch millis; null/0 means permanent until unmuted).
 *
 * Persisted as JSON in the world's data directory so mutes survive restarts.
 * Concurrency: writes funnel through {@link #set} / {@link #remove} which
 * mutate the underlying ConcurrentHashMap and trigger a save.
 */
public final class MuteState {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "ddc_mutes.json";

    private static MuteState instance;

    private final Path storage;
    private final Map<UUID, Long> mutedUntil = new ConcurrentHashMap<>();

    private MuteState(Path storage) {
        this.storage = storage;
    }

    public static synchronized MuteState getInstance() {
        if (instance == null) {
            throw new IllegalStateException("MuteState accessed before server start");
        }
        return instance;
    }

    public static synchronized void onServerStarted(MinecraftServer server) {
        Path dir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            DeerDiaryCommands.LOGGER.error("[DDC] Failed to create data dir for mute state", e);
        }
        instance = new MuteState(dir.resolve(FILE_NAME));
        instance.load();
    }

    public static synchronized void onServerStopped() {
        if (instance != null) {
            instance.save();
            instance = null;
        }
    }

    /** True if the player is currently muted (and the mute has not expired). */
    public boolean isMuted(UUID id) {
        Long expiry = mutedUntil.get(id);
        if (expiry == null) return false;
        if (expiry > 0 && System.currentTimeMillis() >= expiry) {
            mutedUntil.remove(id);
            save();
            return false;
        }
        return true;
    }

    /**
     * Mute a player. {@code durationMillis} = 0 (or negative) means permanent.
     * Returns the expiry epoch-millis (or 0 if permanent).
     */
    public long set(UUID id, long durationMillis) {
        long expiry = durationMillis > 0 ? System.currentTimeMillis() + durationMillis : 0L;
        mutedUntil.put(id, expiry);
        save();
        return expiry;
    }

    /** Returns true if the player was muted (and is now not). */
    public boolean remove(UUID id) {
        boolean changed = mutedUntil.remove(id) != null;
        if (changed) save();
        return changed;
    }

    public Map<UUID, Long> snapshot() {
        return new HashMap<>(mutedUntil);
    }

    private void load() {
        if (!Files.isRegularFile(storage)) return;
        try {
            String json = Files.readString(storage);
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            if (root == null) return;
            mutedUntil.clear();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                try {
                    UUID id = UUID.fromString(entry.getKey());
                    long expiry = entry.getValue().getAsLong();
                    if (expiry > 0 && System.currentTimeMillis() >= expiry) continue;
                    mutedUntil.put(id, expiry);
                } catch (Exception ex) {
                    DeerDiaryCommands.LOGGER.warn("[DDC] Skipping malformed mute entry {}: {}",
                        entry.getKey(), ex.getMessage());
                }
            }
            DeerDiaryCommands.LOGGER.info("[DDC] Loaded {} mute(s) from {}", mutedUntil.size(), storage);
        } catch (IOException e) {
            DeerDiaryCommands.LOGGER.error("[DDC] Failed to load mute state from {}", storage, e);
        }
    }

    private void save() {
        try {
            JsonObject root = new JsonObject();
            for (Map.Entry<UUID, Long> e : mutedUntil.entrySet()) {
                root.addProperty(e.getKey().toString(), e.getValue());
            }
            Files.writeString(storage, GSON.toJson(root));
        } catch (IOException ex) {
            DeerDiaryCommands.LOGGER.error("[DDC] Failed to save mute state to {}", storage, ex);
        }
    }
}
