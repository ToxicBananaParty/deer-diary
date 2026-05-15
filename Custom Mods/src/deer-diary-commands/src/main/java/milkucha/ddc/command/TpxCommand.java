package milkucha.ddc.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;

/**
 * `/tpx <dimension>` — teleport the caller to the given dimension, preserving
 * X/Y/Z and rotation. Admin-only.
 */
public final class TpxCommand {
    private TpxCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("tpx")
            .requires(src -> src.hasPermission(2))
            .then(Commands.argument("dimension", DimensionArgument.dimension())
                .executes(ctx -> run(ctx.getSource(),
                    DimensionArgument.getDimension(ctx, "dimension")))));
    }

    private static int run(CommandSourceStack src, ServerLevel target) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        float yaw = player.getYRot();
        float pitch = player.getXRot();
        player.teleportTo(target, x, y, z, Set.of(), yaw, pitch);
        src.sendSuccess(() -> Component.literal(
            "Teleported to " + target.dimension().location() + " at the same coordinates."), true);
        return 1;
    }
}
