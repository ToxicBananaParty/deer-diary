package me.cortex.nvidium.compat.iris;

import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.gl.shader.Shader;
import me.cortex.nvidium.gl.shader.ShaderType;
import me.cortex.nvidium.sodiumCompat.ShaderLoader;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.blending.AlphaTests;
import net.irisshaders.iris.gl.program.ProgramImages;
import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.gl.program.ProgramUniforms;
import net.irisshaders.iris.gl.state.FogMode;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.samplers.IrisSamplers;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ProgramFallbackResolver;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.uniforms.CelestialUniforms;
import net.irisshaders.iris.uniforms.CommonUniforms;
import net.irisshaders.iris.uniforms.MatrixUniforms;
import net.irisshaders.iris.uniforms.builtin.BuiltinReplacementUniforms;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;

import static me.cortex.nvidium.gl.shader.ShaderType.FRAGMENT;
import static me.cortex.nvidium.gl.shader.ShaderType.MESH;
import static me.cortex.nvidium.gl.shader.ShaderType.TASK;

/**
 * Builds and caches the mesh-shader terrain programs that pair Nvidium's task + mesh stages with
 * the active shaderpack's Iris-patched terrain FRAGMENT shader, for the SOLID / CUTOUT /
 * TRANSLUCENT passes. Structurally mirrors Iris's {@code IrisLodRenderProgram} (the DH LOD
 * integration) but uses Nvidium's NV_mesh_shader pipeline in place of a conventional vertex stage.
 *
 * <h2>Fallback floor</h2>
 * Any failure to resolve, patch, compile, or link a pass marks the WHOLE pipeline unsupported
 * (cached as {@link #FAILED}) and returns {@code null}, so {@code NvidiumIrisCompat.supportsActivePack()}
 * yields and Nvidium falls back to Sodium+Iris. This class never throws to its callers.
 *
 * <h2>Lifecycle</h2>
 * Results are cached per {@link IrisRenderingPipeline} instance in a {@link WeakHashMap}; when Iris
 * rebuilds its pipeline (shaderpack reload / resolution change) a fresh instance misses the cache
 * and is rebuilt. The GL programs leak until GC frees the map entry; acceptable for this
 * research-grade compile-gate (a destroy hook is a later task, see findings note 5).
 */
public final class IrisProgramBridge {
    private IrisProgramBridge() {}

    /** Sentinel cached when a pipeline's program build failed, so we don't retry every frame. */
    private static final BuiltPrograms FAILED = new BuiltPrograms(Collections.emptyMap());

    private static final Map<IrisRenderingPipeline, BuiltPrograms> CACHE = new WeakHashMap<>();

    /** The three terrain passes this task covers. Shadow is deferred (findings: later task). */
    private static final TerrainRenderPass[] PASSES = {
            DefaultTerrainRenderPasses.SOLID,
            DefaultTerrainRenderPasses.CUTOUT,
            DefaultTerrainRenderPasses.TRANSLUCENT,
    };

    /**
     * Return the built programs for this pipeline, building+caching on first use. Returns
     * {@code null} if the pack is unsupported (any pass failed to build). Never throws.
     */
    public static synchronized BuiltPrograms getOrBuild(IrisRenderingPipeline pipeline) {
        BuiltPrograms cached = CACHE.get(pipeline);
        if (cached != null) {
            return cached == FAILED ? null : cached;
        }
        BuiltPrograms built;
        try {
            built = build(pipeline);
        } catch (Throwable t) {
            Nvidium.LOGGER.warn("Nvidium-Iris: terrain program build failed; pack unsupported, yielding to Sodium+Iris", t);
            built = null;
        }
        CACHE.put(pipeline, built == null ? FAILED : built);
        return built;
    }

