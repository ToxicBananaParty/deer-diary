package milkucha.ddc.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * /rec start | stop — announce that the caller has started or stopped
 * recording/streaming. Per-session in-memory state; intentionally not persisted
 * across server restarts.
 */
public final class RecCommand {
    private RecCommand() {}

    private static final Set<UUID> RECORDING = new HashSet<>();

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("rec")
            .executes(ctx -> toggle(ctx.getSource()))
            .then(Commands.literal("start").executes(ctx -> set(ctx.getSource(), true)))
            .then(Commands.literal("stop").executes(ctx -> set(ctx.getSource(), false))));
    }

    private static int toggle(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        return set(src, !RECORDING.contains(player.getUUID()));
    }

    private static int set(CommandSourceStack src, boolean recording) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        UUID id = player.getUUID();
        boolean wasRecording = RECORDING.contains(id);
        if (recording == wasRecording) {
            src.sendSuccess(() -> Component.literal(
                "You are already " + (recording ? "recording" : "not recording") + ".")
                .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }

        if (recording) {
            RECORDING.add(id);
        } else {
            RECORDING.remove(id);
        }
        String name = player.getGameProfile().getName();
        Component announce = Component.literal(
                name + " is " + (recording ? "now recording / streaming." : "no longer recording / streaming."))
            .withStyle(recording ? ChatFormatting.AQUA : ChatFormatting.GRAY);
        src.getServer().getPlayerList().broadcastSystemMessage(announce, false);
        return 1;
    }
}
