package me.cortex.nvidium.compat.iris;

import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.mixin.minecraft.LightTextureAccessor;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.gl.program.ProgramUniforms;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.samplers.IrisSamplers;
import net.irisshaders.iris.targets.RenderTargets;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;

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

    /** Fires once on the first beginPass call, then never again. */
    private static volatile boolean DIAG_LOGGED = false;

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

            // Bind block atlas and lightmap explicitly, mirroring PrimaryTerrainRasterizer's vanilla
            // path. At Nvidium's terrain-render point RenderSystem.getShaderTexture(0) is NOT the
            // block atlas (it holds whatever the last Sodium/vanilla draw left there), so reading it
            // produces black albedo. We fetch the real IDs the same way the vanilla rasterizer does.
            Minecraft.getInstance().gameRenderer.lightTexture().turnOnLightLayer();

            // Block atlas → unit 0 (the `gtexture`/`tex` sampler in the Sodium-style fragment).
            int blockAtlasId = Minecraft.getInstance().getTextureManager()
                    .getTexture(ResourceLocation.fromNamespaceAndPath("minecraft", "textures/atlas/blocks.png"))
                    .getId();
            IrisRenderSystem.bindTextureToUnit(TextureType.TEXTURE_2D.getGlType(), 0, blockAtlasId);

            // Lightmap → Iris's designated lightmap unit (IrisSamplers.LIGHTMAP_TEXTURE_UNIT).
            int lightmapId = ((LightTextureAccessor) Minecraft.getInstance().gameRenderer.lightTexture())
                    .getLightTexture().getId();
            IrisRenderSystem.bindTextureToUnit(TextureType.TEXTURE_2D.getGlType(),
                    IrisSamplers.LIGHTMAP_TEXTURE_UNIT, lightmapId);

            // Matrices first (some custom uniforms may depend on them), then dynamic push.
            program.setMatrices(matrices.modelView(), matrices.projection());
            program.updateUniformsAndSamplers();

            // -------------------------------------------------------------------------
            // ONE-TIME DEPTH DIAGNOSTIC — fires only on the very first pass that binds.
            // Reads GL state + attachment info; NEVER changes any rendering state.
            // -------------------------------------------------------------------------
            if (!DIAG_LOGGED) {
                DIAG_LOGGED = true;
                try {
                    // 1. Bound draw FBO id (should match fbo's id while still bound).
                    int drawFboId = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING); // 0x8CA6

                    // 2. Depth attachment type and object id on the currently-bound FBO.
                    int attachType = GL30C.glGetFramebufferAttachmentParameteri(
                            GL30C.GL_DRAW_FRAMEBUFFER,
                            GL30C.GL_DEPTH_ATTACHMENT,
                            GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
                    int attachId = GL30C.glGetFramebufferAttachmentParameteri(
                            GL30C.GL_DRAW_FRAMEBUFFER,
                            GL30C.GL_DEPTH_ATTACHMENT,
                            GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);

                    String attachTypeName;
                    if (attachType == GL11C.GL_NONE) {           // 0
                        attachTypeName = "NONE";
                    } else if (attachType == GL11C.GL_TEXTURE) { // 0x1702
                        attachTypeName = "TEXTURE";
                    } else if (attachType == 0x8D41) {           // GL_RENDERBUFFER
                        attachTypeName = "RENDERBUFFER";
                    } else {
                        attachTypeName = "raw(0x" + Integer.toHexString(attachType) + ")";
                    }

                    // 3. MC main render target depth texture id (for cross-reference).
                    int mcMainDepthTex = Minecraft.getInstance().getMainRenderTarget().getDepthTextureId();

                    // 4. Iris RenderTargets depth texture id (the one Iris routes through its FBOs).
                    int irisDepthTex = -1;
                    try {
                        RenderTargets rt = ((NvidiumIrisRenderingPipelineAccessor) pipeline).nvidium$getRenderTargets();
                        if (rt != null) {
                            irisDepthTex = rt.getDepthTexture();
                        }
                    } catch (Throwable ignored) {
                        // Not critical; -1 means "unreachable"
                    }

                    // 5. Depth state that the draw will use.
                    boolean depthTestEnabled = GL11C.glGetBoolean(GL11C.GL_DEPTH_TEST);
                    boolean depthMaskEnabled = GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK);
                    int depthFunc = GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC);

                    Nvidium.LOGGER.info(
                            "Nvidium-Iris DEPTH-DIAG[{}]: drawFBO={} depthAttachType={} depthAttachId={}" +
                            " mcMainDepthTex={} irisRenderTargetsDepthTex={}" +
                            " depthTest={} depthMask={} depthFunc=0x{}",
                            pass,
                            drawFboId,
                            attachTypeName,
                            attachId,
                            mcMainDepthTex,
                            irisDepthTex,
                            depthTestEnabled,
                            depthMaskEnabled,
                            Integer.toHexString(depthFunc));
                } catch (Throwable t) {
                    Nvidium.LOGGER.warn("Nvidium-Iris: depth diagnostic failed (non-fatal)", t);
                }
            }
            // -------------------------------------------------------------------------

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
