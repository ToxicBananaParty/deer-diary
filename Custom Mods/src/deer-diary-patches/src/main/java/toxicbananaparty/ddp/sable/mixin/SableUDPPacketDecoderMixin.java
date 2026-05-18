package toxicbananaparty.ddp.sable.mixin;

import dev.ryanhcode.sable.network.udp.SableUDPPacketDecoder;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import toxicbananaparty.ddp.DeerDiaryPatches;

import java.util.List;

/**
 * Suppress noisy "Server UDP channel caught exception ... Received an invalid
 * packet ID: N" log spam from Sable's UDP listener.
 *
 * Sable's SableUDPPacketDecoder throws IOException when a UDP datagram's first
 * byte is outside the known packet-type range. That happens constantly in the
 * wild: port scanners, legacy MC 1.6 ping probes (which start with 0xFE,
 * coincidentally matching the observed packet ID), stray DNS / amplification
 * crap, or simply Sable clients running a different protocol version. The
 * datagram is discarded either way; only the netty ERROR log is noise.
 *
 * Cancel the decode method just before the throw, log at DEBUG instead.
 * Other Sable validation paths (oversized packet, codec failures) already
 * log + return gracefully and don't go through this hook, so they're
 * unaffected.
 *
 * This is a stopgap; the proper fix is upstream in Sable itself (catch and
 * log-at-debug rather than throwing across the netty boundary).
 */
@Mixin(SableUDPPacketDecoder.class)
public abstract class SableUDPPacketDecoderMixin {

    @Inject(
        method = "decode",
        at = @At(
            value = "INVOKE",
            target = "Ljava/io/IOException;<init>(Ljava/lang/String;)V"
        ),
        cancellable = true
    )
    private void ddp$swallowInvalidPacketId(
        ChannelHandlerContext ctx,
        DatagramPacket msg,
        List<Object> out,
        CallbackInfo ci
    ) {
        DeerDiaryPatches.LOGGER.debug(
            "[DDP] Suppressed Sable UDP invalid packet ID from {} (likely port-scan / unrelated UDP traffic).",
            msg.sender()
        );
        ci.cancel();
    }
}
