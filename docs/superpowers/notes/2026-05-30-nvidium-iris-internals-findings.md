# Nvidium-Iris Internals Findings (Spike 2026-05-30)

Research spike: read-only. Goal is to map the exact Iris API surface Nvidium must reach to
render its mesh-shader terrain through the active Iris shaderpack's terrain gbuffer programs.

References used:
- `Custom Mods/src/nvidium-1.21.1/claude-reference/Iris-1.21.1/`
- `Custom Mods/src/nvidium-1.21.1/claude-reference/Colorwheel-1.21.1-dev/`
- `Custom Mods/src/nvidium-1.21.1/src/` (Nvidium itself)

---

## Q1 — Reaching the active IrisRenderingPipeline

### Entry point

```java
// net.irisshaders.iris.Iris (public API)
WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
if (pipeline instanceof IrisRenderingPipeline irisPipeline) { ... }
```

This is the exact pattern used in:
- `MixinShaderChunkRenderer.redirectIrisProgram` (Iris itself)
- `ClrwlPipelineCompiler.compile` (Colorwheel)

`IrisRenderingPipeline` is a concrete class in `net.irisshaders.iris.pipeline`. Its fields are
`private final`; the only way in from outside is mixin accessors.

`Nvidium` already has `IrisCheck.isShaderPackActive()` via `IrisApi`, but getting the actual
pipeline object requires the cast above.

### Fields Nvidium must access via mixin accessors

Colorwheel's `IrisRenderingPipelineMixin` (`@Mixin(IrisRenderingPipeline.class)`) uses
`@Shadow @Final` to expose:

| Field in `IrisRenderingPipeline` | Type | Purpose |
|---|---|---|
| `renderTargets` | `RenderTargets` | call `createGbufferFramebuffer(flipped, drawBuffers)` |
| `flippedAfterPrepare` | `ImmutableSet<Integer>` | flip set for solid/cutout passes |
| `flippedAfterTranslucent` | `ImmutableSet<Integer>` | flip set for translucent pass |
| `shadowRenderer` | `ShadowRenderer` | get `ShadowRenderTargets` for shadow pass FBOs |

Additionally, Colorwheel adds `@Inject` into `beginLevelRendering()` to detect window-resize
events (framebuffer invalidation). Nvidium should do the same.

Colorwheel also uses `IrisRenderingPipeline.getSodiumPrograms()` and
`IrisRenderingPipeline.getCustomUniforms()` — **both are already public methods** on
`IrisRenderingPipeline` (lines 1269 and 1313 of the Iris source). No accessor needed for those.

Similarly, `getTextureMap()` is declared public on `WorldRenderingPipeline` interface and
implemented on `IrisRenderingPipeline` at line 655. No accessor needed.

**Accessor interface Nvidium must create (`NvidiumIrisRenderingPipelineAccessor`):**

```java
@Mixin(IrisRenderingPipeline.class)
public abstract class NvidiumIrisRenderingPipelineMixin implements NvidiumIrisRenderingPipelineAccessor {
    @Shadow @Final private RenderTargets renderTargets;
    @Shadow @Final private ImmutableSet<Integer> flippedAfterPrepare;
    @Shadow @Final private ImmutableSet<Integer> flippedAfterTranslucent;
    @Shadow @Final private ShadowRenderer shadowRenderer;  // nullable

    public RenderTargets nvidium$getRenderTargets() { return renderTargets; }
    public ImmutableSet<Integer> nvidium$getFlippedAfterPrepare() { return flippedAfterPrepare; }
    public ImmutableSet<Integer> nvidium$getFlippedAfterTranslucent() { return flippedAfterTranslucent; }
    public ShadowRenderTargets nvidium$getShadowRenderTargets() {
        return ((NvidiumShadowRendererAccessor) shadowRenderer).nvidium$getTargets();
    }
    @Inject(method = "beginLevelRendering", at = @At("RETURN"))
    private void nvidium$onBeginLevel(CallbackInfo ci, @Local boolean changed) {
        if (changed) nvidium$setFramebufferDirty();
    }
    // + nvidium$setFramebufferDirty / nvidium$consumeFramebufferDirty
}
```

**`NvidiumShadowRendererAccessor`** (`@Mixin(ShadowRenderer.class)`): expose `getTargets()` to
reach `ShadowRenderTargets` — same as Colorwheel's `ShadowRendererAccessor.getTargets()`.

---

## Q2 — Getting the GlFramebuffer for a terrain pass

