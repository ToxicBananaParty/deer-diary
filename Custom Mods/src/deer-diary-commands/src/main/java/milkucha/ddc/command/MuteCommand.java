package milkucha.ddc.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import milkucha.ddc.state.MuteState;
import milkucha.ddc.util.DurationParser;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * {@code /mute <player> [duration]} — mute a player. Duration format follows
 * FTB Essentials: {@code 30s}, {@code 15m}, {@code 2h}, {@code 1d}, {@code 1w},
 * or {@code permanent} (also empty/"*"). Default: permanent.
 *
 * {@code /unmute <player>}, {@code /muted}.
 */
public final class MuteCommand {
    private MuteCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("mute")
            .requires(src -> src.hasPermission(2))
            .then(Commands.argument("targets", EntityArgument.players())
                .executes(ctx -> mute(ctx.getSource(), EntityArgument.getPlayers(ctx, "targets"), 0L, "permanent"))
                .then(Commands.argument("duration", StringArgumentType.greedyString())
                    .suggests((c, b) -> DurationParser.suggestDurations(b))
                    .executes(ctx -> {
                        String s = StringArgumentType.getString(ctx, "duration");
                        long ms = DurationParser.parseToMillis(s);
                        return mute(ctx.getSource(),
                            EntityArgument.getPlayers(ctx, "targets"), ms,
                            DurationParser.prettyPrint(ms));
                    }))));

        dispatcher.register(Commands.literal("unmute")
            .requires(src -> src.hasPermission(2))
            .then(Commands.argument("targets", EntityArgument.players())
                .executes(ctx -> unmute(ctx.getSource(), EntityArgument.getPlayers(ctx, "targets")))));

        dispatcher.register(Commands.literal("muted")
            .requires(src -> src.hasPermission(2))
            .executes(ctx -> list(ctx.getSource())));
    }

    private static int mute(CommandSourceStack src, Collection<ServerPlayer> targets,
                            long durationMs, String prettyDuration) {
        MuteState state = MuteState.getInstance();
        String sourceName = src.isPlayer() ? src.getPlayer().getGameProfile().getName() : "Console";
        for (ServerPlayer p : targets) {
            long expiry = state.set(p.getUUID(), durationMs);
            String name = p.getGameProfile().getName();
            String suffix = expiry == 0 ? "permanently" : "for " + prettyDuration;
            Component msg = Component.literal(
                    name + " was muted " + suffix + " by " + sourceName + ".")
                .withStyle(ChatFormatting.YELLOW);
            broadcastToOpsAndTarget(src, p, msg);
            p.sendSystemMessage(Component.literal("You have been muted " + suffix + ".")
                .withStyle(ChatFormatting.RED));
        }
        return targets.size();
    }

    private static int unmute(CommandSourceStack src, Collection<ServerPlayer> targets) {
        MuteState state = MuteState.getInstance();
        String sourceName = src.isPlayer() ? src.getPlayer().getGameProfile().getName() : "Console";
        int changed = 0;
        for (ServerPlayer p : targets) {
            if (state.remove(p.getUUID())) {
                String name = p.getGameProfile().getName();
                Component msg = Component.literal(name + " was unmuted by " + sourceName + ".")
                    .withStyle(ChatFormatting.GREEN);
                broadcastToOpsAndTarget(src, p, msg);
                p.sendSystemMessage(Component.literal("You are no longer muted.")
                    .withStyle(ChatFormatting.GREEN));
                changed++;
            } else {
                String name = p.getGameProfile().getName();
                src.sendFailure(Component.literal(name + " was not muted."));
            }
        }
        return changed;
    }

    private static int list(CommandSourceStack src) {
        Map<UUID, Long> snapshot = MuteState.getInstance().snapshot();
        if (snapshot.isEmpty()) {
            src.sendSuccess(() -> Component.literal("No muted players.")
                .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        src.sendSuccess(() -> Component.literal("Muted players (" + snapshot.size() + "):")
            .withStyle(ChatFormatting.YELLOW), false);
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Long> e : snapshot.entrySet()) {
            String when = e.getValue() == 0L
                ? "permanent"
                : DurationParser.prettyPrint(Math.max(0, e.getValue() - now)) + " remaining";
            ServerPlayer online = src.getServer().getPlayerList().getPlayer(e.getKey());
            String name = online != null ? online.getGameProfile().getName() : e.getKey().toString();
            String label = "  " + name + " — " + when;
            src.sendSuccess(() -> Component.literal(label).withStyle(ChatFormatting.GRAY), false);
        }
        return snapshot.size();
    }

    private static void broadcastToOpsAndTarget(CommandSourceStack src, ServerPlayer target, Component msg) {
        src.getServer().getPlayerList().getPlayers().forEach(p -> {
            if (p == target || p.hasPermissions(2)) {
                p.sendSystemMessage(msg);
            }
        });
        if (!src.isPlayer()) {
            src.sendSuccess(() -> msg, true);
        }
    }
}
