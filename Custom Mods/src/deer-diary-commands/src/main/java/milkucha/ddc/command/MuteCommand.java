package milkucha.ddc.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import milkucha.ddc.state.MuteState;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * `/mute <player> [minutes]` — mute a player (0 minutes = permanent).
 * `/unmute <player>` — lift a mute.
 * `/muted` — list current mutes.
 */
public final class MuteCommand {
    private MuteCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("mute")
            .requires(src -> src.hasPermission(2))
            .then(Commands.argument("targets", EntityArgument.players())
                .executes(ctx -> mute(ctx.getSource(), EntityArgument.getPlayers(ctx, "targets"), 0))
                .then(Commands.argument("minutes", IntegerArgumentType.integer(0, 60 * 24 * 365))
                    .executes(ctx -> mute(ctx.getSource(),
                        EntityArgument.getPlayers(ctx, "targets"),
                        IntegerArgumentType.getInteger(ctx, "minutes"))))));

        dispatcher.register(Commands.literal("unmute")
            .requires(src -> src.hasPermission(2))
            .then(Commands.argument("targets", EntityArgument.players())
                .executes(ctx -> unmute(ctx.getSource(), EntityArgument.getPlayers(ctx, "targets")))));

        dispatcher.register(Commands.literal("muted")
            .requires(src -> src.hasPermission(2))
            .executes(ctx -> list(ctx.getSource())));
    }

    private static int mute(CommandSourceStack src, Collection<ServerPlayer> targets, int minutes) {
        long durationMs = (long) minutes * 60_000L;
        MuteState state = MuteState.getInstance();
        for (ServerPlayer p : targets) {
            long expiry = state.set(p.getUUID(), durationMs);
            String name = p.getGameProfile().getName();
            String suffix = expiry == 0 ? "permanently" : "for " + minutes + " minute(s)";
            src.sendSuccess(() -> Component.literal("Muted " + name + " " + suffix + ".")
                .withStyle(ChatFormatting.YELLOW), true);
            p.sendSystemMessage(Component.literal("You have been muted " + suffix + ".")
                .withStyle(ChatFormatting.RED));
        }
        return targets.size();
    }

    private static int unmute(CommandSourceStack src, Collection<ServerPlayer> targets) {
        MuteState state = MuteState.getInstance();
        int changed = 0;
        for (ServerPlayer p : targets) {
            if (state.remove(p.getUUID())) {
                String name = p.getGameProfile().getName();
                src.sendSuccess(() -> Component.literal("Unmuted " + name + ".")
                    .withStyle(ChatFormatting.GREEN), true);
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
            String when;
            if (e.getValue() == 0L) {
                when = "permanent";
            } else {
                long minutes = Math.max(0, (e.getValue() - now) / 60_000L);
                when = minutes + " minute(s) remaining";
            }
            // Look up name from the player list (online) or fall back to UUID.
            String name = src.getServer().getPlayerList().getPlayer(e.getKey()) != null
                ? src.getServer().getPlayerList().getPlayer(e.getKey()).getGameProfile().getName()
                : e.getKey().toString();
            String label = "  " + name + " — " + when;
            src.sendSuccess(() -> Component.literal(label).withStyle(ChatFormatting.GRAY), false);
        }
        return snapshot.size();
    }
}
