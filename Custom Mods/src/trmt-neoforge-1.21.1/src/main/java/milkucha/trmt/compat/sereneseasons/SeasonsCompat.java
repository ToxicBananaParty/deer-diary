package milkucha.trmt.compat.sereneseasons;

import milkucha.trmt.TRMT;
import milkucha.trmt.TRMTConfig;
import milkucha.trmt.erosion.EntityStepHandler;
import net.minecraft.server.level.ServerLevel;
import sereneseasons.api.season.ISeasonState;
import sereneseasons.api.season.Season;
import sereneseasons.api.season.SeasonHelper;

/**
 * SereneSeasons integration. Modulates the per-step erosion multiplier
 * based on the current season — frozen ground in winter resists erosion;
 * wet leaves and soft autumn ground erode faster.
 *
 * <p>Multipliers are read from {@link TRMTConfig.SeasonsMultipliers} so users
 * can tune them or disable the effect entirely.
 *
 * <p>Loaded only if the {@code sereneseasons} mod is present at runtime.
 * Wired from {@link TRMT}'s {@code registerOptionalCompatModules} via a
 * {@link net.neoforged.fml.ModList#isLoaded} guard.
 */
public final class SeasonsCompat {

    public static final String MOD_ID = "sereneseasons";

    private SeasonsCompat() {}

    public static void register() {
        EntityStepHandler.addStepMultiplierModifier(SeasonsCompat::seasonMultiplier);
        TRMT.LOGGER.info("[TRMT] SereneSeasons compat enabled — erosion rate will vary by season.");
    }

    /**
     * Returns the multiplier to apply to the per-step erosion count based on
     * the current season in {@code level}. Returns {@code 1.0} (identity) if
     * the feature is disabled in config or the season can't be read.
     */
    public static double seasonMultiplier(ServerLevel level) {
        TRMTConfig.SeasonsMultipliers cfg = TRMTConfig.get().seasonsMultipliers;
        if (!cfg.enabled) return 1.0;
        try {
            ISeasonState state = SeasonHelper.getSeasonState(level);
            Season season = state.getSeason();
            return switch (season) {
                case WINTER -> cfg.winter;
                case SPRING -> cfg.spring;
                case SUMMER -> cfg.summer;
                case AUTUMN -> cfg.autumn;
            };
        } catch (Throwable t) {
            // Defensive — if SereneSeasons errors (e.g. dimension not configured for seasons),
            // fall back to identity so erosion still works.
            return 1.0;
        }
    }
}
