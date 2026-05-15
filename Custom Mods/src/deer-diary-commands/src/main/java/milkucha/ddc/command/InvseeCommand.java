package milkucha.ddc.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import milkucha.ddc.command.invsee.InvseeMenuProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /invsee <player>} — open a 5-row chest GUI proxying the target's
 * inventory. Edits propagate live; the menu auto-closes if the target logs
 * out (see {@link milkucha.ddc.command.invsee.PlayerInventoryView}).
 */
public final class InvseeCommand {
    private InvseeCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("invsee")
            .requires(src -> src.hasPermission(2))
            .then(Commands.argument("target", EntityArgument.player())
                .executes(ctx -> run(ctx.getSource(), EntityArgument.getPlayer(ctx, "target")))));
    }

    private static int run(CommandSourceStack src, ServerPlayer target) throws CommandSyntaxException {
        ServerPlayer viewer = src.getPlayerOrException();
        if (target == viewer) {
            src.sendFailure(Component.literal("That's your own inventory — press E."));
            return 0;
        }
        viewer.openMenu(new InvseeMenuProvider(target, viewer));
        return 1;
    }
}
