package milkucha.ddc.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class KickmeCommand {
    private KickmeCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("kickme")
            .executes(ctx -> run(ctx.getSource())));
    }

    private static int run(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        player.connection.disconnect(Component.literal(
            "You used /kickme to disconnect yourself. Rejoin whenever you're ready."));
        return 1;
    }
}
