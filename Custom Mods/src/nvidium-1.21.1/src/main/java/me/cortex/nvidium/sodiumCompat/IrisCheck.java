package me.cortex.nvidium.sodiumCompat;

import net.irisshaders.iris.api.v0.IrisApi;
import net.neoforged.fml.ModList;

public class IrisCheck {
    public static final boolean IRIS_LOADED = ModList.get().isLoaded("iris");

    /** True when Iris is present AND a shaderpack is currently in use. */
    public static boolean isShaderPackActive() {
        return IRIS_LOADED && IrisApi.getInstance().isShaderPackInUse();
    }

    /** True while Iris is rendering its shadow pass (used by the Iris phase). */
    public static boolean isRenderingShadowPass() {
        return IRIS_LOADED && IrisApi.getInstance().isRenderingShadowPass();
    }
}
