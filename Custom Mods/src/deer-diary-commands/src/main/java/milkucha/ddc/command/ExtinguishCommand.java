package milkucha.ddc.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.Collections;

public final class ExtinguishCommand {
    private ExtinguishCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("extinguish")
            .requires(src -> src.hasPermission(2))
            .executes(ctx -> run(ctx.getSource(), Collections.singletonList(ctx.getSource().getPlayerOrException())))
            .then(Commands.argument("targets", EntityArgument.players())
                .executes(ctx -> run(ctx.getSource(), EntityArgument.getPlayers(ctx, "targets")))));
    }

    private static int run(CommandSourceStack src, Collection<ServerPlayer> targets) throws CommandSyntaxException {
        int count = 0;
        for (ServerPlayer p : targets) {
            p.clearFire();
            count++;
        }
        if (count == 1) {
            ServerPlayer only = targets.iterator().next();
            if (src.getPlayer() == only) {
                src.sendSuccess(() -> Component.literal("Extinguished yourself."), true);
            } else {
                src.sendSuccess(() -> Component.literal("Extinguished " + only.getGameProfile().getName() + "."), true);
            }
        } else {
            final int n = count;
            src.sendSuccess(() -> Component.literal("Extinguished " + n + " players."), true);
        }
        return count;
    }
}
