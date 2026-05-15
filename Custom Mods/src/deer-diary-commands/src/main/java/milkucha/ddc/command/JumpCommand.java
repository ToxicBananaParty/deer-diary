package milkucha.ddc.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class JumpCommand {
    private JumpCommand() {}

    private static final double MAX_REACH = 256.0;

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("jump")
            .requires(src -> src.hasPermission(2))
            .executes(ctx -> run(ctx.getSource())));
    }

    private static int run(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        Vec3 eye = player.getEyePosition(1.0F);
        Vec3 look = player.getViewVector(1.0F);
        Vec3 end = eye.add(look.scale(MAX_REACH));

        BlockHitResult hit = player.serverLevel().clip(new ClipContext(
            eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));

        if (hit.getType() == HitResult.Type.MISS) {
            src.sendFailure(Component.literal("No block in sight (within " + (int) MAX_REACH + " blocks)."));
            return 0;
        }

        // Land on top of the hit block, biased one block above the face the ray hit.
        Vec3 dest = hit.getLocation();
        if (hit.getDirection().getStepY() == 0) {
            // Side-face hit: stand on top of the block we ran into.
            dest = Vec3.atBottomCenterOf(hit.getBlockPos().above());
        } else if (hit.getDirection().getStepY() < 0) {
            // Ceiling hit: stand on top of the block below the ray endpoint.
            dest = Vec3.atBottomCenterOf(hit.getBlockPos());
        }

        player.teleportTo(dest.x, dest.y, dest.z);
        player.connection.resetPosition();
        final Vec3 finalDest = dest;
        src.sendSuccess(() -> Component.literal(String.format(
            "Jumped to %.1f, %.1f, %.1f.", finalDest.x, finalDest.y, finalDest.z)), false);
        return 1;
    }
}
