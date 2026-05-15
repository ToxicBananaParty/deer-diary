package milkucha.ddc.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import milkucha.ddc.util.PlayerDataIO;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.UUID;

/**
 * {@code /tp_offline name <player> <pos>} or {@code /tp_offline id <uuid> <pos>} —
 * rewrite an OFFLINE player's stored position so they appear there on next
 * login. Destination dimension is the caller's current dimension (matching
 * FTB Essentials' behavior). Alias: {@code /tpo}.
 */
public final class TpOfflineCommand {
    private TpOfflineCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralCommandNode<CommandSourceStack> root = dispatcher.register(Commands.literal("tp_offline")
            .requires(src -> src.hasPermission(2))
            .then(Commands.literal("name")
                .then(Commands.argument("player", StringArgumentType.word())
                    .then(Commands.argument("pos", Vec3Argument.vec3())
                        .executes(ctx -> byName(
                            ctx.getSource(),
                            StringArgumentType.getString(ctx, "player"),
                            Vec3Argument.getVec3(ctx, "pos"))))))
            .then(Commands.literal("id")
                .then(Commands.argument("player_id", UuidArgument.uuid())
                    .then(Commands.argument("pos", Vec3Argument.vec3())
                        .executes(ctx -> byUuid(
                            ctx.getSource(),
                            UuidArgument.getUuid(ctx, "player_id"),
                            Vec3Argument.getVec3(ctx, "pos")))))));

        // Short alias.
        dispatcher.register(Commands.literal("tpo")
            .requires(src -> src.hasPermission(2))
            .redirect(root));
    }

    private static int byName(CommandSourceStack src, String name, Vec3 pos) throws CommandSyntaxException {
        MinecraftServer server = src.getServer();
        Optional<GameProfile> profile = server.getProfileCache() != null
            ? server.getProfileCache().get(name)
            : Optional.empty();
        if (profile.isEmpty()) {
            src.sendFailure(Component.literal("Unknown player: " + name));
            return 0;
        }
        return apply(src, profile.get().getId(), name, pos);
    }

    private static int byUuid(CommandSourceStack src, UUID id, Vec3 pos) {
        return apply(src, id, id.toString(), pos);
    }

    private static int apply(CommandSourceStack src, UUID id, String label, Vec3 pos) {
        MinecraftServer server = src.getServer();
        if (server.getPlayerList().getPlayer(id) != null) {
            src.sendFailure(Component.literal(
                label + " is online; /tp_offline is for offline players only."));
            return 0;
        }

        Optional<PlayerDataIO.Snapshot> existing = PlayerDataIO.read(server, id);
        if (existing.isEmpty()) {
            src.sendFailure(Component.literal(
                label + " has no offline data on this server (have they ever joined?)."));
            return 0;
        }

        ServerLevel destDim = src.getLevel();
        PlayerDataIO.Snapshot newSnap = new PlayerDataIO.Snapshot(
            pos.x, pos.y, pos.z,
            existing.get().yaw(), existing.get().pitch(),
            destDim.dimension());
        if (!PlayerDataIO.writePosition(server, id, newSnap)) {
            src.sendFailure(Component.literal("Failed to write offline data; see server log."));
            return 0;
        }
        src.sendSuccess(() -> Component.literal(String.format(
            "Set %s's next-login position to %.1f, %.1f, %.1f in %s.",
            label, pos.x, pos.y, pos.z, destDim.dimension().location())), true);
        return 1;
    }
}
