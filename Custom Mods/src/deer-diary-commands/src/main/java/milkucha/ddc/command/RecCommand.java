package milkucha.ddc.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * `/recording` and `/streaming` — toggle a player's broadcast indicator that
 * they are currently recording or streaming. Admins can toggle other players
 * by passing a player argument. Per-session in-memory state; intentionally
 * not persisted across restarts (matches FTB's "transient status" intent).
 */
public final class RecCommand {
    private RecCommand() {}

    public enum Mode { RECORDING, STREAMING }

    private static final Map<UUID, Mode> STATUS = new HashMap<>();

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("recording")
            .executes(ctx -> toggle(ctx.getSource(), ctx.getSource().getPlayerOrException(), Mode.RECORDING))
            .then(Commands.argument("player", EntityArgument.player())
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> toggle(ctx.getSource(),
                    EntityArgument.getPlayer(ctx, "player"), Mode.RECORDING))));

        dispatcher.register(Commands.literal("streaming")
            .executes(ctx -> toggle(ctx.getSource(), ctx.getSource().getPlayerOrException(), Mode.STREAMING))
            .then(Commands.argument("player", EntityArgument.player())
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> toggle(ctx.getSource(),
                    EntityArgument.getPlayer(ctx, "player"), Mode.STREAMING))));
    }

    private static int toggle(CommandSourceStack src, ServerPlayer player, Mode mode) throws CommandSyntaxException {
        UUID id = player.getUUID();
        Mode current = STATUS.get(id);
        boolean nowOn = current != mode;

        if (nowOn) {
            STATUS.put(id, mode);
        } else {
            STATUS.remove(id);
        }

        String name = player.getGameProfile().getName();
        String verb = switch (mode) {
            case RECORDING -> nowOn ? "is now recording." : "stopped recording.";
            case STREAMING -> nowOn ? "is now streaming." : "stopped streaming.";
        };
        Component announce = Component.literal(name + " " + verb)
            .withStyle(nowOn ? ChatFormatting.AQUA : ChatFormatting.GRAY);
        src.getServer().getPlayerList().broadcastSystemMessage(announce, false);
        return 1;
    }

    /** Useful for external display-name decorators. */
    public static Mode statusFor(UUID id) {
        return STATUS.get(id);
    }
}
