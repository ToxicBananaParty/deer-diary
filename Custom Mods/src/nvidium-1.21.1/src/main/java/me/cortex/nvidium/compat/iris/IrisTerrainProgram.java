package me.cortex.nvidium.compat.iris;

import me.cortex.nvidium.gl.shader.Shader;
import net.irisshaders.iris.gl.program.ProgramImages;
import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.gl.program.ProgramUniforms;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;

/**
 * One linked mesh-shader terrain program for a single Iris pass (SOLID / CUTOUT / TRANSLUCENT),
 * paired with its reusable Iris uniform/sampler/image holders and the manually-tracked matrix
 * uniform locations. Direct analogue of {@code IrisLodRenderProgram} but the underlying GL program
 * is Nvidium's task+mesh+patched-fragment program.
 *
 * <p>The Iris holder objects are built once and reused every draw; they store only locations and
 * callbacks, no per-draw state ({@code IrisLodRenderProgram} does the same).
 */
public final class IrisTerrainProgram {
    private final int programId;
    private final Shader shader; // kept so we can free the GL program
    private final CustomUniforms customUniforms;

    private ProgramUniforms uniforms;
    private ProgramSamplers samplers;
    private ProgramImages images;

    // Matrix uniform locations, looked up after link (mirror IrisLodRenderProgram). -1 if absent.
    private int uModelView = -1;
    private int uModelViewInverse = -1;
    private int uProjection = -1;
    private int uProjectionInverse = -1;
    private int uNormalMatrix = -1;

    IrisTerrainProgram(int programId, Shader shader, CustomUniforms customUniforms) {
        this.programId = programId;
        this.shader = shader;
        this.customUniforms = customUniforms;
    }

    /** Complete construction once the Iris holders have been built by the bridge. */
    void finishBuild(ProgramUniforms uniforms, ProgramSamplers samplers, ProgramImages images) {
        this.uniforms = uniforms;
        this.samplers = samplers;
        this.images = images;
        this.uModelView = GL32.glGetUniformLocation(programId, "iris_ModelViewMatrix");
        this.uModelViewInverse = GL32.glGetUniformLocation(programId, "iris_ModelViewMatrixInverse");
        this.uProjection = GL32.glGetUniformLocation(programId, "iris_ProjectionMatrix");
        this.uProjectionInverse = GL32.glGetUniformLocation(programId, "iris_ProjectionMatrixInverse");
        this.uNormalMatrix = GL32.glGetUniformLocation(programId, "iris_NormalMatrix");
    }

    public int getProgramId() {
        return programId;
    }

    /** glUseProgram(this). The caller (binder) owns FBO bind and texture-unit setup. */
    public void bind() {
        GL32.glUseProgram(programId);
    }

    /**
     * Push all dynamic uniforms/samplers/images for this draw. Must be called after {@link #bind()}
     * and after the matrix uniforms are set. Order mirrors {@code IrisLodRenderProgram.fillUniformData}.
     */
    public void updateUniformsAndSamplers() {
        samplers.update();
        uniforms.update();
        customUniforms.push(this);
        images.update();
    }

    /** Set the model-view / projection / normal matrix uniforms by location (no-op if absent). */
    public void setMatrices(Matrix4fc modelView, Matrix4fc projection) {
        setMat4(uModelView, modelView);
        if (uModelViewInverse != -1) {
            setMat4(uModelViewInverse, new Matrix4f(modelView).invert());
        }
        setMat4(uProjection, projection);
        if (uProjectionInverse != -1) {
            setMat4(uProjectionInverse, new Matrix4f(projection).invert());
        }
        if (uNormalMatrix != -1) {
            Matrix3f normal = new Matrix4f(modelView).invert().transpose3x3(new Matrix3f());
            setMat3(uNormalMatrix, normal);
        }
    }

    private static void setMat4(int location, Matrix4fc matrix) {
        if (location == -1 || matrix == null) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buffer = stack.callocFloat(16);
            matrix.get(buffer);
            GL32.glUniformMatrix4fv(location, false, buffer);
        }
    }

    private static void setMat3(int location, Matrix3f matrix) {
        if (location == -1) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buffer = stack.callocFloat(9);
            matrix.get(buffer);
            GL32.glUniformMatrix3fv(location, false, buffer);
        }
    }

    void free() {
        shader.delete();
    }
}