    private static BuiltPrograms build(IrisRenderingPipeline pipeline) {
        ProgramFallbackResolver resolver =
                ((NvidiumIrisRenderingPipelineAccessor) pipeline).nvidium$getResolver();
        CustomUniforms customUniforms = pipeline.getCustomUniforms();

        Map<TerrainRenderPass, IrisTerrainProgram> programs = new EnumMapPasses();

        for (TerrainRenderPass pass : PASSES) {
            ProgramId programId = programIdFor(pass);
            ProgramSource source = resolver.resolveNullable(programId);
            if (source == null) {
                // The pack has no terrain program for this pass at all -> can't integrate.
                Nvidium.LOGGER.warn("Nvidium-Iris: no shaderpack program for {} ({}); pack unsupported",
                        pass, programId);
                return null;
            }
            IrisTerrainProgram program = buildPass(pipeline, customUniforms, pass, source);
            if (program == null) {
                return null; // any pass failing fails the whole pack
            }
            programs.put(pass, program);
        }

        Nvidium.LOGGER.info("Nvidium-Iris: built terrain programs for SOLID/CUTOUT/TRANSLUCENT");
        return new BuiltPrograms(programs);
    }

    private static IrisTerrainProgram buildPass(IrisRenderingPipeline pipeline,
                                                CustomUniforms customUniforms,
                                                TerrainRenderPass pass,
                                                ProgramSource source) {
        boolean translucent = pass == DefaultTerrainRenderPasses.TRANSLUCENT;
        boolean cutout = pass == DefaultTerrainRenderPasses.CUTOUT;

        // 1. Alpha test: mirror SodiumPrograms.getAlphaTest (cutout defaults to 1/10 alpha).
        AlphaTest alpha = source.getDirectives().getAlphaTestOverride()
                .orElse(cutout ? AlphaTests.ONE_TENTH_ALPHA : AlphaTest.ALWAYS);

        // 2. Patch the FRAGMENT through Iris's Sodium transformer (vertex/geometry/tess = null;
        //    Nvidium supplies the mesh stage). Arg order matches SodiumPrograms.transformShaders.
        String rawFragment = source.getFragmentSource().orElse(null);
        if (rawFragment == null) {
            Nvidium.LOGGER.warn("Nvidium-Iris: {} program has no fragment source; pack unsupported", pass);
            return null;
        }
        Map<PatchShaderType, String> transformed = TransformPatcher.patchSodium(
                source.getName(),
                null,   // vertex
                null,   // geometry
                null,   // tessControl
                null,   // tessEval
                rawFragment,
                alpha,
                pipeline.getTextureMap());
        String patchedFragment = transformed.get(PatchShaderType.FRAGMENT);
        if (patchedFragment == null) {
            Nvidium.LOGGER.warn("Nvidium-Iris: fragment patch produced null for {}; pack unsupported", pass);
            return null;
        }

        // 3. Varying matching: parse the PATCHED FRAGMENT's `in` declarations and generate the
        //    matching mesh-shader output block + writer function (findings Q4). The fragment is the
        //    authoritative consumer contract -- the mesh `out` interface must cover exactly its
        //    `in` set (every name, with identical type + interpolation qualifier), or the program
        //    fails to link with "<name> not declared as input from previous stage". Driving off the
        //    vertex `out` set is wrong: it can miss Iris-injected inputs (e.g. iris_FogFragCoord)
        //    and multi-variable declarations the vertex stage never wrote.
        List<IrisVaryingMapper.Varying> varyings =
                IrisVaryingMapper.parseFragmentIn(patchedFragment);
        String varyingGlsl = IrisVaryingMapper.generateMeshVaryingGlsl(varyings);

        // 4. Load Nvidium's task + mesh GLSL in IRIS_PASS mode and splice in the generated block.
        String taskSrc;
        String meshSrc;
        if (translucent) {
            taskSrc = ShaderLoader.parse(rl("terrain/translucent/task.glsl"), b -> b.add("IRIS_PASS"));
            meshSrc = ShaderLoader.parse(rl("terrain/translucent/mesh.glsl"), b -> b.add("IRIS_PASS"));
        } else {
            taskSrc = ShaderLoader.parse(rl("terrain/task.glsl"), b -> b.add("IRIS_PASS"));
            meshSrc = ShaderLoader.parse(rl("terrain/mesh.glsl"), b -> b.add("IRIS_PASS"));
        }
        meshSrc = spliceVaryings(meshSrc, varyingGlsl);

        // 5. Link the mesh-shader program (TASK + MESH + patched or debug FRAGMENT) via Nvidium's builder.
        //
        // Debug-fragment isolation mode (-Dnvidium.iris.debug=<mode>):
        //   When set, replaces the shaderpack fragment with a minimal GLSL shader that declares the
        //   SAME 'in' interface as the mesh 'out' (so it links) and writes a solid diagnostic color
        //   to draw-buffer 0.  Complementary Unbound is a DEFERRED pack, so the debug color lands in
        //   a gbuffer attachment (colortex0 / albedo) and IS re-processed by composite passes — but
        //   gross geometry / depth shredding will still be visible, and varying visualization remains
        //   indicative.  To see the raw output, disable post-processing or use a forward-only pack.
        //   Supported modes: flat, normal, pos, uv, lm.  Unknown mode → magenta (1,0,1,1).
        String debugMode = System.getProperty("nvidium.iris.debug");
        String activeFragmentSrc = patchedFragment;
        if (debugMode != null && !debugMode.isEmpty()) {
            Nvidium.LOGGER.info("Nvidium-Iris: DEBUG fragment mode '{}' active", debugMode);
            activeFragmentSrc = buildDebugFragment(varyings, debugMode);
        }

        Shader shader;
        try {
            shader = Shader.make()
                    .addSource(TASK, taskSrc)
                    .addSource(MESH, meshSrc)
                    .addSource(FRAGMENT, activeFragmentSrc)
                    .compile();
        } catch (RuntimeException e) {
            // Dump the full generated source of each stage so the next gate can inspect exactly
            // what we fed the GLSL compiler. Best-effort: IO failures here must not mask the
            // original compile failure or change control flow.
            dumpGeneratedSources(pass, taskSrc, meshSrc, activeFragmentSrc, false);
            Nvidium.LOGGER.warn("Nvidium-Iris: compile/link failed for {} terrain program; pack unsupported", pass, e);
            return null;
        }

        // Mesh output footprint diagnostic: query NV mesh-shader limits and compare against our
        // per-meshlet output budget. This runs once per pass at program-build time; it is INFO-level,
        // cheap (a handful of glGetInteger calls), and needs no property gate.
        logMeshOutputFootprint(pass, varyings);

        // Success-dump (-Dnvidium.iris.dump=true):
        //   Writes the exact GLSL fed to the compiler for each stage to run/nvidium-iris-dump/ so
        //   an offline agent can inspect them without re-launching.  Off by default (no property set).
        if (Boolean.getBoolean("nvidium.iris.dump")) {
            dumpGeneratedSources(pass, taskSrc, meshSrc, activeFragmentSrc, true);
        }

        int programId = shaderHandle(shader);

        // 6. Build ProgramUniforms / ProgramSamplers / ProgramImages, mirroring IrisLodRenderProgram.
        Supplier<com.google.common.collect.ImmutableSet<Integer>> flipState =
                translucent ? pipeline::getFlippedAfterTranslucent : pipeline::getFlippedAfterPrepare;

        ProgramUniforms.Builder uniformBuilder = ProgramUniforms.builder("nvidium_iris_" + pass, programId);
        ProgramSamplers.Builder samplerBuilder =
                ProgramSamplers.builder(programId, IrisSamplers.SODIUM_RESERVED_TEXTURE_UNITS);
        ProgramImages.Builder imageBuilder = ProgramImages.builder(programId);

        CommonUniforms.addDynamicUniforms(uniformBuilder, FogMode.PER_VERTEX);
        // Register the shaderpack-facing gbuffer matrix + celestial uniforms the mesh stage reads
        // (gbufferModelView, sunPosition; and, for the fragment, gbufferProjection / dhProjection /
        // their inverses). IrisLodRenderProgram doesn't need these because the DH *vertex* shader is
        // patched to the iris_* aliases that addDynamicUniforms covers; our mesh shader references the
        // pack names directly, so without this they would stay at the GL default (a zero matrix) and
        // the basis vectors / view-space normal would collapse -- the near-terrain darkness we are
        // fixing. MatrixUniforms / CelestialUniforms take a UniformHolder, which the builder is.
        PackDirectives packDirectives =
                ((NvidiumIrisRenderingPipelineAccessor) pipeline).nvidium$getPackDirectives();
        MatrixUniforms.addMatrixUniforms(uniformBuilder, packDirectives);
        new CelestialUniforms(pipeline.getSunPathRotation()).addCelestialUniforms(uniformBuilder);
        customUniforms.assignTo(uniformBuilder);
        BuiltinReplacementUniforms.addBuiltinReplacementUniforms(uniformBuilder);

        // 7-arg addGbufferOrShadowSamplers: (samplerHolder, imageHolder, flipState, isShadowPass,
        //    hasTexture, hasLightmap, hasOverlay). Terrain: not shadow, has block atlas + lightmap,
        //    no overlay. Verified against the Iris 1.8.12 bytecode.
        pipeline.addGbufferOrShadowSamplers(samplerBuilder, imageBuilder, flipState,
                false, true, true, false);

        // The program object acts as the opaque "pass" key for custom uniforms (the API takes an
        // Object; no WrappedUniformHolder interface exists in this Iris version).
        IrisTerrainProgram program = new IrisTerrainProgram(programId, shader, customUniforms);
        customUniforms.mapholderToPass(uniformBuilder, program);

        program.finishBuild(
                uniformBuilder.buildUniforms(),
                samplerBuilder.build(),
                imageBuilder.build());

        return program;
    }

