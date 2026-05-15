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

        // Start one block above the hit block, then scan up until we find two
        // empty blocks stacked (room for the player). Prevents jumping into a
        // wall when the ray hits a vertical face of a tall structure.
        var level = player.serverLevel();
        var mPos = hit.getBlockPos().above().mutable();
        int maxY = level.getMaxBuildHeight();
        while (mPos.getY() < maxY) {
            boolean feet = isEmpty(level, mPos);
            boolean head = isEmpty(level, mPos.above());
            if (feet && head) break;
            mPos.move(net.minecraft.core.Direction.UP);
        }

        Vec3 dest = Vec3.atBottomCenterOf(mPos);
        player.teleportTo(dest.x, dest.y, dest.z);
        player.connection.resetPosition();
        src.sendSuccess(() -> Component.literal(String.format(
            "Jumped to %.1f, %.1f, %.1f.", dest.x, dest.y, dest.z)), false);
        return 1;
    }

    private static boolean isEmpty(net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }
}
