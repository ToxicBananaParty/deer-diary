package me.cortex.nvidium.compat.iris;

import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.sodiumCompat.IrisCheck;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;

/**
 * Detection + supported-pack gate for the Nvidium &harr; Iris terrain integration.
 *
 * <p>This is the single authority {@link me.cortex.nvidium.RenderMode#resolve()} consults to
 * decide whether Nvidium may render terrain through the active shaderpack. It returns
 * {@code false} on ANY uncertainty or failure so the arbiter yields to Sodium+Iris and Nvidium
 * never crashes the frame.
 *
 * <p>All Iris symbols touched here are guarded by {@link IrisCheck#IRIS_LOADED}; this class is
 * only ever loaded after that guard at the call site, mirroring the DH-bridge isolation pattern.
 */
public final class NvidiumIrisCompat {
    private NvidiumIrisCompat() {}

    /**
     * True when Iris is loaded, a shaderpack is in use, and the live pipeline is an
     * {@link IrisRenderingPipeline} (the only pipeline type we can drive). Pure detection; no
     * program building.
     */
    public static boolean active() {
        if (!IrisCheck.IRIS_LOADED) {
            return false;
        }
        try {
            if (!IrisApi.getInstance().isShaderPackInUse()) {
                return false;
            }
            WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
            return pipeline instanceof IrisRenderingPipeline;
        } catch (Throwable t) {
            // Any failure reaching Iris means we are not safely active.
            return false;
        }
    }

    /**
     * True when {@link #active()} AND the Iris bridge has built (or can build) the terrain
     * programs for the current pipeline. The build result is cached per pipeline instance inside
     * {@link IrisProgramBridge}; a failed build is cached as unsupported so we yield permanently
     * for that pack (until a shaderpack reload swaps the pipeline).
     *
     * <p>This is what {@code RenderMode.resolve()} calls. It must NEVER throw.
     */
    public static boolean supportsActivePack() {
        if (!active()) {
            return false;
        }
        try {
            IrisRenderingPipeline pipeline =
                    (IrisRenderingPipeline) Iris.getPipelineManager().getPipelineNullable();
            return IrisProgramBridge.getOrBuild(pipeline) != null;
        } catch (Throwable t) {
            Nvidium.LOGGER.warn("Nvidium-Iris: supportsActivePack() failed; yielding to Sodium+Iris", t);
            return false;
        }
    }
}
