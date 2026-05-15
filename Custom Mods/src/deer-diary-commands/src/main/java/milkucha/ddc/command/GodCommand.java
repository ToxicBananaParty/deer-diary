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

/**
 * Toggles invulnerability. Vanilla persists {@code Invulnerable} in player NBT,
 * so no separate save layer is needed.
 */
public final class GodCommand {
    private GodCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("god")
            .requires(src -> src.hasPermission(2))
            .executes(ctx -> run(ctx.getSource(), Collections.singletonList(ctx.getSource().getPlayerOrException())))
            .then(Commands.argument("targets", EntityArgument.players())
                .executes(ctx -> run(ctx.getSource(), EntityArgument.getPlayers(ctx, "targets")))));
    }

    private static int run(CommandSourceStack src, Collection<ServerPlayer> targets) {
        int count = 0;
        for (ServerPlayer p : targets) {
            boolean nowInvuln = !p.isInvulnerable();
            p.setInvulnerable(nowInvuln);
            String name = p == src.getPlayer() ? "yourself" : p.getGameProfile().getName();
            String state = nowInvuln ? "invulnerable" : "vulnerable";
            src.sendSuccess(() -> Component.literal(
                (p == src.getPlayer() ? "You are " : name + " is ") + "now " + state + "."), true);
            count++;
        }
        return count;
    }
}
