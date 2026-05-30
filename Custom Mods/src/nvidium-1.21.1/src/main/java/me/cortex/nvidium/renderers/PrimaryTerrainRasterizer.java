package me.cortex.nvidium.renderers;

import com.mojang.blaze3d.platform.GlStateManager;
import me.cortex.nvidium.compat.iris.IrisTerrainProgram;
import me.cortex.nvidium.gl.shader.Shader;
import me.cortex.nvidium.sodiumCompat.ShaderLoader;
import net.minecraft.client.Minecraft;
import me.cortex.nvidium.util.FrameTimeProfiler;
import net.minecraft.resources.ResourceLocation;
import me.cortex.nvidium.mixin.minecraft.LightTextureAccessor;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL45;
import org.lwjgl.opengl.GL45C;

import static me.cortex.nvidium.RenderPipeline.GL_DRAW_INDIRECT_ADDRESS_NV;
import static me.cortex.nvidium.gl.shader.ShaderType.*;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL33.glGenSamplers;
import static org.lwjgl.opengl.NVMeshShader.glMultiDrawMeshTasksIndirectNV;
import static org.lwjgl.opengl.NVVertexBufferUnifiedMemory.glBufferAddressRangeNV;

public class PrimaryTerrainRasterizer extends Phase {
    private final int blockSampler = glGenSamplers();
    private final int lightSampler = glGenSamplers();
    private final Shader shader = Shader.make()
            .addSource(TASK, ShaderLoader.parse(ResourceLocation.fromNamespaceAndPath("nvidium", "terrain/task.glsl")))
            .addSource(MESH, ShaderLoader.parse(ResourceLocation.fromNamespaceAndPath("nvidium", "terrain/mesh.glsl")))
            .addSource(FRAGMENT, ShaderLoader.parse(ResourceLocation.fromNamespaceAndPath("nvidium", "terrain/frag.frag"))).compile();

    public PrimaryTerrainRasterizer() {
        GL45C.glSamplerParameteri(blockSampler,     GL45C.GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_LINEAR);
        GL45C.glSamplerParameteri(blockSampler, GL45C.GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        GL45C.glSamplerParameteri(blockSampler, GL45C.GL_TEXTURE_MIN_LOD, 0);
        GL45C.glSamplerParameteri(blockSampler, GL45C.GL_TEXTURE_MAX_LOD, 4);
        GL45C.glSamplerParameteri(lightSampler, GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
        GL45C.glSamplerParameteri(lightSampler, GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
        GL45C.glSamplerParameteri(lightSampler, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        GL45C.glSamplerParameteri(lightSampler, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    }

    // When set (SHADERS mode), the terrain raster is issued through the Iris shaderpack's terrain
    // program instead of Nvidium's own `shader`. The IrisGbufferBinder has already bound the FBO,
    // the program, and the block-atlas/lightmap texture units, so this path does NOT bind Nvidium's
    // own shader or samplers. Null in VANILLA mode, which is byte-for-byte the original behavior.
    private IrisTerrainProgram irisProgram = null;

    public void setIrisProgram(IrisTerrainProgram program) {
        this.irisProgram = program;
    }

    public void clearIrisProgram() {
        this.irisProgram = null;
    }

    private static void setTexture(int textureId, int bindingPoint) {
        GlStateManager._activeTexture(33984 + bindingPoint);
        GlStateManager._bindTexture(textureId);
    }

    public void raster(int regionCount, long commandAddr, FrameTimeProfiler frameTimeProfiler) {
        if (irisProgram != null) {
            // SHADERS path: program + textures already bound by IrisGbufferBinder.beginPass.
            glBufferAddressRangeNV(GL_DRAW_INDIRECT_ADDRESS_NV, 0, commandAddr, regionCount*8L);
            frameTimeProfiler.startQuery();
            glMultiDrawMeshTasksIndirectNV(0, regionCount, 0);
            frameTimeProfiler.endQuery();
            return;
        }

        shader.bind();

        int blockId = Minecraft.getInstance().getTextureManager().getTexture(ResourceLocation.fromNamespaceAndPath("minecraft", "textures/atlas/blocks.png")).getId();
        int lightId = ((LightTextureAccessor)Minecraft.getInstance().gameRenderer.lightTexture()).getLightTexture().getId();

        GL45C.glBindSampler(0, blockSampler);
        GL45C.glBindSampler(1, lightSampler);
        setTexture(blockId, 0);
        setTexture(lightId, 1);

        glBufferAddressRangeNV(GL_DRAW_INDIRECT_ADDRESS_NV, 0, commandAddr, regionCount*8L);//Bind the command buffer
        frameTimeProfiler.startQuery();
        glMultiDrawMeshTasksIndirectNV( 0, regionCount, 0);
        frameTimeProfiler.endQuery();
        GL45C.glBindSampler(0, 0);
        GL45C.glBindSampler(1, 0);
    }

    public void delete() {
        GL45.glDeleteSamplers(blockSampler);
        GL45.glDeleteSamplers(lightSampler);
        shader.delete();
    }
}
