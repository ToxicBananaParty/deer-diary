package milkucha.ddc.util;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.network.chat.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Parse short duration strings like {@code 30s}, {@code 15m}, {@code 2h},
 * {@code 1d}, {@code 1w}, or {@code permanent} / {@code perm} / empty / "*".
 * Returns milliseconds (0 = permanent). Format matches FTB Essentials.
 */
public final class DurationParser {
    private DurationParser() {}

    public static final SimpleCommandExceptionType INVALID_FORMAT =
        new SimpleCommandExceptionType(Component.literal(
            "Bad duration. Expected <number><unit> (e.g. 30m, 2h, 1d, 5s) or 'permanent'."));

    private static final Map<Character, Long> UNIT_MS = Map.of(
        's', 1_000L,
        'm', 60_000L,
        'h', 3_600_000L,
        'd', 86_400_000L,
        'w', 604_800_000L);

    /** Returns 0 for permanent/indefinite. */
    public static long parseToMillis(String raw) throws CommandSyntaxException {
        if (raw == null) return 0L;
        String s = raw.trim().toLowerCase();
        if (s.isEmpty() || s.equals("*") || s.startsWith("perm")) return 0L;
        if (s.length() < 2) throw INVALID_FORMAT.create();
        char unit = s.charAt(s.length() - 1);
        Long unitMs = UNIT_MS.get(unit);
        if (unitMs == null) throw INVALID_FORMAT.create();
        String numPart = s.substring(0, s.length() - 1);
        double n;
        try {
            n = Double.parseDouble(numPart);
        } catch (NumberFormatException e) {
            throw INVALID_FORMAT.create();
        }
        if (n < 0) throw INVALID_FORMAT.create();
        return (long) (n * unitMs);
    }

    /** Human-friendly form, e.g. "2h 30m" or "permanent". */
    public static String prettyPrint(long ms) {
        if (ms <= 0) return "permanent";
        long s = ms / 1000;
        long d = s / 86_400; s %= 86_400;
        long h = s / 3_600;  s %= 3_600;
        long m = s / 60;     s %= 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("d ");
        if (h > 0) sb.append(h).append("h ");
        if (m > 0) sb.append(m).append("m ");
        if (s > 0 || sb.length() == 0) sb.append(s).append("s");
        return sb.toString().trim();
    }

    public static CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestDurations(SuggestionsBuilder b) {
        for (String s : new String[]{"30s", "1m", "5m", "15m", "30m", "1h", "2h", "6h", "12h", "1d", "1w", "permanent"}) {
            b.suggest(s);
        }
        return b.buildFuture();
    }
}