    /**
     * Query the NV mesh-shader hardware limits via GL and log how our per-meshlet output footprint
     * compares to {@code GL_MAX_MESH_TOTAL_MEMORY_SIZE_NV}.
     *
     * <p>The mesh shader is declared {@code layout(triangles, max_vertices=64, max_primitives=32) out;}.
     * Each per-vertex output slot is vec4-aligned on NV hardware (16 bytes), so the padded footprint is
     * {@code (numVaryings + 1) * 16 * 64} (the +1 accounts for gl_Position). The tight estimate uses the exact component
     * sum. If either estimate meets or exceeds the hardware limit, a WARN is emitted to flag the likely
     * cause of meshlet geometry corruption.
     *
     * <p>Fully defensive: any GL error or unexpected exception is caught and logged as a warning, never
     * propagated.
     */
    private static void logMeshOutputFootprint(TerrainRenderPass pass, java.util.List<IrisVaryingMapper.Varying> varyings) {
        try {
            // Query the three NV mesh-shader limits. Use NVMeshShader constants where available;
            // raw hex literals are spelled out in comments for auditability.
            int maxTotalMem     = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.NVMeshShader.GL_MAX_MESH_TOTAL_MEMORY_SIZE_NV);     // 0x9536
            int maxOutputVerts  = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.NVMeshShader.GL_MAX_MESH_OUTPUT_VERTICES_NV);        // 0x9539
            int maxOutputPrims  = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.NVMeshShader.GL_MAX_MESH_OUTPUT_PRIMITIVES_NV);      // 0x953A

            // Compute component counts.
            int numVaryings = varyings.size();
            int sumComponents = 0;
            for (IrisVaryingMapper.Varying v : varyings) {
                sumComponents += IrisVaryingMapper.componentCount(v.type());
            }

            final int MAX_VERTICES = 64;

            // Tight estimate: each component is 4 bytes; gl_Position contributes 4 components.
            int tightBytesPerVertex = (sumComponents + 4 /*gl_Position components*/) * 4;
            int tightTotal = tightBytesPerVertex * MAX_VERTICES;

            // Padded (vec4-aligned) estimate: each varying occupies one 16-byte slot; gl_Position
            // adds one more slot. This is the pessimistic / hardware-budget estimate.
            int paddedBytesPerVertex = (numVaryings + 1 /*gl_Position slot*/) * 16;
            int paddedTotal = paddedBytesPerVertex * MAX_VERTICES;

            String passLabel = pass.toString();
            Nvidium.LOGGER.info(
                    "Nvidium-Iris[{}]: mesh output = {} varyings, ~{}B/vert tight / ~{}B/vert padded;" +
                    " x{} verts = ~{}B tight / ~{}B padded;" +
                    " NV limits: totalMem={}, maxVerts={}, maxPrims={}",
                    passLabel, numVaryings,
                    tightBytesPerVertex, paddedBytesPerVertex,
                    MAX_VERTICES, tightTotal, paddedTotal,
                    maxTotalMem, maxOutputVerts, maxOutputPrims);

            // Warn if either footprint estimate is at or over the hardware limit.
            if (paddedTotal >= maxTotalMem || tightTotal >= maxTotalMem) {
                Nvidium.LOGGER.warn(
                        "Nvidium-Iris[{}]: WARNING mesh output footprint (~{}B padded / ~{}B tight)" +
                        " is at/over NV total-memory limit ({}B) -- likely cause of meshlet corruption",
                        passLabel, paddedTotal, tightTotal, maxTotalMem);
            }
        } catch (Throwable t) {
            Nvidium.LOGGER.warn("Nvidium-Iris: failed to query NV mesh-shader limits for footprint diagnostic", t);
        }
    }

    /**
     * Write the full generated source of each stage to
     * {@code <gamedir>/nvidium-iris-dump/<pass>-<stage>.glsl} so the next gate can inspect exactly
     * what was generated. Anchored at {@link FMLPaths#GAMEDIR} (the {@code run/} directory) rather
     * than a CWD-relative {@code run/...} path, which previously doubled to {@code run/run/...}
     * because the process CWD is already {@code run/}. Fully defensive: any IO error is swallowed
     * and never changes control flow (including a missing/unready FMLPaths).
     *
     * @param isSuccess {@code true} when called on a successful compile (gated by
     *                  {@code -Dnvidium.iris.dump=true}); {@code false} on failure (always dumped).
     *                  Affects only the log level (info vs warn).
     */
    private static void dumpGeneratedSources(TerrainRenderPass pass, String taskSrc,
                                             String meshSrc, String fragmentSrc, boolean isSuccess) {
        try {
            java.nio.file.Path dir = FMLPaths.GAMEDIR.get().resolve("nvidium-iris-dump");
            java.nio.file.Files.createDirectories(dir);
            String label = sanitizePassLabel(pass);
            writeDumpFile(dir.resolve(label + "-task.glsl"), taskSrc);
            writeDumpFile(dir.resolve(label + "-mesh.glsl"), meshSrc);
            writeDumpFile(dir.resolve(label + "-fragment.glsl"), fragmentSrc);
            if (isSuccess) {
                Nvidium.LOGGER.info("Nvidium-Iris: dumped generated shader source (success) for {} to {}",
                        pass, dir.toAbsolutePath());
            } else {
                Nvidium.LOGGER.warn("Nvidium-Iris: dumped generated shader source (failure) for {} to {}",
                        pass, dir.toAbsolutePath());
            }
        } catch (Throwable ignored) {
            // Best-effort only; never let a dump failure mask the real compile error or the
            // successful program being returned.
        }
    }

    private static void writeDumpFile(java.nio.file.Path path, String content) {
        try {
            java.nio.file.Files.writeString(path, content == null ? "" : content);
        } catch (Throwable ignored) {
            // best-effort per file
        }
    }

    /**
     * Build a minimal debug GLSL fragment that links against the mesh stage's {@code out} interface
     * (same {@code in} declarations, same qualifier + type + name) and writes a solid diagnostic
     * color to draw-buffer 0 of the bound gbuffer FBO.  Used by the {@code -Dnvidium.iris.debug}
     * system property.
     *
     * <p>Supported modes and what they reveal:
     * <ul>
     *   <li>{@code flat}   – {@code vec4(1.0)} solid white.  If terrain is clean white with no
     *       geometry shredding, geometry + depth are fine and the bug is in pack shading.  If still
     *       shredded, the problem is upstream of shading (depth/FBO/geometry).</li>
     *   <li>{@code normal} – {@code vec4(normal * 0.5 + 0.5, 1.0)}.  Visualises the view-space
     *       normal varying (mapped by {@link IrisVaryingMapper}).</li>
     *   <li>{@code pos}    – {@code vec4(fract(vertexPos), 1.0)}.  Position varying,
     *       fractional part for colour variation.  Falls back to {@code worldPos} /
     *       {@code playerPos} / {@code position} if the primary name is absent.</li>
     *   <li>{@code uv}     – {@code vec4(texCoord, 0.0, 1.0)}.</li>
     *   <li>{@code lm}     – {@code vec4(lmCoord, 0.0, 1.0)}.  Lightmap UV.</li>
     *   <li>Unknown        – {@code vec4(1.0, 0.0, 1.0, 1.0)} magenta.</li>
     * </ul>
     *
     * <p><b>Deferred-pipeline caveat:</b> Complementary Unbound is a DEFERRED shaderpack.  The
     * debug color is written to a gbuffer attachment (likely colortex0 / albedo) and WILL be
     * re-processed by composite passes (tonemapping, bloom, etc.).  Gross geometry / depth shredding
     * remains visible through compositing; varying visualisation is still indicative for direction.
     * To see the raw fragment output, use a forward-only pack or disable post-processing.
     *
     * <p>Guards: each mode checks via {@link IrisVaryingMapper#hasVarying} whether the required
     * varying is actually present in the interface before referencing it.  If absent, falls back to
     * solid white so the program still links and compiles without errors.  The method is fully
     * defensive and never throws.
     */
    private static String buildDebugFragment(List<IrisVaryingMapper.Varying> varyings, String mode) {
        // Declare 'in' varyings matching the mesh 'out' interface exactly.
        String inDecls = IrisVaryingMapper.generateDebugFragmentInDecls(varyings);

        // Determine the color expression for the requested mode.
        String colorExpr;
        switch (mode) {
            case "flat": {
                colorExpr = "vec4(1.0)";
                break;
            }
            case "normal": {
                IrisVaryingMapper.Varying v = IrisVaryingMapper.findVarying(varyings, "normal");
                if (v == null) v = IrisVaryingMapper.findVarying(varyings, "vnormal");
                if (v == null) v = IrisVaryingMapper.findVarying(varyings, "viewnormal");
                if (v == null) v = IrisVaryingMapper.findVarying(varyings, "worldnormal");
                if (v != null) {
                    // Normal is always vec3 or vec4; both support component-wise multiply + add.
                    // Extract the xyz component if vec4 to avoid a vec4*0.5+0.5,1.0 ambiguity.
                    String ref = v.type().equals("vec4") ? v.name() + ".xyz" : v.name();
                    colorExpr = "vec4(" + ref + " * 0.5 + 0.5, 1.0)";
                } else {
                    colorExpr = "vec4(1.0)"; // fallback: normal varying absent
                }
                break;
            }
            case "pos": {
                // Try several position varying name conventions in priority order.
                IrisVaryingMapper.Varying v = IrisVaryingMapper.findVarying(varyings, "vertexpos");
                if (v == null) v = IrisVaryingMapper.findVarying(varyings, "worldpos");
                if (v == null) v = IrisVaryingMapper.findVarying(varyings, "playerpos");
                if (v == null) v = IrisVaryingMapper.findVarying(varyings, "position");
                if (v == null) v = IrisVaryingMapper.findVarying(varyings, "vposition");
                if (v == null) v = IrisVaryingMapper.findVarying(varyings, "scenepos");
                if (v != null) {
                    // Use .xyz so the result is always vec3 regardless of whether declared as vec4.
                    String ref = v.type().equals("vec4") ? v.name() + ".xyz" : v.name();
                    colorExpr = "vec4(fract(" + ref + "), 1.0)";
                } else {
                    colorExpr = "vec4(1.0)"; // fallback: no position varying found
                }
                break;
            }
            case "uv": {
                IrisVaryingMapper.Varying v = IrisVaryingMapper.findVarying(varyings, "texcoord");
                if (v == null) v = IrisVaryingMapper.findVarying(varyings, "uv");
                if (v == null) v = IrisVaryingMapper.findVarying(varyings, "vtexcoord");
                if (v != null) {
                    String ref = v.type().equals("vec4") ? v.name() + ".xy" : v.name();
                    colorExpr = "vec4(" + ref + ", 0.0, 1.0)";
                } else {
                    colorExpr = "vec4(1.0)";
                }
                break;
            }
            case "lm": {
                IrisVaryingMapper.Varying v = IrisVaryingMapper.findVarying(varyings, "lmcoord");
                if (v == null) v = IrisVaryingMapper.findVarying(varyings, "lightmapcoord");
                if (v == null) v = IrisVaryingMapper.findVarying(varyings, "light");
                if (v != null) {
                    String ref = v.type().equals("vec4") ? v.name() + ".xy" : v.name();
                    colorExpr = "vec4(" + ref + ", 0.0, 1.0)";
                } else {
                    colorExpr = "vec4(1.0)";
                }
                break;
            }
            default: {
                colorExpr = "vec4(1.0, 0.0, 1.0, 1.0)"; // magenta — unknown mode
                break;
            }
        }

        // Assemble the complete fragment shader.
        // No layout qualifier on the out — binds to draw-buffer 0 by default (index 0 = first
        // color attachment of the bound gbuffer FBO, which is what the pack's terrain fragment
        // also writes to).
        return "#version 460\n" +
                "\n" +
                "// Nvidium-Iris diagnostic fragment (mode: " + mode + ").\n" +
                "// Replaces the shaderpack fragment when -Dnvidium.iris.debug is set.\n" +
                "// DEFERRED-PIPELINE CAVEAT: this color is written to a gbuffer attachment\n" +
                "// (colortex0/albedo) and WILL be re-processed by Complementary's composite\n" +
                "// passes (tonemapping, bloom, etc.). Gross geometry/depth shredding remains\n" +
                "// visible through compositing. For raw output use a forward-only pack.\n" +
                "\n" +
                inDecls +
                "\n" +
                "out vec4 nvidium_debugColor;\n" +
                "\n" +
                "void main() {\n" +
                "    nvidium_debugColor = " + colorExpr + ";\n" +
                "}\n";
    }

    /** Turn a pass into a filename-safe label (the pass toString may contain odd characters). */
    private static String sanitizePassLabel(TerrainRenderPass pass) {
        String raw;
        if (pass == DefaultTerrainRenderPasses.SOLID) raw = "solid";
        else if (pass == DefaultTerrainRenderPasses.CUTOUT) raw = "cutout";
        else if (pass == DefaultTerrainRenderPasses.TRANSLUCENT) raw = "translucent";
        else raw = String.valueOf(pass);
        return raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** Replace the //__NVIDIUM_IRIS_VARYINGS__ marker (added to the mesh asset) with generated GLSL. */
    private static String spliceVaryings(String meshSrc, String varyingGlsl) {
        final String marker = "//__NVIDIUM_IRIS_VARYINGS__";
        if (!meshSrc.contains(marker)) {
            throw new IllegalStateException("Nvidium-Iris: mesh shader is missing the IRIS varying marker");
        }
        return meshSrc.replace(marker, varyingGlsl);
    }

    private static ProgramId programIdFor(TerrainRenderPass pass) {
        if (pass == DefaultTerrainRenderPasses.SOLID) return ProgramId.TerrainSolid;
        if (pass == DefaultTerrainRenderPasses.CUTOUT) return ProgramId.TerrainCutout;
        if (pass == DefaultTerrainRenderPasses.TRANSLUCENT) return ProgramId.Water;
        throw new IllegalArgumentException("Unknown terrain pass: " + pass);
    }

    private static ResourceLocation rl(String path) {
        return ResourceLocation.fromNamespaceAndPath("nvidium", path);
    }

    /** Read the raw GL program handle out of a Nvidium {@link Shader}. */
    private static int shaderHandle(Shader shader) {
        return shader.getId();
    }

    /** Immutable bundle of the built per-pass programs for one pipeline. */
    public static final class BuiltPrograms {
        private final Map<TerrainRenderPass, IrisTerrainProgram> programs;

        BuiltPrograms(Map<TerrainRenderPass, IrisTerrainProgram> programs) {
            this.programs = programs;
        }

        /** May be null for passes not built (none, currently — all three are required). */
        public IrisTerrainProgram get(TerrainRenderPass pass) {
            return programs.get(pass);
        }

        void freeAll() {
            for (IrisTerrainProgram p : programs.values()) {
                p.free();
            }
        }
    }

    /**
     * A plain HashMap-backed pass map (TerrainRenderPass instances aren't enum constants, so we
     * can't use EnumMap). Named for readability at the call site.
     */
    private static final class EnumMapPasses extends java.util.HashMap<TerrainRenderPass, IrisTerrainProgram> {}
}
