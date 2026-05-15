package milkucha.ddc.command;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.authlib.GameProfile;
import milkucha.ddc.DeerDiaryCommands;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * `/leaderboard [stat] [top]` — print a top-N ranking from vanilla per-player
 * stats. Reads `<world>/stats/<UUID>.json` for all players who have ever joined.
 * Default stat: `minecraft:play_time` (rendered as hours/minutes).
 *
 * Recognized aliases:
 *   playtime → minecraft:custom / minecraft:play_time (default)
 *   deaths   → minecraft:custom / minecraft:deaths
 *   jumps    → minecraft:custom / minecraft:jump
 *   distance → minecraft:custom / minecraft:walk_one_cm (rendered as km)
 *   mobkills → minecraft:custom / minecraft:mob_kills
 */
public final class LeaderboardCommand {
    private LeaderboardCommand() {}

    private static final int DEFAULT_TOP = 10;
    private static final int MAX_TOP = 100;

    private record StatPath(String category, String key, String render) {}

    private static StatPath aliasOrDefault(String name) {
        return switch (name.toLowerCase()) {
            case "deaths" -> new StatPath("minecraft:custom", "minecraft:deaths", "count");
            case "jumps", "jump" -> new StatPath("minecraft:custom", "minecraft:jump", "count");
            case "distance", "walk" -> new StatPath("minecraft:custom", "minecraft:walk_one_cm", "distance");
            case "mobkills", "mob_kills" -> new StatPath("minecraft:custom", "minecraft:mob_kills", "count");
            default -> new StatPath("minecraft:custom", "minecraft:play_time", "ticks");
        };
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("leaderboard")
            .executes(ctx -> run(ctx.getSource(), "playtime", DEFAULT_TOP))
            .then(Commands.argument("stat", StringArgumentType.word())
                .suggests((ctx, b) -> {
                    for (String s : List.of("playtime", "deaths", "jumps", "distance", "mobkills")) b.suggest(s);
                    return b.buildFuture();
                })
                .executes(ctx -> run(ctx.getSource(),
                    StringArgumentType.getString(ctx, "stat"), DEFAULT_TOP))
                .then(Commands.argument("top", IntegerArgumentType.integer(1, MAX_TOP))
                    .executes(ctx -> run(ctx.getSource(),
                        StringArgumentType.getString(ctx, "stat"),
                        IntegerArgumentType.getInteger(ctx, "top"))))));
    }

    private static int run(CommandSourceStack src, String statName, int top) {
        MinecraftServer server = src.getServer();
        StatPath path = aliasOrDefault(statName);

        Path statsDir = server.getWorldPath(LevelResource.PLAYER_STATS_DIR);
        if (!Files.isDirectory(statsDir)) {
            src.sendFailure(Component.literal("No player stats found yet."));
            return 0;
        }

        record Row(String name, long value) {}
        List<Row> rows = new ArrayList<>();
        try (Stream<Path> stream = Files.list(statsDir)) {
            for (Path file : stream.toList()) {
                if (!file.toString().endsWith(".json")) continue;
                String uuidStr = file.getFileName().toString().replace(".json", "");
                UUID id;
                try { id = UUID.fromString(uuidStr); } catch (IllegalArgumentException e) { continue; }

                long value = extractValue(file, path);
                if (value <= 0) continue;

                String name = resolveName(server, id);
                rows.add(new Row(name, value));
            }
        } catch (IOException e) {
            DeerDiaryCommands.LOGGER.error("[DDC] Failed to enumerate stats", e);
            src.sendFailure(Component.literal("Failed to read player stats; see server log."));
            return 0;
        }

        rows.sort(Comparator.comparingLong((Row r) -> r.value).reversed());
        if (rows.isEmpty()) {
            src.sendSuccess(() -> Component.literal("No players have recorded " + path.key + " yet.")
                .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }

        int limit = Math.min(top, rows.size());
        src.sendSuccess(() -> Component.literal(
            "Top " + limit + " by " + path.key + ":").withStyle(ChatFormatting.YELLOW), false);
        for (int i = 0; i < limit; i++) {
            Row row = rows.get(i);
            String pretty = renderValue(row.value, path.render);
            int rank = i + 1;
            src.sendSuccess(() -> Component.literal(
                String.format("  %2d. %s — %s", rank, row.name, pretty))
                .withStyle(ChatFormatting.GRAY), false);
        }
        return limit;
    }

    private static long extractValue(Path file, StatPath path) {
        try {
            String json = Files.readString(file);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonElement statsEl = root.get("stats");
            if (statsEl == null || !statsEl.isJsonObject()) return 0;
            JsonElement catEl = statsEl.getAsJsonObject().get(path.category);
            if (catEl == null || !catEl.isJsonObject()) return 0;
            JsonElement valEl = catEl.getAsJsonObject().get(path.key);
            if (valEl == null) return 0;
            return valEl.getAsLong();
        } catch (Exception e) {
            return 0;
        }
    }

    private static String renderValue(long value, String render) {
        return switch (render) {
            case "ticks" -> {
                long totalSec = value / 20;
                long h = totalSec / 3600;
                long m = (totalSec % 3600) / 60;
                yield h > 0 ? h + "h " + m + "m" : m + "m";
            }
            case "distance" -> String.format("%.1f km", value / 100_000.0);
            default -> Long.toString(value);
        };
    }

    private static String resolveName(MinecraftServer server, UUID id) {
        Optional<GameProfile> cached = server.getProfileCache() != null
            ? server.getProfileCache().get(id)
            : Optional.empty();
        return cached.map(GameProfile::getName).orElse(id.toString().substring(0, 8));
    }
}
