package me.cortex.nvidium.compat.iris;

import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.renderers.PrimaryTerrainRasterizer;
import me.cortex.nvidium.renderers.TranslucentTerrainRasterizer;
import me.cortex.nvidium.sodiumCompat.IrisCheck;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;

/**
 * Per-frame glue between Nvidium's {@link me.cortex.nvidium.RenderPipeline} and the Iris terrain
 * integration. This is the ONLY entry point {@code RenderPipeline} touches; everything Iris-shaped
 * lives behind it so the hot render path keeps a single, easy-to-audit branch.
 *
 * <h2>Contract with RenderPipeline</h2>
 * For each terrain phase, {@code RenderPipeline} calls:
 * <pre>
 *   boolean iris = Nvidium.MODE == RenderMode.SHADERS &amp;&amp; IrisRenderBridge.beginXxx(rasterizer, crm);
 *   rasterizer.raster(...);          // draws through the Iris program iff begin returned true
 *   if (iris) IrisRenderBridge.endXxx(rasterizer);
 * </pre>
 * {@code beginXxx} returns {@code true} only when it has fully bound the shaderpack FBO + program +
 * texture units AND armed the rasterizer's Iris draw path ({@code setIrisProgram}). On ANY failure
 * it returns {@code false} having left the rasterizer in its VANILLA state (no Iris program set),
 * so the subsequent {@code raster(...)} runs Nvidium's own shader exactly as before. It never
 * throws to the caller; the worst case is a yield to Nvidium's own terrain shader for that phase.
 *
 * <h2>Pass mapping</h2>
 * Nvidium issues a single primary mesh draw covering opaque + cutout geometry, so the primary phase
 * binds the shaderpack's SOLID terrain program / gbuffer. CUTOUT is built by
 * {@link IrisProgramBridge} (so the pack is only considered supported when it links too) and is
 * available for a future opaque/cutout split, but the single primary draw maps to SOLID here.
 * Translucent maps to the pack's water/translucent program.
 *
 * <h2>Safety</h2>
 * All Iris symbols are reached only after {@link IrisCheck#IRIS_LOADED} (the class is loaded behind
 * that guard at the call site, and {@code Nvidium.MODE == SHADERS} already implies a built program).
 */
public final class IrisRenderBridge {
    private IrisRenderBridge() {}

    /**
     * Arm the primary (opaque + cutout) terrain draw to go through the shaderpack's SOLID program
     * into Iris's gbuffer. Returns {@code true} on success (caller must pair with
     * {@link #endPrimary}); {@code false} means yield to Nvidium's own shader for this phase.
     */
    public static boolean beginPrimary(PrimaryTerrainRasterizer rasterizer, ChunkRenderMatrices matrices) {
        return begin(DefaultTerrainRenderPasses.SOLID, matrices, rasterizer::setIrisProgram);
    }

    /** Restore after a primary Iris draw. Always safe; never throws. */
    public static void endPrimary(PrimaryTerrainRasterizer rasterizer) {
        end(rasterizer::clearIrisProgram);
    }

    /**
     * Arm the translucent terrain draw to go through the shaderpack's water/translucent program.
     * Returns {@code true} on success (pair with {@link #endTranslucent}); {@code false} yields.
     */
    public static boolean beginTranslucent(TranslucentTerrainRasterizer rasterizer, ChunkRenderMatrices matrices) {
        return begin(DefaultTerrainRenderPasses.TRANSLUCENT, matrices, rasterizer::setIrisProgram);
    }

    /** Restore after a translucent Iris draw. Always safe; never throws. */
    public static void endTranslucent(TranslucentTerrainRasterizer rasterizer) {
        end(rasterizer::clearIrisProgram);
    }

    /** Functional sink for {@code rasterizer.setIrisProgram(program)}. */
    private interface ProgramSetter {
        void set(IrisTerrainProgram program);
    }

    /** Functional sink for {@code rasterizer.clearIrisProgram()}. */
    private interface ProgramClearer {
        void clear();
    }

    private static boolean begin(TerrainRenderPass pass, ChunkRenderMatrices matrices, ProgramSetter setter) {
        try {
            IrisRenderingPipeline pipeline = activePipeline();
            if (pipeline == null) {
                return false;
            }
            IrisProgramBridge.BuiltPrograms programs = IrisProgramBridge.getOrBuild(pipeline);
            if (programs == null) {
                return false;
            }
            // Bind FBO + program + texture units and prime uniforms/samplers for this pass.
            IrisTerrainProgram program = IrisGbufferBinder.beginPass(pipeline, programs, pass, matrices);
            if (program == null) {
                return false;
            }
            // Arm the rasterizer's Iris draw path: it will issue the indirect draw against the
            // program the binder just bound, skipping Nvidium's own shader/sampler setup.
            setter.set(program);
            return true;
        } catch (Throwable t) {
            Nvidium.LOGGER.warn("Nvidium-Iris: begin({}) failed; yielding to Nvidium's own terrain shader", pass, t);
            // Make sure the rasterizer is NOT left armed, and the main target is restored.
            try {
                IrisGbufferBinder.endPass();
            } catch (Throwable ignored) {
                // best-effort
            }
            return false;
        }
    }

    private static void end(ProgramClearer clearer) {
        // Always disarm the rasterizer first so a later restore failure can't leave it pointing at
        // a stale program, then restore GL state.
        try {
            clearer.clear();
        } catch (Throwable ignored) {
            // best-effort
        }
        IrisGbufferBinder.endPass();
    }

    /**
     * The live pipeline cast to {@link IrisRenderingPipeline}, or {@code null} if Iris is absent, no
     * pack is in use, or the pipeline is some other type. Never throws.
     */
    private static IrisRenderingPipeline activePipeline() {
        if (!IrisCheck.IRIS_LOADED) {
            return null;
        }
        try {
            WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
            return pipeline instanceof IrisRenderingPipeline irisPipeline ? irisPipeline : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
