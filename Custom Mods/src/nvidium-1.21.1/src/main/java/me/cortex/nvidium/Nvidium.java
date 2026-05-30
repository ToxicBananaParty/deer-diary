package me.cortex.nvidium;

import me.cortex.nvidium.config.NvidiumConfig;
import net.minecraft.Util;
import net.neoforged.fml.ModList;
import org.lwjgl.opengl.GL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Nvidium {
    private static String modVersion;
    public static final Logger LOGGER = LoggerFactory.getLogger("Nvidium");
    public static boolean IS_COMPATIBLE = false;
    public static boolean IS_ENABLED = false;
    public static boolean IS_DEBUG = System.getProperty("nvidium.isDebug", "false").equals("TRUE");
    public static boolean SUPPORTS_PERSISTENT_SPARSE_ADDRESSABLE_BUFFER = true;
    public static boolean FORCE_DISABLE = false;

    public static NvidiumConfig config = NvidiumConfig.loadOrCreate();

    // Computed lazily on first access (the debug HUD), NOT in a static
    // initializer: Nvidium is class-loaded during Window.<init>, which runs
    // before NeoForge populates ModList.get(). Touching ModList that early
    // NPEs. By the time the HUD reads the version, mod loading is complete.
    public static String getModVersion() {
        if (modVersion == null) {
            var mod = ModList.get().getModContainerById("nvidium")
                    .orElseThrow(NullPointerException::new)
                    .getModInfo();
            var version = mod.getVersion().toString();
            var commit = String.valueOf(mod.getModProperties().get("commit"));
            modVersion = version + "-" + commit;
        }
        return modVersion;
    }

    public static void checkSystemIsCapable() {
        var cap = GL.getCapabilities();
        boolean supported = cap.GL_NV_mesh_shader &&
                cap.GL_NV_uniform_buffer_unified_memory &&
                cap.GL_NV_vertex_buffer_unified_memory &&
                cap.GL_NV_representative_fragment_test &&
                cap.GL_ARB_sparse_buffer &&
                cap.GL_NV_bindless_multi_draw_indirect;
        IS_COMPATIBLE = supported;
        if (IS_COMPATIBLE) {
            LOGGER.info("All capabilities met");
        } else {
            LOGGER.warn("Not all requirements met, disabling nvidium");
        }
        if (IS_COMPATIBLE && Util.getPlatform() == Util.OS.LINUX) {
            LOGGER.warn("Linux currently uses fallback terrain buffer due to driver inconsistencies, expect increase vram usage");
            SUPPORTS_PERSISTENT_SPARSE_ADDRESSABLE_BUFFER = false;
        }

        if (IS_COMPATIBLE) {
            LOGGER.info("Enabling Nvidium");
        }
        IS_ENABLED = IS_COMPATIBLE;
    }
}
