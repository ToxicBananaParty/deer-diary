package milkucha.trmt.erosion;

import milkucha.trmt.TRMT;
import milkucha.trmt.TRMTBlocks;
import milkucha.trmt.TRMTConfig;
import milkucha.trmt.block.ErodedGrassBlock;
import milkucha.trmt.network.SyncChunkPayload;
import milkucha.trmt.network.UpdateStagePayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;

public class ErosionMapManager {
    /** Max chunks to drain per player per server tick — keeps login from blowing past the packet budget. */
    private static final int SYNC_DRAIN_PER_TICK = 4;

    private static ErosionMapManager INSTANCE;
    private final Map<ResourceKey<Level>, ErosionPersistentState> states = new HashMap<>();
    private final Map<java.util.UUID, java.util.ArrayDeque<ChunkPos>> pendingSyncQueues = new HashMap<>();
    private final Map<java.util.UUID, ResourceKey<Level>> pendingSyncDims = new HashMap<>();
    private MinecraftServer server;

    private ErosionMapManager() {}

    public static ErosionMapManager getInstance() {
        if (INSTANCE == null) INSTANCE = new ErosionMapManager();
        return INSTANCE;
    }

    public void attachServer(MinecraftServer server) {
        this.server = server;
    }

    public static void reset() {
        if (INSTANCE != null) {
            INSTANCE.pendingSyncQueues.clear();
            INSTANCE.pendingSyncDims.clear();
        }
        INSTANCE = null;
    }

    private ErosionPersistentState stateFor(ServerLevel level) {
        return states.computeIfAbsent(level.dimension(), k -> ErosionPersistentState.getOrCreate(level));
    }

    public void onStep(ServerLevel level, BlockPos worldPos, Block block, float amount, long currentGameTime) {
        ErosionPersistentState state = stateFor(level);
        ChunkPos chunkPos = new ChunkPos(worldPos);
        ChunkErosionMap map = state.computeChunkMap(chunkPos);
        map.recordStep(worldPos, block, amount, currentGameTime);
        state.setDirty();
    }

    public void broadcastEntryUpdate(ServerLevel level, BlockPos pos, Block block) {
        ErosionPersistentState state = stateFor(level);
        ChunkErosionMap map = state.getChunkMap(new ChunkPos(pos));
        if (map == null) return;
        ErosionEntry entry = map.getEntry(pos);
        if (entry == null) return;
        int stage = entry.getErosionStage();
        if (stage == 0 && block == Blocks.GRASS_BLOCK) return;
        if (stage == 0) stage = 1;
        broadcastStageUpdate(level.dimension(), pos, stage, entry.getWalkedOnCount(), entry.getThreshold(), entry.getLastTouchedGameTime());
    }

    public void removeEntry(ServerLevel level, BlockPos worldPos) {
        ErosionPersistentState state = stateFor(level);
        ChunkPos chunkPos = new ChunkPos(worldPos);
        ChunkErosionMap map = state.getChunkMap(chunkPos);
        if (map == null) return;
        map.removeEntry(worldPos);
        state.removeChunkMapIfEmpty(chunkPos);
        state.setDirty();
        broadcastStageUpdate(level.dimension(), worldPos, 0, 0f, 0f, 0L);
    }

    public void markForRerender(ServerLevel level, BlockPos pos) {
        ErosionPersistentState state = stateFor(level);
        ChunkErosionMap map = state.getChunkMap(new ChunkPos(pos));
        if (map == null) return;
        ErosionEntry entry = map.getEntry(pos);
        if (entry == null) return;
        broadcastStageUpdate(level.dimension(), pos, entry.getErosionStage(), entry.getWalkedOnCount(), entry.getThreshold(), entry.getLastTouchedGameTime());
    }

    public ChunkErosionMap getChunkMap(ServerLevel level, ChunkPos chunkPos) {
        return stateFor(level).getChunkMap(chunkPos);
    }

    public void revertGrassStage(ServerLevel level, BlockPos worldPos, long currentGameTime) {
        ErosionPersistentState state = stateFor(level);
        ChunkErosionMap map = state.getChunkMap(new ChunkPos(worldPos));
        if (map == null) return;
        ErosionEntry entry = map.getEntry(worldPos);
        if (entry == null) return;
        entry.revertGrassStage(BlockThresholds.randomThreshold(Blocks.GRASS_BLOCK), currentGameTime);
        state.setDirty();
    }

    public void writeErodedGrassCooldownEntry(ServerLevel level, BlockPos worldPos, int stage, long currentGameTime) {
        ErosionPersistentState state = stateFor(level);
        ChunkPos chunkPos = new ChunkPos(worldPos);
        ChunkErosionMap map = state.computeChunkMap(chunkPos);
        float threshold = BlockThresholds.randomThreshold(Blocks.GRASS_BLOCK);
        map.putEntry(worldPos.immutable(), new ErosionEntry(Blocks.GRASS_BLOCK, threshold, 0f, currentGameTime, stage));
        state.setDirty();
    }

