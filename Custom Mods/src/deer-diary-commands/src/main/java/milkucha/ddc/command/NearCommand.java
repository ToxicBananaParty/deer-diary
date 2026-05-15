package milkucha.ddc.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.Comparator;
import java.util.List;

public final class NearCommand {
    private NearCommand() {}

    private static final int DEFAULT_RADIUS = 100;
    private static final int MAX_RADIUS = 5000;

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("near")
            .executes(ctx -> run(ctx.getSource(), DEFAULT_RADIUS))
            .then(Commands.argument("radius", IntegerArgumentType.integer(1, MAX_RADIUS))
                .executes(ctx -> run(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius")))));
    }

    private static int run(CommandSourceStack src, int radius) throws CommandSyntaxException {
        ServerPlayer caller = src.getPlayerOrException();
        double r2 = (double) radius * radius;

        List<ServerPlayer> nearby = caller.serverLevel().players().stream()
            .filter(p -> p != caller)
            .filter(p -> p.distanceToSqr(caller) <= r2)
            .sorted(Comparator.comparingDouble(p -> p.distanceToSqr(caller)))
            .toList();

        if (nearby.isEmpty()) {
            src.sendSuccess(() -> Component.literal("No other players within " + radius + " blocks.")
                .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }

        MutableComponent header = Component.literal("Players within " + radius + " blocks:")
            .withStyle(ChatFormatting.YELLOW);
        src.sendSuccess(() -> header, false);
        for (ServerPlayer p : nearby) {
            int dist = (int) Math.round(Math.sqrt(p.distanceToSqr(caller)));
            String name = p.getGameProfile().getName();
            src.sendSuccess(() -> Component.literal("  " + name + " — " + dist + " blocks")
                .withStyle(ChatFormatting.GRAY), false);
        }
        return nearby.size();
    }
}
