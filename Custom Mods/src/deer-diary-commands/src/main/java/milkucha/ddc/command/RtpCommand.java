package milkucha.ddc.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import milkucha.ddc.config.DDCConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * `/rtp` — random teleport within a per-dimension whitelist, observing
 * min/max distance and a per-player cooldown. Configuration is loaded from
 * {@link DDCConfig} (config/deer_diary_commands.json).
 */
public final class RtpCommand {
    private RtpCommand() {}

    private static final Map<UUID, Long> LAST_USE = new HashMap<>();
    private static final Random RNG = new Random();

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("rtp")
            .executes(ctx -> run(ctx.getSource())));
    }

    private static int run(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        DDCConfig.Rtp cfg = DDCConfig.get().rtp;

        long now = System.currentTimeMillis();
        Long last = LAST_USE.get(player.getUUID());
        if (last != null && !player.hasPermissions(2)) {
            long elapsed = (now - last) / 1000L;
            if (elapsed < cfg.cooldown_seconds) {
                long remaining = cfg.cooldown_seconds - elapsed;
                src.sendFailure(Component.literal(
                    "RTP cooldown: " + remaining + "s remaining."));
                return 0;
            }
        }

        ServerLevel level = player.serverLevel();
        String dimId = level.dimension().location().toString();

        if (!cfg.dimension_whitelist.isEmpty() && !matchesAny(cfg.dimension_whitelist, dimId)) {
            src.sendFailure(Component.literal("RTP is not allowed in " + dimId + "."));
            return 0;
        }
        if (matchesAny(cfg.dimension_blacklist, dimId)) {
            src.sendFailure(Component.literal("RTP is blacklisted in " + dimId + "."));
            return 0;
        }

        BlockPos origin = level.getSharedSpawnPos();
        int minSq = cfg.min_distance * cfg.min_distance;
        int maxSq = cfg.max_distance * cfg.max_distance;

        for (int attempt = 0; attempt < cfg.max_tries; attempt++) {
            int dx = nextSigned(cfg.max_distance);
            int dz = nextSigned(cfg.max_distance);
            int distSq = dx * dx + dz * dz;
            if (distSq < minSq || distSq > maxSq) continue;

            BlockPos surface = findSafeSurface(level, origin.getX() + dx, origin.getZ() + dz);
            if (surface == null) continue;

            player.teleportTo(level, surface.getX() + 0.5, surface.getY(), surface.getZ() + 0.5,
                Set.of(), player.getYRot(), player.getXRot());
            LAST_USE.put(player.getUUID(), now);
            src.sendSuccess(() -> Component.literal(String.format(
                "Teleported to %d, %d, %d (%d blocks from spawn).",
                surface.getX(), surface.getY(), surface.getZ(),
                (int) Math.round(Math.sqrt(distSq))))
                .withStyle(ChatFormatting.AQUA), false);
            return 1;
        }

        src.sendFailure(Component.literal(
            "Could not find a safe destination after " + cfg.max_tries + " tries. Try again."));
        return 0;
    }

    private static int nextSigned(int bound) {
        int n = RNG.nextInt(bound + 1);
        return RNG.nextBoolean() ? n : -n;
    }

    private static boolean matchesAny(java.util.List<String> patterns, String dimId) {
        for (String pattern : patterns) {
            if (pattern.endsWith(":*")) {
                String ns = pattern.substring(0, pattern.length() - 1);
                if (dimId.startsWith(ns)) return true;
            } else if (pattern.equals(dimId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Walk down from world top at (x, z) to find an open, non-fluid spot to
     * stand on. Returns the BlockPos to stand at (one above the solid block),
     * or null if nothing suitable was found.
     */
    private static BlockPos findSafeSurface(ServerLevel level, int x, int z) {
        // Pre-load the chunk so heightmap queries are valid.
        level.getChunk(new ChunkPos(new BlockPos(x, 0, z)).x,
                       new ChunkPos(new BlockPos(x, 0, z)).z);
        int topY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            x, z);
        if (topY <= level.getMinBuildHeight() + 1) return null;
        BlockPos floor = new BlockPos(x, topY - 1, z);
        BlockState floorState = level.getBlockState(floor);
        if (floorState.isAir() || !floorState.getFluidState().isEmpty()) return null;
        if (floorState.is(BlockTags.LEAVES) || floorState.is(BlockTags.LOGS)) return null;
        BlockState feet = level.getBlockState(floor.above());
        BlockState head = level.getBlockState(floor.above(2));
        if (!feet.isAir() && !feet.canBeReplaced()) return null;
        if (!head.isAir() && !head.canBeReplaced()) return null;
        return floor.above();
    }
}