    public void writeCooldownEntry(ServerLevel level, BlockPos worldPos, Block block, long currentGameTime) {
        ErosionPersistentState state = stateFor(level);
        ChunkPos chunkPos = new ChunkPos(worldPos);
        ChunkErosionMap map = state.computeChunkMap(chunkPos);
        float threshold = BlockThresholds.randomThreshold(block);
        map.putEntry(worldPos.immutable(), new ErosionEntry(block, threshold, 0f, currentGameTime));
        state.setDirty();
    }

    /** One-time migration of legacy overworld grass entries into eroded_grass_block. */
    public void migrateGrassEntries(ServerLevel level) {
        ErosionPersistentState state = stateFor(level);

        List<BlockPos> candidates = new ArrayList<>();
        for (ChunkErosionMap chunk : state.getAllChunkMaps().values()) {
            for (Map.Entry<BlockPos, ErosionEntry> e : chunk.getEntries().entrySet()) {
                ErosionEntry entry = e.getValue();
                if (entry.getTrackedBlock() == Blocks.GRASS_BLOCK && entry.getErosionStage() > 0) {
                    candidates.add(e.getKey());
                }
            }
        }

        if (candidates.isEmpty()) return;

        long currentTime = level.getGameTime();
        int migrated = 0;
        for (BlockPos pos : candidates) {
            ChunkErosionMap chunk = state.getChunkMap(new ChunkPos(pos));
            if (chunk == null) continue;
            ErosionEntry entry = chunk.getEntry(pos);
            if (entry == null) continue;

            if (!level.getBlockState(pos).is(Blocks.GRASS_BLOCK)) {
                removeEntry(level, pos);
                continue;
            }

            int stage = entry.getErosionStage() - 1;
            Direction facing = facingFromPos(pos);
            level.setBlock(pos,
                TRMTBlocks.ERODED_GRASS_BLOCK.get().defaultBlockState()
                    .setValue(ErodedGrassBlock.FACING, facing)
                    .setValue(ErodedGrassBlock.STAGE, stage),
                Block.UPDATE_ALL);
            removeEntry(level, pos);
            writeCooldownEntry(level, pos, TRMTBlocks.ERODED_GRASS_BLOCK.get(), currentTime);
            migrated++;
        }

        if (migrated > 0) {
            TRMT.LOGGER.info("[TRMT] Migrated {} eroded grass entries to eroded_grass_block in {}.", migrated, level.dimension().location());
        }
    }

    private static Direction facingFromPos(BlockPos pos) {
        return switch (BlockThresholds.posRotation(pos)) {
            case 1  -> Direction.WEST;
            case 2  -> Direction.NORTH;
            case 3  -> Direction.EAST;
            default -> Direction.SOUTH;
        };
    }

