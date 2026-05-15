package milkucha.ddc.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Comparator;
import java.util.List;

public final class NearCommand {
    private NearCommand() {}

    private static final int DEFAULT_RADIUS = 200;
    private static final int MAX_RADIUS = 30_000;

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("near")
            .requires(src -> src.hasPermission(2))
            .executes(ctx -> run(ctx.getSource(), ctx.getSource().getPlayerOrException(), DEFAULT_RADIUS))
            .then(Commands.argument("radius", IntegerArgumentType.integer(1, MAX_RADIUS))
                .executes(ctx -> run(ctx.getSource(),
                    ctx.getSource().getPlayerOrException(),
                    IntegerArgumentType.getInteger(ctx, "radius"))))
            .then(Commands.argument("player", EntityArgument.player())
                .executes(ctx -> run(ctx.getSource(),
                    EntityArgument.getPlayer(ctx, "player"), DEFAULT_RADIUS))
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, MAX_RADIUS))
                    .executes(ctx -> run(ctx.getSource(),
                        EntityArgument.getPlayer(ctx, "player"),
                        IntegerArgumentType.getInteger(ctx, "radius"))))));
    }

    private static int run(CommandSourceStack src, ServerPlayer pivot, int radius) {
        double r2 = (double) radius * radius;

        List<ServerPlayer> nearby = pivot.serverLevel().players().stream()
            .filter(p -> p != pivot)
            .filter(p -> p.distanceToSqr(pivot) <= r2)
            .sorted(Comparator.comparingDouble(p -> p.distanceToSqr(pivot)))
            .toList();

        String pivotName = pivot.getGameProfile().getName();
        if (nearby.isEmpty()) {
            src.sendSuccess(() -> Component.literal(
                "No other players within " + radius + " blocks of " + pivotName + ".")
                .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }

        src.sendSuccess(() -> Component.literal(
            nearby.size() + " player(s) within " + radius + " blocks of " + pivotName + ":")
            .withStyle(ChatFormatting.YELLOW), false);
        for (ServerPlayer p : nearby) {
            String name = p.getGameProfile().getName();
            double dist = Math.sqrt(p.distanceToSqr(pivot));
            src.sendSuccess(() -> Component.literal(
                    String.format("  %s — %.2fm", name, dist))
                .withStyle(ChatFormatting.GRAY), false);
        }
        return nearby.size();
    }
}