### The existing SodiumPrograms path

`IrisRenderingPipeline` builds and owns a `SodiumPrograms` object (field `sodiumPrograms`, line
177) that is returned by the **public** method `getSodiumPrograms()`.

`SodiumPrograms` has:
```java
public GlFramebuffer getFramebuffer(TerrainRenderPass pass)   // line 188
public GlProgram<ChunkShaderInterface> getProgram(TerrainRenderPass pass)  // line 183
```

Where `TerrainRenderPass` is Sodium's `net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass`.
The three live instances are `DefaultTerrainRenderPasses.{SOLID, CUTOUT, TRANSLUCENT}`.

`SodiumPrograms.mapTerrainRenderPass` does the shadow-pass redirect automatically:
- If `ShadowRenderingState.areShadowsCurrentlyBeingRendered()` → returns shadow FBO/program.
- Otherwise → returns the gbuffer FBO/program for the pass.

**This means Nvidium can simply call:**
```java
IrisRenderingPipeline irisPipeline = ...;
GlFramebuffer fbo = irisPipeline.getSodiumPrograms().getFramebuffer(DefaultTerrainRenderPasses.SOLID);
int glFboId = fbo.handle;  // GlFramebuffer.handle is the raw GL FBO id
fbo.bind();                 // binds and sets draw buffers
```

The framebuffer is created inside `SodiumPrograms` via:
```java
renderTargets.createGbufferFramebuffer(flipState.get(),
    source == null ? new int[]{0} : source.getDirectives().getDrawBuffers())
```

So the draw-buffer set comes from the shaderpack's `gbuffers_terrain(_solid/_cutout/_water)`
program directives. **No additional accessor is needed for Q2 if Nvidium borrows the
Sodium-path FBOs** — `getSodiumPrograms()` is already public.

### Alternative: create new FBOs

