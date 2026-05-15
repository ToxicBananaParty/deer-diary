package milkucha.trmt.client.network;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side cache of erosion data received from the server.
 * Entries are partitioned by dimension so that crossing portals doesn't
 * leak erosion data between Overworld / Nether / End / custom dims.
 * Thread-safe: written on the Netty receive thread, read on the render thread.
 */
public final class ClientErosionCache {

    /** All erosion data the client knows about for one block position. */
    public static final class Entry {
        public final int   stage;
        public final float walkedOnCount;
        public final float threshold;
        public final long  lastTouchedGameTime;

        public Entry(int stage, float walkedOnCount, float threshold, long lastTouchedGameTime) {
            this.stage               = stage;
            this.walkedOnCount       = walkedOnCount;
            this.threshold           = threshold;
            this.lastTouchedGameTime = lastTouchedGameTime;
        }
    }

    private static final ClientErosionCache INSTANCE = new ClientErosionCache();

    private final ConcurrentHashMap<ResourceKey<Level>, ConcurrentHashMap<ChunkPos, Map<BlockPos, Entry>>> dimChunks =
        new ConcurrentHashMap<>();

    private ClientErosionCache() {}

    public static ClientErosionCache getInstance() {
        return INSTANCE;
    }

    private ConcurrentHashMap<ChunkPos, Map<BlockPos, Entry>> chunksFor(ResourceKey<Level> dim) {
        return dimChunks.computeIfAbsent(dim, k -> new ConcurrentHashMap<>());
    }

    /** Returns the erosion stage at {@code pos} in the client player's current dimension, or 0. */
    public int getStage(BlockPos pos) {
        Entry e = getEntry(pos);
        return e != null ? e.stage : 0;
    }

    /** Returns the full entry for {@code pos} in the client player's current dimension, or null. */
    public Entry getEntry(BlockPos pos) {
        Level level = Minecraft.getInstance().level;
        if (level == null) return null;
        return getEntry(level.dimension(), pos);
    }

    public Entry getEntry(ResourceKey<Level> dim, BlockPos pos) {
        Map<ChunkPos, Map<BlockPos, Entry>> chunks = dimChunks.get(dim);
        if (chunks == null) return null;
        Map<BlockPos, Entry> chunk = chunks.get(new ChunkPos(pos));
        if (chunk == null) return null;
        return chunk.get(pos);
    }

    /** Replaces all data for a chunk in the given dimension (received on join / chunk-watch). */
    public void setChunk(ResourceKey<Level> dim, ChunkPos chunkPos, Map<BlockPos, Entry> chunkEntries) {
        ConcurrentHashMap<ChunkPos, Map<BlockPos, Entry>> chunks = chunksFor(dim);
        if (chunkEntries.isEmpty()) {
            chunks.remove(chunkPos);
        } else {
            chunks.put(chunkPos, new HashMap<>(chunkEntries));
        }
    }

    /**
     * Updates (or removes) data for a single block in the given dimension.
     * stage <= 0 clears the entry.
     */
    public void setEntry(ResourceKey<Level> dim, BlockPos pos, int stage, float walkedOnCount, float threshold, long lastTouchedGameTime) {
        ChunkPos chunkPos = new ChunkPos(pos);
        ConcurrentHashMap<ChunkPos, Map<BlockPos, Entry>> chunks = chunksFor(dim);
        if (stage <= 0) {
            Map<BlockPos, Entry> chunk = chunks.get(chunkPos);
            if (chunk != null) {
                chunk.remove(pos);
                if (chunk.isEmpty()) chunks.remove(chunkPos);
            }
        } else {
            chunks.computeIfAbsent(chunkPos, k -> new ConcurrentHashMap<>())
                  .put(pos.immutable(), new Entry(stage, walkedOnCount, threshold, lastTouchedGameTime));
        }
    }

    /** Clears all cached data (called on server disconnect). */
    public void clear() {
        dimChunks.clear();
    }
}
