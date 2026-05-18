package toxicbananaparty.ddp.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import toxicbananaparty.ddp.DeerDiaryPatches;

/**
 * Suppress noisy "Error sending packet clientbound/minecraft:disconnect" log
 * spam from PacketEncoder.
 *
 * When something fires netty's exceptionCaught on a connection still in the
 * HANDSHAKING or STATUS protocol (port scanner, cracked client probe, MC
 * client whose handshake died mid-stream, server list ping that lost the
 * socket, etc.), vanilla Connection.exceptionCaught unconditionally tries
 * to send a ClientboundDisconnectPacket. Neither HANDSHAKING nor STATUS
 * register that packet, so IdDispatchCodec throws EncoderException and
 * PacketEncoder logs an error per occurrence — pure noise, the connection
 * is torn down regardless.
 *
 * Wrap the specific send call inside exceptionCaught: if the active
 * protocol can't encode disconnect packets, skip the send and close the
 * channel directly (mirroring what the listener's thenRun would have done
 * on a successful send). LOGIN / CONFIGURATION / PLAY behavior is
 * unchanged.
 */
@Mixin(Connection.class)
public abstract class ConnectionMixin {

    @WrapOperation(
        method = "exceptionCaught",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/Connection;send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V"
        )
    )
    private void ddp$skipUnencodableDisconnect(
        Connection self,
        Packet<?> packet,
        PacketSendListener listener,
        Operation<Void> original
    ) {
        PacketListener pl = self.getPacketListener();
        if (pl != null) {
            ConnectionProtocol p = pl.protocol();
            if (p != ConnectionProtocol.LOGIN
                && p != ConnectionProtocol.CONFIGURATION
                && p != ConnectionProtocol.PLAY) {
                DeerDiaryPatches.LOGGER.debug(
                    "[DDP] Suppressed unencodable {} for protocol {}; closing channel quietly.",
                    packet.getClass().getSimpleName(), p
                );
                self.disconnect(Component.translatable(
                    "disconnect.genericReason",
                    "Pre-handshake exception"
                ));
                return;
            }
        }
        original.call(self, packet, listener);
    }
}