If Nvidium needs **separate** FBOs (e.g. to avoid interfering with Sodium's own render loop),
it calls the `IrisRenderingPipelineAccessor` approach:

```java
ProgramSource src = ...;                          // from resolver
GlFramebuffer fbo = renderTargets.createGbufferFramebuffer(flippedAfterPrepare, src.getDirectives().getDrawBuffers());
```

But for a first implementation, reusing `getSodiumPrograms().getFramebuffer(pass)` is simpler
and correct.

---

## Q3 — Getting the shaderpack terrain fragment GLSL (post-patch)

### Recommendation: option (b) — raw ProgramSource + TransformPatcher.patchSodium

**Option (a): read from SodiumPrograms** is not viable. The per-pass GLSL is consumed at
construction time (compiled into `GlShader` objects, then the `GlShader.handle` is what
survives). The transformed source strings are NOT stored on `SodiumPrograms` or
`SodiumShader` after compilation. There is no public or shadow-accessible field holding the
patched source text.

**Option (b): ProgramSource + TransformPatcher.patchSodium**

`TransformPatcher.patchSodium(...)` is a **public static method** in
`net.irisshaders.iris.pipeline.transform.TransformPatcher` (line 322):

```java
public static Map<PatchShaderType, String> patchSodium(
    String name,
    String vertex,          // may be null
    String geometry,        // may be null
    String tessControl,     // may be null
    String tessEval,        // may be null
    String fragment,        // may be null
    AlphaTest alpha,
    Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap)
```

Returns a `Map<PatchShaderType, String>` where the keys are `PatchShaderType.{VERTEX, GEOMETRY,
TESS_CONTROL, TESS_EVAL, FRAGMENT}` and the values are the transformed GLSL strings (or null
if that stage was not provided).

**This is exactly what `SodiumPrograms.transformShaders` calls** (lines 80-93 of
`SodiumPrograms.java`), so the output is identical to what Iris itself uses.

### Getting ProgramSource

```java
IrisRenderingPipeline irisPipeline = ...;
// public methods, no accessor needed:
ShaderPack pack = irisPipeline.getSodiumPrograms();  // No, wrong
```

Wait — there is no direct public getter for `pack` or `programSet` on `IrisRenderingPipeline`.
Colorwheel gets the `ProgramSet` from `ShaderPack.getProgramSet(dimension)`, which requires
getting the `ShaderPack` first.

Looking at `ClrwlPipelineCompiler.compile`:
```java
WorldRenderingPipeline worldPipeline = Iris.getPipelineManager().getPipelineNullable();
if (worldPipeline instanceof IrisRenderingPipeline irisPipeline) {
    ProgramSet programSet = pack.getProgramSet(dimension);   // pack is held by ClrwlPipelineCompiler
    ...
    var irisSources = this.patchedSources.getSources(baseProgramId, ..., programSet, irisPipeline);
```

Colorwheel holds `ShaderPack pack` directly because it gets it passed in from Flywheel's
pipeline-created callback. Nvidium needs a different path.

`IrisRenderingPipeline` has a private `ShaderPack pack` field (line 172) and a private
`ProgramFallbackResolver resolver` field (line 129). The resolver is the clean way to get
`ProgramSource` for a given `ProgramId`. **No public accessor for `pack` or `resolver` exists.**

The simplest approach for Nvidium:

**Option B1** — expose `ProgramFallbackResolver` via accessor mixin, then call:
```java
ProgramSource source = resolver.resolveNullable(ProgramId.TerrainSolid);
```
`ProgramFallbackResolver.resolveNullable(ProgramId)` traverses the fallback chain and returns
the best available `ProgramSource`, or null if the pack has no terrain shader at all.

**Option B2** — expose `ShaderPack pack` via accessor, then:
```java
ProgramSet programSet = pack.getProgramSet(currentDimension);
ProgramSource source = new ProgramFallbackResolver(programSet).resolveNullable(ProgramId.TerrainSolid);
```

**Option B1 is simpler**. One accessor mixin, one field shadow:

```java
// NvidiumIrisRenderingPipelineMixin (extend the one from Q1)
@Shadow @Final private ProgramFallbackResolver resolver;
// accessor method:
public ProgramFallbackResolver nvidium$getResolver() { return resolver; }
```

Then in Nvidium's bridge:
```java
ProgramFallbackResolver resolver = ((NvidiumIrisRenderingPipelineAccessor) irisPipeline).nvidium$getResolver();
ProgramSource solidSource = resolver.resolveNullable(ProgramId.TerrainSolid);
ProgramSource cutoutSource = resolver.resolveNullable(ProgramId.TerrainCutout);
ProgramSource waterSource = resolver.resolveNullable(ProgramId.Water);  // translucent
```

Then patch:
```java
AlphaTest alpha = solidSource.getDirectives().getAlphaTestOverride()
    .orElse(AlphaTest.ALWAYS);
Map<PatchShaderType, String> transformed = TransformPatcher.patchSodium(
    solidSource.getName(),
    null,            // Nvidium provides its own vertex/task/mesh stages
    null, null, null,
    solidSource.getFragmentSource().orElse(null),
    alpha,
    irisPipeline.getTextureMap());
String fragmentGlsl = transformed.get(PatchShaderType.FRAGMENT);
```

Passing `null` for the vertex stage is fine — `patchSodium` skips null stages. The fragment
transform adds the Iris-required varying declarations, renames samplers, and handles the
`gl_FragData` → `iris_FragData`/layout-out rewrites.

**TransformPatcher.patchSodium is public static — directly callable from any mod. No Mixin needed.**

### What Colorwheel does differently

Colorwheel calls its own `ClrwlTransformPatcher.patchFragment(...)` rather than
`TransformPatcher.patchSodium(...)`. The reason: Colorwheel injects Flywheel-specific
attribute names (`flw_vertexPos` etc.) that don't exist in Nvidium's context. Nvidium should
call Iris's own `TransformPatcher.patchSodium` which does the Sodium-specific transforms
(the `SodiumCoreTransformer` + `SodiumTransformer`). Those transforms:

- Replace `gl_FragColor`/`gl_FragData` with layout-out `iris_FragData0`, `iris_FragData1`, ...
- Rename `tex`/`gtexture` to the correct Iris texture sampler name.
- Apply alpha test injection.
- Apply the custom texture map (sampler name substitutions for pack-defined custom textures).

---

## Q4 — Fragment's required inputs (the varyings/uniforms/samplers contract)

### Varyings the Iris-patched terrain fragment expects

After `TransformPatcher.patchSodium`, the fragment shader references varyings (inputs) by the
names that the Sodium terrain **vertex** shader outputs. The Sodium vertex attribute binding
points (from `SodiumPrograms.buildProgram`) are:

| Attribute | Binding point | Varying name in shader |
|---|---|---|
| `a_Position` | `ChunkShaderBindingPoints.ATTRIBUTE_POSITION` (0) | `iris_position` (post-patch) |
| `a_Color` | `ATTRIBUTE_COLOR` (1) | vertex color |
| `a_TexCoord` | `ATTRIBUTE_TEXTURE` (2) | UV coords |
| `a_LightAndData` | `ATTRIBUTE_LIGHT_MATERIAL_INDEX` (3) | lightmap + material |
| `iris_Normal` | 10 | normal |
| `mc_Entity` | 11 | block entity ID |
| `mc_midTexCoord` | 12 | mid-texture UV |
| `at_tangent` | 13 | tangent |
| `at_midBlock` | 14 | mid-block emission |

After patching, the varyings flowing into the fragment are whatever the `gbuffers_terrain.vsh`
(or fallback) declares as `out`. The exact names depend on the shaderpack. Common ones:

- `vec4 texcoord` / `vec2 texcoord` — UV
- `vec4 lmcoord` / `vec2 lmcoord` — lightmap UV
- `vec4 glcolor` — vertex colour
- `vec3 normal` — world/view normal
- `vec4 mc_Entity` — block entity ID
- `vec2 mc_midTexCoord` — mid-texture UV
- `vec4 at_tangent` — tangent
- `vec4 at_midBlock` — mid-block emission

**Critical implication for Nvidium:** the mesh shader must output whatever the pack's vertex
shader outputs. Since Nvidium is replacing the vertex stage entirely, it must either:

(a) Link the pack's original vertex shader (after patching the vertex stage via
    `TransformPatcher.patchSodium`) alongside the pack's fragment shader, OR