    public void revertDisabledBlocks(ServerLevel level, ChunkPos chunkPos) {
        TRMTConfig.ErosionToggles t = TRMTConfig.get().erosion;
        if (t.grassEnabled && t.dirtEnabled && t.sandEnabled) return;

        int startX = chunkPos.getMinBlockX();
        int startZ = chunkPos.getMinBlockZ();
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();

        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        for (int x = startX; x < startX + 16; x++) {
            for (int z = startZ; z < startZ + 16; z++) {
                for (int y = minY; y < maxY; y++) {
                    mutable.set(x, y, z);
                    Block block = level.getBlockState(mutable).getBlock();

                    if (!t.grassEnabled && block == TRMTBlocks.ERODED_GRASS_BLOCK.get()) {
                        level.setBlock(mutable.immutable(), Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
                        removeEntry(level, mutable.immutable());
                    } else if (!t.dirtEnabled) {
                        if (block == TRMTBlocks.ERODED_DIRT.get()) {
                            level.setBlock(mutable.immutable(), Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
                            removeEntry(level, mutable.immutable());
                        } else if (block == TRMTBlocks.ERODED_COARSE_DIRT.get()) {
                            level.setBlock(mutable.immutable(), Blocks.COARSE_DIRT.defaultBlockState(), Block.UPDATE_ALL);
                            removeEntry(level, mutable.immutable());
                        }
                    } else if (!t.sandEnabled && block == TRMTBlocks.ERODED_SAND.get()) {
                        level.setBlock(mutable.immutable(), Blocks.SAND.defaultBlockState(), Block.UPDATE_ALL);
                        removeEntry(level, mutable.immutable());
                    }
                }
            }
        }
    }

    public void revertDisabledBlocksAllLoaded(MinecraftServer server) {
        TRMTConfig.ErosionToggles t = TRMTConfig.get().erosion;
        if (t.grassEnabled && t.dirtEnabled && t.sandEnabled) return;

        int viewDistance = server.getPlayerList().getViewDistance();
        for (ServerLevel level : server.getAllLevels()) {
            Set<ChunkPos> scanned = new HashSet<>();
            for (ServerPlayer player : level.getPlayers(p -> true)) {
                ChunkPos playerChunk = player.chunkPosition();
                for (int dx = -viewDistance; dx <= viewDistance; dx++) {
                    for (int dz = -viewDistance; dz <= viewDistance; dz++) {
                        ChunkPos cp = new ChunkPos(playerChunk.x + dx, playerChunk.z + dz);
                        if (scanned.add(cp) && level.getChunk(cp.x, cp.z, ChunkStatus.FULL, false) != null) {
                            revertDisabledBlocks(level, cp);
                        }
                    }
                }
            }
        }
    }

    /**
     * Queues a paginated full sync of the player's current dimension. Chunks
     * within the server's view distance are added to a per-player queue and
     * drained {@code SYNC_DRAIN_PER_TICK} per server tick by
     * {@link #drainSyncQueues()}, so login on a heavily-eroded world doesn't
     * burst-send hundreds of packets in one tick.
     */
    public void sendFullSyncToPlayer(ServerPlayer player) {
        if (server == null) return;
        ServerLevel level = player.serverLevel();
        ErosionPersistentState state = stateFor(level);
        ResourceKey<Level> dim = level.dimension();
        ChunkPos centre = player.chunkPosition();
        int viewDistance = server.getPlayerList().getViewDistance();

        java.util.ArrayDeque<ChunkPos> queue = new java.util.ArrayDeque<>();
        for (Map.Entry<ChunkPos, ChunkErosionMap> chunkEntry : state.getAllChunkMaps().entrySet()) {
            ChunkPos chunkPos = chunkEntry.getKey();
            if (chunkEntry.getValue().getEntries().isEmpty()) continue;
            if (Math.abs(chunkPos.x - centre.x) > viewDistance) continue;
            if (Math.abs(chunkPos.z - centre.z) > viewDistance) continue;
            queue.add(chunkPos);
        }
        pendingSyncQueues.put(player.getUUID(), queue);
        pendingSyncDims.put(player.getUUID(), dim);
    }

    /** Drains a few queued sync chunks per player. Call from {@code ServerTickEvent.Post}. */
    public void drainSyncQueues() {
        if (server == null || pendingSyncQueues.isEmpty()) return;
        for (var it = pendingSyncQueues.entrySet().iterator(); it.hasNext();) {
            var entry = it.next();
            java.util.UUID playerId = entry.getKey();
            java.util.ArrayDeque<ChunkPos> queue = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            ResourceKey<Level> dim = pendingSyncDims.get(playerId);
            if (player == null || dim == null || !player.serverLevel().dimension().equals(dim)) {
                it.remove();
                pendingSyncDims.remove(playerId);
                continue;
            }
            ServerLevel level = player.serverLevel();
            ErosionPersistentState state = stateFor(level);
            int budget = SYNC_DRAIN_PER_TICK;
            while (budget > 0 && !queue.isEmpty()) {
                ChunkPos chunkPos = queue.pollFirst();
                ChunkErosionMap map = state.getChunkMap(chunkPos);
                if (map == null) continue;
                Map<BlockPos, ErosionEntry> entries = map.getEntries();
                if (entries.isEmpty()) continue;
                List<SyncChunkPayload.Entry> payloadEntries = new ArrayList<>(entries.size());
                for (Map.Entry<BlockPos, ErosionEntry> e : entries.entrySet()) {
                    payloadEntries.add(new SyncChunkPayload.Entry(
                        e.getKey(),
                        e.getValue().getErosionStage(),
                        e.getValue().getWalkedOnCount(),
                        e.getValue().getThreshold(),
                        e.getValue().getLastTouchedGameTime()
                    ));
                }
                PacketDistributor.sendToPlayer(player, new SyncChunkPayload(dim, chunkPos.x, chunkPos.z, payloadEntries));
                budget--;
            }
            if (queue.isEmpty()) {
                it.remove();
                pendingSyncDims.remove(playerId);
            }
        }
    }

    public void clearPlayerSync(java.util.UUID playerId) {
        pendingSyncQueues.remove(playerId);
        pendingSyncDims.remove(playerId);
    }

    private void broadcastStageUpdate(ResourceKey<Level> dim, BlockPos pos, int stage, float walkedOnCount, float threshold, long lastTouchedGameTime) {
        if (server == null) return;
        UpdateStagePayload payload = new UpdateStagePayload(dim, pos, stage, walkedOnCount, threshold, lastTouchedGameTime);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }
}
