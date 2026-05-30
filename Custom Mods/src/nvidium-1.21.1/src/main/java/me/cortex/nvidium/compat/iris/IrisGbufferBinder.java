package me.cortex.nvidium.compat.iris;

import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.nvidium.Nvidium;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.gl.program.ProgramUniforms;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.samplers.IrisSamplers;
import net.minecraft.client.Minecraft;

/**
 * Per-pass FBO bind / program setup / restore for the Nvidium &harr; Iris terrain integration.
 *
 * <p>{@link #beginPass} binds the shaderpack's gbuffer framebuffer for the pass, activates the
 * built mesh-shader program, pushes matrices + dynamic uniforms/samplers, and binds the block
 * atlas (unit 0) and lightmap (unit 2) that the Sodium-style fragment reads (findings Q4 — those
 * two units are NOT covered by ProgramSamplers and must be bound manually).
 *
 * <p>{@link #endPass} restores GL state: unbinds the program, clears Iris's active-uniform /
 * active-sampler tracking, and rebinds the main render target. Restoring the main target also
 * protects later consumers (e.g. Colorwheel) from inheriting our FBO binding.
 *
 * <p>Every entry point is wrapped so a failure leaves Nvidium running its own path rather than
 * crashing — callers gate on {@code Nvidium.MODE == RenderMode.SHADERS}, which is only ever set
 * when {@link IrisProgramBridge} has already built a usable program.
 */
public final class IrisGbufferBinder {
    private IrisGbufferBinder() {}

    /**
     * Bind the pass's gbuffer FBO + program and prime all uniforms/samplers for the draw.
     *
     * @return the bound program, or {@code null} if anything went wrong (caller must then NOT draw
     *         through Iris and should restore the main target itself).
     */
    public static IrisTerrainProgram beginPass(IrisRenderingPipeline pipeline,
                                               IrisProgramBridge.BuiltPrograms programs,
                                               TerrainRenderPass pass,
                                               ChunkRenderMatrices matrices) {
        try {
            IrisTerrainProgram program = programs.get(pass);
            if (program == null) {
                return null;
            }

            // Reuse Sodium's gbuffer framebuffer for this pass; getFramebuffer auto-redirects to
            // the shadow FBO when shadows are being rendered (we defer shadow draws, so in the
            // gbuffer path this is the terrain target with the pack's draw buffers).
            GlFramebuffer fbo = pipeline.getSodiumPrograms().getFramebuffer(pass);
            if (fbo == null) {
                return null;
            }
            fbo.bind();

            program.bind();

            // Bind block atlas (unit 0) and lightmap (unit 2) manually — Sodium does the same; the
            // patched fragment samples gtexture/tex from unit 0 and lightmap from unit 2.
            Minecraft.getInstance().gameRenderer.lightTexture().turnOnLightLayer();
            IrisRenderSystem.bindTextureToUnit(TextureType.TEXTURE_2D.getGlType(), 0,
                    RenderSystem.getShaderTexture(0));
            IrisRenderSystem.bindTextureToUnit(TextureType.TEXTURE_2D.getGlType(),
                    IrisSamplers.LIGHTMAP_TEXTURE_UNIT, RenderSystem.getShaderTexture(2));

            // Matrices first (some custom uniforms may depend on them), then dynamic push.
            program.setMatrices(matrices.modelView(), matrices.projection());
            program.updateUniformsAndSamplers();

            return program;
        } catch (Throwable t) {
            Nvidium.LOGGER.warn("Nvidium-Iris: beginPass({}) failed; aborting Iris draw for this pass", pass, t);
            try {
                endPass();
            } catch (Throwable ignored) {
                // best-effort restore
            }
            return null;
        }
    }

    /** Restore GL state after an Iris-driven pass. Always safe to call; never throws. */
    public static void endPass() {
        try {
            org.lwjgl.opengl.GL32.glUseProgram(0);
            ProgramUniforms.clearActiveUniforms();
            ProgramSamplers.clearActiveSamplers();
            Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
        } catch (Throwable t) {
            Nvidium.LOGGER.warn("Nvidium-Iris: endPass() restore hit an error", t);
        }
    }
}