(b) Only replace the vertex with Nvidium's own mesh shader and write the same named varyings
    that the pack's vertex shader would have written (which requires parsing the pack's vertex
    source to find the `out` declarations).

**Option (a) is infeasible** — the mesh shader replaces the traditional vertex stage entirely;
you cannot mix `GL_NV_mesh_shader` meshlets with a conventional vertex shader in the same
program.

**Option (b) requires parsing the pack vertex source.** This is the necessary path. The
transformed vertex GLSL (from `patchSodium`) must be inspected for `out` declarations. Nvidium's
mesh shader must then declare the same `out` varyings and populate them from the terrain vertex
data it reads from the SSBO.

Alternatively: Nvidium can declare a **fixed varying set** that covers all commonly used
varyings, and only enable those the fragment actually uses (detected by searching the fragment
source for the names). This is what DH (`IrisLodRenderProgram`) does — it uses a fixed vertex
attribute set and trusts the patcher to handle the rest.

### Uniforms

The Iris-patched fragment (and vertex if used) expects these uniforms, provided by
`ProgramUniforms` built from `CommonUniforms.addDynamicUniforms(builder, FogMode.PER_VERTEX)`:

**Matrix uniforms** (set per-draw, manually via `SodiumShader.setupState`):
- `mat4 iris_ModelViewMatrix` — model-view
- `mat4 iris_ModelViewMatrixInverse`
- `mat4 iris_ProjectionMatrix`
- `mat4 iris_ProjectionMatrixInv`
- `mat3 iris_NormalMatrix`

**Dynamic uniforms** (updated by `ProgramUniforms.update()`, driven by Iris notifiers):
- Fog: `vec4 fogColor`, `float fogStart`, `float fogEnd`, `int fogShape` (from `FogUniforms`)
- `int renderStage`
- `vec2 atlasSize`, `vec2 gtextureSize`
- `vec4 blendFunc`
- Iris internal: time, camera position, sun angle, etc. (from `CameraUniforms`,
  `WorldTimeUniforms`, etc., all wired through `addNonDynamicUniforms`)

**Custom uniforms** — any `uniform` declared in `shaders/shaders.properties` as a custom
expression; driven by `CustomUniforms.push(this)`.

**Per-region uniforms** specific to Nvidium's mesh shader — these are NOT provided by Iris.
Nvidium currently passes them via its `sceneUniform` UBO (NV bindless buffer address range).
That block is custom to Nvidium and not affected by Iris.

### Samplers and texture units

From `SodiumShader.buildSamplers`:
```java
ProgramSamplers.Builder builder = ProgramSamplers.builder(handle, IrisSamplers.SODIUM_RESERVED_TEXTURE_UNITS);
// SODIUM_RESERVED = {0, 2}  (diffuse atlas=0, lightmap=2; overlay NOT reserved for Sodium)
pipeline.addGbufferOrShadowSamplers(builder, imageBuilder, flipState, isShadowPass, true, true, false);
```

