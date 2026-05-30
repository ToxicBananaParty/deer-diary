package me.cortex.nvidium;

import me.cortex.nvidium.compat.iris.NvidiumIrisCompat;
import me.cortex.nvidium.sodiumCompat.IrisCheck;

/**
 * Per-frame decision of how Nvidium participates in rendering. This is the
 * single fallback-floor authority: anything uncertain resolves to a mode that
 * yields to Sodium, never a crash.
 */
public enum RenderMode {
    /** Hardware unsupported or force-disabled: Nvidium does nothing. */
    DISABLED,
    /** Nvidium renders terrain, no shaderpack active (the original working path). */
    VANILLA,
    /** Nvidium renders terrain THROUGH the active, supported Iris shaderpack. */
    SHADERS;

    /**
     * Resolve the mode for the current frame. Called where IS_ENABLED was set.
     * SHADERS is only returned once a later phase reports a usable Iris
     * integration for the active pack; until then, shaders-active resolves to
     * DISABLED (yield to Sodium+Iris), preserving the original behavior.
     */
    public static RenderMode resolve() {
        if (!Nvidium.IS_COMPATIBLE || Nvidium.FORCE_DISABLE) {
            return DISABLED;
        }
        if (IrisCheck.isShaderPackActive()) {
            // Render terrain through the shaderpack only if a usable Iris terrain program has
            // actually been built for the active pack; otherwise yield to Sodium+Iris. This is
            // the fallback floor: any uncertainty resolves to DISABLED, never a crash.
            return NvidiumIrisCompat.supportsActivePack() ? SHADERS : DISABLED;
        }
        return VANILLA;
    }
}
