package milkucha.ddc.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.authlib.GameProfile;
import milkucha.ddc.util.PlayerDataIO;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;
import java.util.Set;

/**
 * `/tpl <playerName>` — admin command. Teleport caller to the named player's
 * location, whether they are online (current position) or offline (last
 * logout position stored in playerdata NBT).
 */
public final class TplCommand {
    private TplCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var node = dispatcher.register(Commands.literal("teleport_last")
            .requires(src -> src.hasPermission(2))
            .then(Commands.argument("player", StringArgumentType.word())
                .suggests((ctx, builder) -> {
                    for (String name : ctx.getSource().getOnlinePlayerNames()) builder.suggest(name);
                    return builder.buildFuture();
                })
                .executes(ctx -> run(ctx.getSource(), StringArgumentType.getString(ctx, "player")))));
        // Short alias.
        dispatcher.register(Commands.literal("tpl")
            .requires(src -> src.hasPermission(2))
            .redirect(node));
    }

    private static int run(CommandSourceStack src, String targetName) throws CommandSyntaxException {
        MinecraftServer server = src.getServer();
        ServerPlayer caller = src.getPlayerOrException();

        ServerPlayer online = server.getPlayerList().getPlayerByName(targetName);
        if (online != null) {
            caller.teleportTo(online.serverLevel(),
                online.getX(), online.getY(), online.getZ(),
                Set.of(), online.getYRot(), online.getXRot());
            src.sendSuccess(() -> Component.literal("Teleported to " + targetName + " (online)."), true);
            return 1;
        }

        Optional<GameProfile> profile = server.getProfileCache() != null
            ? server.getProfileCache().get(targetName)
            : Optional.empty();
        if (profile.isEmpty()) {
            src.sendFailure(Component.literal("Unknown player: " + targetName));
            return 0;
        }

        Optional<PlayerDataIO.Snapshot> snap = PlayerDataIO.read(server, profile.get().getId());
        if (snap.isEmpty()) {
            src.sendFailure(Component.literal(targetName + " has no offline data on this server."));
            return 0;
        }
        PlayerDataIO.Snapshot s = snap.get();
        ServerLevel level = server.getLevel(s.dimension());
        if (level == null) {
            src.sendFailure(Component.literal(
                "Dimension " + s.dimension().location() + " is not loaded."));
            return 0;
        }
        caller.teleportTo(level, s.x(), s.y(), s.z(), Set.of(), s.yaw(), s.pitch());
        src.sendSuccess(() -> Component.literal(
            "Teleported to " + targetName + "'s last logout position in " + s.dimension().location() + "."), true);
        return 1;
    }
}