Inside `addGbufferOrShadowSamplers` (`IrisRenderingPipeline` line 732-761):

1. `IrisSamplers.addRenderTargetSamplers` — `colortex4` through `colortex15` (colortex0-3 only
   in fullscreen passes); also `dhDepthTex`/`dhDepthTex1`.
2. `IrisSamplers.addCustomTextures` — any pack-defined custom textures.
3. `IrisSamplers.addLevelSamplers(samplers, this, whitePixel, hasTexture=true, hasLightmap=true, hasOverlay=false)`:
   - Unit 0: `tex` / `texture` / `gtexture` — diffuse block atlas (external, Sodium binds it)
   - Unit 2: `lightmap` — lightmap texture (external, Sodium binds it)
   - Dynamic: `normals`, `specular` (PBR — white pixel if no PBR)
4. `IrisSamplers.addWorldDepthSamplers` — `depthtex0`, `depthtex1`, `depthtex2`
5. `IrisSamplers.addNoiseSampler` — `noisetex`
6. `IrisSamplers.addCustomImages` — any pack image bindings.
7. If shadow samplers present: `IrisSamplers.addShadowSamplers` — `shadowtex0`, `shadowtex1`,
   `shadow`, `watershadow`, `shadowcolor0`, `shadowcolor1`, etc.

For Nvidium's mesh-shader integration, texture units 0 and 2 must be manually bound before the
draw call (just as `SodiumShader.bindTextures` does — lines 177-181 of `SodiumShader.java`):
```java
IrisRenderSystem.bindTextureToUnit(GL_TEXTURE_2D, 0, RenderSystem.getShaderTexture(0)); // block atlas
IrisRenderSystem.bindTextureToUnit(GL_TEXTURE_2D, 2, RenderSystem.getShaderTexture(2)); // lightmap
```

The `ProgramSamplers.update()` call handles all the dynamic samplers (colortex*, depthtex*,
shadows, etc.) — Nvidium does NOT need to bind those manually.

---

## Q5 — Uniform and sampler push mechanism

### How Iris does it for Sodium (SodiumShader.setupState, line 149-175)

```java
public void setupState() {
    applyBlendModes();     // BlendModeOverride + BufferBlendOverride per-draw-buffer
    updateUniforms();      // uniforms.update() + customUniforms.push(this) + samplers.update()
    images.update();
    bindTextures();        // manual: unit 0 = block atlas, unit 2 = lightmap
    // plus u_TexCoordShrink Sodium-specific uniform
}

private void updateUniforms() {
    CapturedRenderingState.INSTANCE.setCurrentAlphaTest(alphaTest);
    samplers.update();         // binds all dynamic samplers (colortex*, shadows, noise, etc.)
    uniforms.update();         // updates all PER_FRAME / PER_TICK uniforms
    customUniforms.push(this); // runs custom uniform expressions from shaders.properties
}
```

The `ProgramUniforms` and `ProgramSamplers` objects are built **once at program construction**
(they hold GL uniform locations and sampler queries) and then called each frame.

### Can Nvidium reuse these objects?

**Yes, recommended.** The `ProgramUniforms` + `ProgramSamplers` + `CustomUniforms` objects are
completely reusable across draw calls; they hold only locations and callbacks, not state. The
construction pattern is:

```java
// At program build time:
ProgramUniforms.Builder uniformBuilder = ProgramUniforms.builder("nvidium_terrain", glProgramId);
ProgramSamplers.Builder samplerBuilder = ProgramSamplers.builder(glProgramId, IrisSamplers.SODIUM_RESERVED_TEXTURE_UNITS);
ProgramImages.Builder imageBuilder = ProgramImages.builder(glProgramId);

CommonUniforms.addDynamicUniforms(uniformBuilder, FogMode.PER_VERTEX);
customUniforms.assignTo(uniformBuilder);
BuiltinReplacementUniforms.addBuiltinReplacementUniforms(uniformBuilder);
pipeline.addGbufferOrShadowSamplers(samplerBuilder, imageBuilder, flipState, isShadowPass, true, true, false);
customUniforms.mapholderToPass(uniformBuilder, this);  // 'this' must implement CustomUniforms.WrappedUniformHolder

ProgramUniforms uniforms = uniformBuilder.buildUniforms();
ProgramSamplers samplers = samplerBuilder.build();
ProgramImages images = imageBuilder.build();

// Per-draw:
GL20.glUseProgram(glProgramId);
samplers.update();
uniforms.update();
customUniforms.push(this);
images.update();
```

