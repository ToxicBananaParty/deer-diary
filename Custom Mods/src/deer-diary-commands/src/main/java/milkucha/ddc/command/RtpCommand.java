package milkucha.ddc.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import milkucha.ddc.DeerDiaryCommands;
import milkucha.ddc.config.DDCConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * {@code /rtp} — random teleport within a per-dimension whitelist, with
 * cooldown, min/max distance, world-border respect, biome tag blacklist, and
 * spiral-search fallback for dimensions with broken heightmaps (Nether-style).
 */
public final class RtpCommand {
    private RtpCommand() {}

    public static final TagKey<Biome> IGNORE_RTP_BIOMES =
        TagKey.create(Registries.BIOME,
            ResourceLocation.fromNamespaceAndPath(DeerDiaryCommands.MOD_ID, "ignore_rtp"));
    public static final TagKey<Block> IGNORE_RTP_BLOCKS =
        TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(DeerDiaryCommands.MOD_ID, "ignore_rtp"));

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
                src.sendFailure(Component.literal("RTP cooldown: " + remaining + "s remaining."));
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

        src.sendSuccess(() -> Component.literal("Looking for a spot...")
            .withStyle(ChatFormatting.GRAY), false);

        BlockPos origin = level.getSharedSpawnPos();
        BlockPos target = findSafeDestination(level, origin, cfg);
        if (target == null) {
            src.sendFailure(Component.literal(
                "Could not find a safe destination after " + cfg.max_tries + " tries. Try again."));
            return 0;
        }

        player.teleportTo(level, target.getX() + 0.5, target.getY(), target.getZ() + 0.5,
            Set.of(), player.getYRot(), player.getXRot());
        LAST_USE.put(player.getUUID(), now);
        int distance = (int) Math.round(Math.sqrt(target.distSqr(origin)));
        src.sendSuccess(() -> Component.literal(String.format(
            "Teleported to %d, %d, %d (%d blocks from spawn).",
            target.getX(), target.getY(), target.getZ(), distance))
            .withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static BlockPos findSafeDestination(ServerLevel level, BlockPos origin, DDCConfig.Rtp cfg) {
        for (int attempt = 0; attempt < cfg.max_tries; attempt++) {
            // Sample uniformly in an annulus between minDistance and maxDistance.
            double dist = cfg.min_distance + RNG.nextDouble() * (cfg.max_distance - cfg.min_distance);
            double angle = RNG.nextDouble() * Math.PI * 2.0;
            int dx = Mth.floor(Math.cos(angle) * dist);
            int dz = Mth.floor(Math.sin(angle) * dist);
            BlockPos candidate = new BlockPos(origin.getX() + dx, 64, origin.getZ() + dz);

            if (!level.getWorldBorder().isWithinBounds(candidate)) continue;
            if (level.getBiome(candidate).is(IGNORE_RTP_BIOMES)) continue;

            // Pre-load the chunk so heightmap and block lookups are valid.
            level.getChunkAt(candidate);
            BlockPos surface = level.getHeightmapPos(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, candidate);

            BlockPos resolved;
            if (surface.getY() > level.getMinBuildHeight()
                && surface.getY() < level.getLogicalHeight()) {
                resolved = pickFloor(level, surface);
            } else {
                // Heightmap is unusable (Nether-like): spiral at sea level for
                // a solid block with two clear blocks above.
                resolved = spiralFallback(level, new BlockPos(
                    surface.getX(), level.getSeaLevel(), surface.getZ()));
            }
            if (resolved != null) return resolved.above();
        }
        return null;
    }

    private static BlockPos pickFloor(ServerLevel level, BlockPos top) {
        BlockState floor = level.getBlockState(top.below());
        if (!floor.blocksMotion()) return null;
        if (floor.is(IGNORE_RTP_BLOCKS)) return null;
        if (!floor.getFluidState().isEmpty()) return null;
        BlockState feet = level.getBlockState(top);
        BlockState head = level.getBlockState(top.above());
        if (!isClear(feet) || !isClear(head)) return null;
        return top.below();
    }

    private static BlockPos spiralFallback(ServerLevel level, BlockPos start) {
        for (BlockPos pos : BlockPos.spiralAround(start, 16, Direction.EAST, Direction.SOUTH)) {
            BlockState block = level.getBlockState(pos);
            if (!block.blocksMotion()) continue;
            if (block.is(IGNORE_RTP_BLOCKS)) continue;
            BlockState a1 = level.getBlockState(pos.above());
            BlockState a2 = level.getBlockState(pos.above(2));
            BlockState a3 = level.getBlockState(pos.above(3));
            if (isClear(a1) && isClear(a2) && isClear(a3)) return pos.immutable();
        }
        return null;
    }

    private static boolean isClear(BlockState s) {
        return s.isAir() || (s.canBeReplaced() && s.getFluidState().isEmpty());
    }

    private static boolean matchesAny(List<String> patterns, String dimId) {
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
}
