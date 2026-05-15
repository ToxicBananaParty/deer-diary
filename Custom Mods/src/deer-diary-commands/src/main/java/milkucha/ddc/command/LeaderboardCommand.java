package milkucha.ddc.command;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import milkucha.ddc.DeerDiaryCommands;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * {@code /leaderboard <stat>} — print top-N for one of the registered stats.
 * Stats are read from per-player {@code stats/<UUID>.json}; players who have
 * ever joined the server appear here whether they're online or not.
 *
 * Medal coloring: gold (#1), silver-blue (#2), bronze (#3), gray (rest).
 * The caller's own row is highlighted green.
 */
public final class LeaderboardCommand {
    private LeaderboardCommand() {}

    private static final int DEFAULT_TOP = 20;
    private static final int MAX_TOP = 100;

    private record Stat(String name, String category, String key,
                        Function<Long, String> formatter,
                        Function<Map<String, JsonObject>, Long> custom) {
        Stat(String name, String category, String key, Function<Long, String> formatter) {
            this(name, category, key, formatter, null);
        }
    }

    private static final Map<String, Stat> STATS = new LinkedHashMap<>();

    static {
        // Vanilla minecraft:custom stat keys — values are raw integers.
        register(new Stat("deaths", "minecraft:custom", "minecraft:deaths",
            n -> Long.toString(n)));
        register(new Stat("time_played", "minecraft:custom", "minecraft:play_time",
            LeaderboardCommand::formatTicks));
        register(new Stat("player_kills", "minecraft:custom", "minecraft:player_kills",
            n -> Long.toString(n)));
        register(new Stat("mob_kills", "minecraft:custom", "minecraft:mob_kills",
            n -> Long.toString(n)));
        register(new Stat("damage_dealt", "minecraft:custom", "minecraft:damage_dealt",
            n -> String.format("%.1f", n / 10.0)));
        register(new Stat("jumps", "minecraft:custom", "minecraft:jump",
            n -> Long.toString(n)));
        register(new Stat("distance_walked", "minecraft:custom", "minecraft:walk_one_cm",
            n -> {
                double m = n / 100.0;
                if (m >= 1000) return String.format("%.2f km", m / 1000.0);
                if (m >= 1)    return String.format("%.1f m", m);
                return n + " cm";
            }));
        register(new Stat("time_since_death", "minecraft:custom", "minecraft:time_since_death",
            LeaderboardCommand::formatTicks));
        // Derived: deaths / playtime, scaled to "per hour" (72000 ticks).
        register(new Stat("deaths_per_hour", "", "", null,
            stats -> {
                JsonObject custom = stats.get("minecraft:custom");
                if (custom == null) return 0L;
                long deaths = optLong(custom, "minecraft:deaths");
                long ticks = optLong(custom, "minecraft:play_time");
                if (deaths <= 0 || ticks < 72_000) return 0L;
                // Store as fixed-point (×1000) so we can sort numerically.
                return Math.round(deaths * 72_000.0 / ticks * 1000.0);
            }));
    }

    private static void register(Stat s) { STATS.put(s.name(), s); }

    private static String formatTicks(long ticks) {
        double sec = ticks / 20.0;
        if (sec >= 86_400 * 365 * 0.5) return String.format("%.2f y", sec / (86_400 * 365));
        if (sec >= 86_400 * 0.5)       return String.format("%.2f d", sec / 86_400);
        if (sec >= 3_600 * 0.5)        return String.format("%.2f h", sec / 3_600);
        if (sec >= 60 * 0.5)           return String.format("%.2f m", sec / 60);
        return String.format("%.0f s", sec);
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("leaderboard");
        for (String name : STATS.keySet()) {
            root.then(Commands.literal(name)
                .executes(ctx -> run(ctx.getSource(), name, DEFAULT_TOP))
                .then(Commands.argument("top", IntegerArgumentType.integer(1, MAX_TOP))
                    .executes(ctx -> run(ctx.getSource(), name,
                        IntegerArgumentType.getInteger(ctx, "top")))));
        }
        dispatcher.register(root);
    }

    private static int run(CommandSourceStack src, String statName, int top) {
        Stat stat = STATS.get(statName);
        if (stat == null) {
            src.sendFailure(Component.literal("Unknown stat: " + statName));
            return 0;
        }
        MinecraftServer server = src.getServer();
        Path statsDir = server.getWorldPath(LevelResource.PLAYER_STATS_DIR);
        if (!Files.isDirectory(statsDir)) {
            src.sendFailure(Component.literal("No player stats found yet."));
            return 0;
        }

        record Row(UUID id, String name, long value) {}
        List<Row> rows = new ArrayList<>();
        try (Stream<Path> stream = Files.list(statsDir)) {
            for (Path file : stream.toList()) {
                if (!file.toString().endsWith(".json")) continue;
                String uuidStr = file.getFileName().toString().replace(".json", "");
                UUID id;
                try { id = UUID.fromString(uuidStr); } catch (IllegalArgumentException e) { continue; }
                long v = extract(file, stat);
                if (v <= 0) continue;
                rows.add(new Row(id, resolveName(server, id), v));
            }
        } catch (IOException e) {
            DeerDiaryCommands.LOGGER.error("[DDC] Failed to enumerate stats", e);
            src.sendFailure(Component.literal("Failed to read stats; see server log."));
            return 0;
        }

        rows.sort(Comparator.comparingLong((Row r) -> r.value).reversed());
        if (rows.isEmpty()) {
            src.sendSuccess(() -> Component.literal("No data for " + statName + " yet.")
                .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }

        UUID self = src.getEntity() != null ? src.getEntity().getUUID() : null;
        int limit = Math.min(top, rows.size());
        src.sendSuccess(() -> Component.literal("== Leaderboard: " + statName + " ==")
            .withStyle(ChatFormatting.DARK_GREEN), false);

        for (int i = 0; i < limit; i++) {
            Row row = rows.get(i);
            String rank = String.format("#%02d ", i + 1);
            MutableComponent line = Component.literal("")
                .append(Component.literal(rank).withStyle(rankStyle(i)))
                .append(Component.literal(row.name)
                    .withStyle(row.id.equals(self) ? ChatFormatting.GREEN : ChatFormatting.YELLOW))
                .append(Component.literal(": ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(formatStatValue(stat, row.value))
                    .withStyle(ChatFormatting.WHITE));
            src.sendSuccess(() -> line, false);
        }
        return limit;
    }

    private static Style rankStyle(int i) {
        return switch (i) {
            case 0 -> Style.EMPTY.withColor(ChatFormatting.GOLD);
            case 1 -> Style.EMPTY.withColor(TextColor.fromRgb(0xB0D9FF));
            case 2 -> Style.EMPTY.withColor(TextColor.fromRgb(0xCD7F32));
            default -> Style.EMPTY.withColor(TextColor.fromRgb(0xB4B4B4));
        };
    }

    private static String formatStatValue(Stat stat, long value) {
        if (stat.name().equals("deaths_per_hour")) {
            return String.format("%.3f", value / 1000.0);
        }
        return stat.formatter().apply(value);
    }

    private static long extract(Path file, Stat stat) {
        try {
            String json = Files.readString(file);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonElement statsEl = root.get("stats");
            if (statsEl == null || !statsEl.isJsonObject()) return 0;
            JsonObject statsObj = statsEl.getAsJsonObject();
            if (stat.custom() != null) {
                Map<String, JsonObject> bag = new LinkedHashMap<>();
                for (var entry : statsObj.entrySet()) {
                    if (entry.getValue().isJsonObject()) bag.put(entry.getKey(), entry.getValue().getAsJsonObject());
                }
                return stat.custom().apply(bag);
            }
            JsonElement cat = statsObj.get(stat.category());
            if (cat == null || !cat.isJsonObject()) return 0;
            return optLong(cat.getAsJsonObject(), stat.key());
        } catch (Exception e) {
            return 0;
        }
    }

    private static long optLong(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null) return 0;
        try { return el.getAsLong(); } catch (Exception e) { return 0; }
    }

    private static String resolveName(MinecraftServer server, UUID id) {
        Optional<GameProfile> cached = server.getProfileCache() != null
            ? server.getProfileCache().get(id)
            : Optional.empty();
        return cached.map(GameProfile::getName).orElse(id.toString().substring(0, 8));
    }
}