`CustomUniforms` comes from `irisPipeline.getCustomUniforms()` — public method, no accessor.

The `CustomUniforms.mapholderToPass` call requires `this` (the program object) to implement
`net.irisshaders.iris.uniforms.custom.CustomUniforms.WrappedUniformHolder`. This is a simple
interface; Nvidium's `IrisProgramBridge` just needs to implement it (returning the GL program
handle for uniform location queries).

Matrix uniforms (`iris_ModelViewMatrix`, `iris_ProjectionMatrix`, etc.) are NOT part of
`ProgramUniforms` — they are Sodium-specific `GlUniformMatrix4f` objects bound at construction
via `ShaderBindingContext.bindUniformOptional`. Nvidium must look up these uniform locations
manually after linking the program and set them per-draw, just as `IrisLodRenderProgram` does
(lines 127-131 in that file).

### IrisLodRenderProgram as the definitive template

`IrisLodRenderProgram` (Iris's DH terrain integration, `Iris-1.21.1/common/.../dh/IrisLodRenderProgram.java`)
is the best complete template for Nvidium because:
- It uses a **custom vertex shader** (DH's own), not Sodium's.
- It links the pack's fragment shader via `TransformPatcher.patchDHTerrain(...)`.
- It builds `ProgramUniforms` + `ProgramSamplers` + `ProgramImages` the same way.
- It manually tracks and sets matrix uniforms.
- It calls `samplers.update() + uniforms.update() + customUniforms.push(this)` per-draw.

The only difference for Nvidium is using `patchSodium` instead of `patchDHTerrain` (different
transformer rules for Sodium-targeted vs DH-targeted packs) and using mesh/task shader stages.

---

## Q6 — Recommended accessor-mixin list and integration sketch

### Accessor mixins Nvidium must add

| Mixin class | Target Iris class | Field/method exposed | Notes |
|---|---|---|---|
| `NvidiumIrisRenderingPipelineMixin` | `IrisRenderingPipeline` | `renderTargets: RenderTargets` (shadow) | For creating custom FBOs if needed |
| `NvidiumIrisRenderingPipelineMixin` | `IrisRenderingPipeline` | `flippedAfterPrepare: ImmutableSet<Integer>` (shadow) | For solid/cutout FBO creation |
| `NvidiumIrisRenderingPipelineMixin` | `IrisRenderingPipeline` | `flippedAfterTranslucent: ImmutableSet<Integer>` (shadow) | For translucent FBO creation |
| `NvidiumIrisRenderingPipelineMixin` | `IrisRenderingPipeline` | `shadowRenderer: ShadowRenderer` (shadow) | For shadow FBO; may be null |
| `NvidiumIrisRenderingPipelineMixin` | `IrisRenderingPipeline` | `resolver: ProgramFallbackResolver` (shadow) | To get ProgramSource per pass |
| `NvidiumIrisRenderingPipelineMixin` | `IrisRenderingPipeline` | `@Inject beginLevelRendering RETURN` | Detect window resize / FBO invalidation |
| `NvidiumShadowRendererMixin` | `ShadowRenderer` | `getTargets(): ShadowRenderTargets` (shadow) | For shadow FBO creation |

**Methods NOT needing an accessor (already public on the pipeline):**

- `IrisRenderingPipeline.getSodiumPrograms()` → `SodiumPrograms`
- `IrisRenderingPipeline.getCustomUniforms()` → `CustomUniforms`
- `IrisRenderingPipeline.getTextureMap()` → `Object2ObjectMap<...>`
- `IrisRenderingPipeline.addGbufferOrShadowSamplers(...)` (public method, line 732)
- `SodiumPrograms.getFramebuffer(TerrainRenderPass)` (public)
- `TransformPatcher.patchSodium(...)` (public static)

### 8-step integration sketch for `IrisProgramBridge` + `IrisGbufferBinder`

**At shaderpack load** (triggered when Nvidium detects `IrisCheck.isShaderPackActive()` and
the pipeline is non-null):

**Step 1 — Obtain pipeline and resolver**
```java
IrisRenderingPipeline pipeline = (IrisRenderingPipeline) Iris.getPipelineManager().getPipelineNullable();
ProgramFallbackResolver resolver = ((NvidiumIrisRenderingPipelineAccessor) pipeline).nvidium$getResolver();
CustomUniforms customUniforms = pipeline.getCustomUniforms();
```

**Step 2 — Resolve ProgramSource for each terrain pass**
```java
ProgramSource solidSrc    = resolver.resolveNullable(ProgramId.TerrainSolid);   // may be null
ProgramSource cutoutSrc   = resolver.resolveNullable(ProgramId.TerrainCutout);
ProgramSource waterSrc    = resolver.resolveNullable(ProgramId.Water);          // translucent
```
If `resolveNullable` returns null for all, Iris has no terrain program → skip integration,
fall back to Nvidium's own frag shader.

**Step 3 — Transform the fragment source for each pass**
```java
AlphaTest alpha = solidSrc.getDirectives().getAlphaTestOverride().orElse(AlphaTest.ALWAYS);
Map<PatchShaderType, String> transformed = TransformPatcher.patchSodium(
    solidSrc.getName(),
    null, null, null, null,                        // no vertex/geom/tess stages from pack
    solidSrc.getFragmentSource().orElse(null),
    alpha,
    pipeline.getTextureMap());
String fragmentGlsl = transformed.get(PatchShaderType.FRAGMENT);
```

**Step 4 — Determine the required varying set**
Parse the transformed fragment for `in` declarations (or the original vertex source for `out`
declarations). Build a fixed varying interface block for Nvidium's mesh shader to output. At
minimum: `vec2 texcoord`, `vec4 lmcoord`, `vec4 glcolor`, `vec3 normal`, and optionally
`mc_Entity`, `mc_midTexCoord`, `at_tangent`, `at_midBlock` when the vertex source declares them.

**Step 5 — Compile the linked mesh-shader + pack-fragment program**
```java
// Build Nvidium's task/mesh sources (existing GLSL + varying outputs added above)
String taskGlsl = ShaderLoader.parse(...) + <varying output declarations>;
String meshGlsl  = ShaderLoader.parse(...) + <varying output + populate from terrainData>;

// Iris provides only VERTEX, FRAGMENT, etc. ShaderType; Nvidium must use NV raw GL calls
int taskId = GL20.glCreateShader(NVMeshShader.GL_TASK_SHADER_NV);
int meshId = GL20.glCreateShader(NVMeshShader.GL_MESH_SHADER_NV);
int fragId = new GlShader(ShaderType.FRAGMENT, "nvidium_solid", fragmentGlsl).getHandle();
int programId = GL20.glCreateProgram();
// attach all three, link, verify
```

NOTE: Iris's `GlShader` only knows `VERTEX / GEOMETRY / FRAGMENT / TESSELATION_*`. Nvidium
must call the GL mesh shader APIs directly (as it already does in `Shader.Builder`). The
fragment stage, however, can use `new net.irisshaders.iris.gl.shader.GlShader(ShaderType.FRAGMENT, ...)`.

**Step 6 — Build ProgramUniforms / ProgramSamplers / ProgramImages**
```java
// Determine flip state for this pass
boolean translucent = (pass == DefaultTerrainRenderPasses.TRANSLUCENT);
Supplier<ImmutableSet<Integer>> flipState = translucent
    ? pipeline::getFlippedAfterTranslucent
    : pipeline::getFlippedAfterPrepare;
boolean isShadow = IrisCheck.isRenderingShadowPass();

ProgramUniforms.Builder uniformBuilder = ProgramUniforms.builder("nvidium_" + passName, programId);
ProgramSamplers.Builder samplerBuilder = ProgramSamplers.builder(programId, IrisSamplers.SODIUM_RESERVED_TEXTURE_UNITS);
ProgramImages.Builder imageBuilder = ProgramImages.builder(programId);

CommonUniforms.addDynamicUniforms(uniformBuilder, FogMode.PER_VERTEX);
customUniforms.assignTo(uniformBuilder);
BuiltinReplacementUniforms.addBuiltinReplacementUniforms(uniformBuilder);
pipeline.addGbufferOrShadowSamplers(samplerBuilder, imageBuilder, flipState, isShadow, true, true, false);
customUniforms.mapholderToPass(uniformBuilder, nvidiumProgramBridge);  // implements WrappedUniformHolder

ProgramUniforms uniforms = uniformBuilder.buildUniforms();
ProgramSamplers samplers = samplerBuilder.build();
ProgramImages images = imageBuilder.build();
```

Also cache matrix uniform locations:
```java
int uModelView     = GL20.glGetUniformLocation(programId, "iris_ModelViewMatrix");
int uProjection    = GL20.glGetUniformLocation(programId, "iris_ProjectionMatrix");
int uNormalMatrix  = GL20.glGetUniformLocation(programId, "iris_NormalMatrix");
```

**Step 7 — Get/create the framebuffer**

Option A (simplest — reuse Sodium FBO):
```java
GlFramebuffer fbo = pipeline.getSodiumPrograms().getFramebuffer(terrainRenderPass);
```

Option B (own FBO, needed if running outside Sodium's render loop):
```java
ProgramSource src = solidSrc;
int[] drawBuffers = src.getDirectives().getDrawBuffers();
GlFramebuffer fbo = ((NvidiumIrisRenderingPipelineAccessor) pipeline)
    .nvidium$getRenderTargets()
    .createGbufferFramebuffer(flipState.get(), drawBuffers);
```

**Step 8 — Per-draw call (in IrisGbufferBinder.bind)**
```java
fbo.bind();                          // binds FBO and sets draw buffers
GL20.glUseProgram(programId);
// Bind diffuse atlas and lightmap manually (Sodium does NOT do this via ProgramSamplers):
IrisRenderSystem.bindTextureToUnit(GL_TEXTURE_2D, 0, RenderSystem.getShaderTexture(0)); // block atlas
IrisRenderSystem.bindTextureToUnit(GL_TEXTURE_2D, IrisSamplers.LIGHTMAP_TEXTURE_UNIT,
    RenderSystem.getShaderTexture(2)); // lightmap
samplers.update();                   // binds colortex*, depthtex*, shadows, noise, etc.
uniforms.update();                   // uploads all dynamic PER_FRAME uniforms
customUniforms.push(nvidiumBridge);  // runs custom uniform expressions
images.update();
// Push matrix uniforms manually:
if (uModelView  != -1) GL20.glUniformMatrix4fv(uModelView,  false, modelViewMatrix.get(new float[16]));
if (uProjection != -1) GL20.glUniformMatrix4fv(uProjection, false, projectionMatrix.get(new float[16]));
if (uNormalMatrix != -1) GL20.glUniformMatrix3fv(uNormalMatrix, false, normalMatrix.get(new float[9]));
// Then issue glMultiDrawMeshTasksIndirectNV(...)
// After:
ProgramUniforms.clearActiveUniforms();
ProgramSamplers.clearActiveSamplers();
BlendModeOverride.restore();
Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
```

---

## Fragility notes and risks

1. **Varying interface mismatch is the hardest problem.** Different shaderpacks declare
   different varyings in their vertex shaders. Nvidium's mesh shader must emit whatever the
   pack's fragment shader consumes. There is no single fixed contract. The safest approach is
   to parse the (unpatched) pack vertex source for `out` declarations and generate the mesh
   shader's `out` block dynamically. This is non-trivial but necessary for broad compatibility.

2. **TransformPatcher.patchSodium with null vertex.** Passing null for the vertex source and
   only transforming the fragment is technically supported (the transformer skips null inputs),
   but the fragment may reference varyings that the transformer would have renamed if the vertex
   were present. In practice, the fragment-only transform is what DH does for its custom vertex
   programs. Watch for `varying` name mismatches.

3. **GlFramebuffer FBO reuse vs. own FBOs.** Reusing `SodiumPrograms.getFramebuffer(pass)` is
   the simplest path but means Nvidium and Sodium's terrain renderer share the same GL FBO
   object. If they ever render simultaneously (they should not, but check) there could be
   binding conflicts.

4. **Shadow pass.** `IrisCheck.isRenderingShadowPass()` / `ShadowRenderingState.areShadowsCurrentlyBeingRendered()`
   must gate the shadow draw path. The shadow FBO has a different resolution
   (`ShadowRenderer.RESOLUTION`) and requires `pipeline.getSodiumPrograms().getFramebuffer(SOLID)`
   which already auto-redirects to the shadow FBO when the shadow flag is set.

5. **Program lifecycle / reload.** The IrisRenderingPipeline is rebuilt on shaderpack reload
   and on resolution change. Nvidium's bridge programs must be torn down and rebuilt in
   `IrisRenderingPipeline.destroy()` — inject via `@Inject(method="destroy", at=@At("HEAD"))`.

6. **CustomUniforms.mapholderToPass requires a WrappedUniformHolder.** This interface lives in
   `net.irisshaders.iris.uniforms.custom.CustomUniforms`. Verify it is public and that
   NeoForge's module system lets Nvidium implement it. If it is package-private or sealed,
   a shim will be needed.
