package milkucha.ddc.util;

import milkucha.ddc.DeerDiaryCommands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/**
 * Read/write offline player NBT files at {@code <world>/playerdata/<UUID>.dat}.
 * Used by /tpl (read-only, when the target is offline) and /tp_offline (write
 * the next-login position).
 *
 * Only call these on offline players. Mutating the .dat of an online player
 * will be clobbered on their next logout, when vanilla overwrites the file.
 */
public final class PlayerDataIO {
    private PlayerDataIO() {}

    public record Snapshot(double x, double y, double z, float yaw, float pitch,
                           ResourceKey<Level> dimension) {}

    private static Path datPath(MinecraftServer server, UUID id) {
        return server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(id + ".dat");
    }

    public static Optional<Snapshot> read(MinecraftServer server, UUID id) {
        Path path = datPath(server, id);
        if (!Files.isRegularFile(path)) return Optional.empty();
        try {
            CompoundTag tag = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
            ListTag pos = tag.getList("Pos", Tag.TAG_DOUBLE);
            ListTag rot = tag.getList("Rotation", Tag.TAG_FLOAT);
            String dimStr = tag.getString("Dimension");
            if (pos.size() < 3 || rot.size() < 2 || dimStr.isEmpty()) return Optional.empty();
            ResourceLocation dimLoc = ResourceLocation.parse(dimStr);
            ResourceKey<Level> dimKey = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dimLoc);
            return Optional.of(new Snapshot(
                pos.getDouble(0), pos.getDouble(1), pos.getDouble(2),
                rot.getFloat(0), rot.getFloat(1),
                dimKey));
        } catch (IOException e) {
            DeerDiaryCommands.LOGGER.error("[DDC] Failed to read offline player data {}", path, e);
            return Optional.empty();
        }
    }

    public static boolean writePosition(MinecraftServer server, UUID id, Snapshot snap) {
        Path path = datPath(server, id);
        if (!Files.isRegularFile(path)) {
            DeerDiaryCommands.LOGGER.warn("[DDC] No offline data file for {}", id);
            return false;
        }
        try {
            CompoundTag tag = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());

            ListTag pos = new ListTag();
            pos.add(DoubleTag.valueOf(snap.x()));
            pos.add(DoubleTag.valueOf(snap.y()));
            pos.add(DoubleTag.valueOf(snap.z()));
            tag.put("Pos", pos);

            ListTag rot = new ListTag();
            rot.add(FloatTag.valueOf(snap.yaw()));
            rot.add(FloatTag.valueOf(snap.pitch()));
            tag.put("Rotation", rot);

            tag.putString("Dimension", snap.dimension().location().toString());

            NbtIo.writeCompressed(tag, path);
            return true;
        } catch (IOException e) {
            DeerDiaryCommands.LOGGER.error("[DDC] Failed to write offline player data {}", path, e);
            return false;
        }
    }
}
