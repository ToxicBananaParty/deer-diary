package milkucha.trmt.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

public record UpdateStagePayload(ResourceKey<Level> dimension, BlockPos pos, int stage, float walkedOnCount, float threshold, long lastTouchedGameTime) implements CustomPacketPayload {

    public static final Type<UpdateStagePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("trmt", "update_stage"));

    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateStagePayload> STREAM_CODEC = StreamCodec.of(
        (buf, payload) -> {
            buf.writeResourceKey(payload.dimension());
            BlockPos.STREAM_CODEC.encode(buf, payload.pos());
            buf.writeInt(payload.stage());
            buf.writeFloat(payload.walkedOnCount());
            buf.writeFloat(payload.threshold());
            buf.writeLong(payload.lastTouchedGameTime());
        },
        buf -> new UpdateStagePayload(
            buf.readResourceKey(Registries.DIMENSION),
            BlockPos.STREAM_CODEC.decode(buf),
            buf.readInt(),
            buf.readFloat(),
            buf.readFloat(),
            buf.readLong()
        )
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
