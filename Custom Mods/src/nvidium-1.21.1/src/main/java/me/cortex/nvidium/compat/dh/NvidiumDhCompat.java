package me.cortex.nvidium.compat.dh;

import me.cortex.nvidium.Nvidium;
import net.neoforged.fml.ModList;

/**
 * Coordinates Nvidium near-terrain rendering with Distant Horizons far LODs.
 * Soft dependency: every DH (com.seibel.*) symbol lives in DhBridge, only ever
 * touched after this isLoaded() guard, so Nvidium runs unchanged without DH.
 */
public final class NvidiumDhCompat {
    public static boolean ACTIVE = false;
    private NvidiumDhCompat() {}

    public static void init() {
        if (!ModList.get().isLoaded("distanthorizons")) {
            return;
        }
        try {
            DhBridge.bind();
            ACTIVE = true;
            Nvidium.LOGGER.info("Distant Horizons detected - Nvidium DH coexistence active");
        } catch (Throwable t) {
            ACTIVE = false;
            Nvidium.LOGGER.warn("Failed to init Nvidium<->DH coexistence; DH may double-draw", t);
        }
    }
}
