package milkucha.ddc.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.authlib.GameProfile;
import milkucha.ddc.util.PlayerDataIO;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * `/tp_offline <playerName> <pos> [dimension]` — rewrite an OFFLINE player's
 * stored position so they appear there on their next login. Admin-only.
 * Refuses if the player is currently online (online state would clobber on
 * next logout). Reuses the player's existing dimension if not specified.
 */
public final class TpOfflineCommand {
    private TpOfflineCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("tp_offline")
            .requires(src -> src.hasPermission(2))
            .then(Commands.argument("player", StringArgumentType.word())
                .then(Commands.argument("pos", Vec3Argument.vec3())
                    .executes(ctx -> run(ctx, false))
                    .then(Commands.argument("dimension", DimensionArgument.dimension())
                        .executes(ctx -> run(ctx, true))))));
    }

    private static int run(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx, boolean dimensionGiven)
            throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        MinecraftServer server = src.getServer();
        String targetName = StringArgumentType.getString(ctx, "player");

        if (server.getPlayerList().getPlayerByName(targetName) != null) {
            src.sendFailure(Component.literal(
                targetName + " is online; use /tp instead. /tp_offline is for offline players."));
            return 0;
        }

        Optional<GameProfile> profile = server.getProfileCache() != null
            ? server.getProfileCache().get(targetName)
            : Optional.empty();
        if (profile.isEmpty()) {
            src.sendFailure(Component.literal("Unknown player: " + targetName));
            return 0;
        }

        Optional<PlayerDataIO.Snapshot> existing = PlayerDataIO.read(server, profile.get().getId());
        if (existing.isEmpty()) {
            src.sendFailure(Component.literal(
                targetName + " has no offline data on this server (have they ever joined?)."));
            return 0;
        }

        Vec3 pos = Vec3Argument.getVec3(ctx, "pos");
        ServerLevel dim = dimensionGiven
            ? DimensionArgument.getDimension(ctx, "dimension")
            : server.getLevel(existing.get().dimension());
        if (dim == null) {
            src.sendFailure(Component.literal("Dimension not loaded."));
            return 0;
        }

        PlayerDataIO.Snapshot snap = new PlayerDataIO.Snapshot(
            pos.x, pos.y, pos.z,
            existing.get().yaw(), existing.get().pitch(),
            dim.dimension());
        boolean ok = PlayerDataIO.writePosition(server, profile.get().getId(), snap);
        if (!ok) {
            src.sendFailure(Component.literal("Failed to write offline data; see server log."));
            return 0;
        }
        src.sendSuccess(() -> Component.literal(String.format(
            "Set %s's next-login position to %.1f, %.1f, %.1f in %s.",
            targetName, pos.x, pos.y, pos.z, dim.dimension().location())), true);
        return 1;
    }
}
