# Nvidium x Complementary Unbound — dithered translucent (sky-through-terrain) overlay

**Date:** 2026-05-30
**Scope:** READ-ONLY investigation. No code changed. Why does Nvidium terrain, rendered
through Complementary Unbound r5.7.1 (deferred + TAA), get a pervasive dithered translucent
(whitish) overlay through which the background sky shows on top of *solid* terrain?

**Confirmed-NOT causes (given):** Distant Horizons (overlay persists with DH off), surface
shading (survived albedo/normal/atlas/cutout/lightmap fixes), geometry (correct).
**Conclusion of this note:** it is a **depth-value / composite-stage classification** problem.

---

## TL;DR (lead with the answer)

**#1 most-likely cause:** Complementary's deferred + fog + composite stages classify and shade
every pixel from the **depth buffer**. Two depth-driven tests run on Nvidium terrain:

1. **Binary sky test** — `deferred1.glsl` / `composite.glsl` / `composite1.glsl` all branch on
   `z0 < 1.0` (terrain) vs `z0 == 1.0` (sky). Any Nvidium pixel whose depth reads `1.0` (or never
   got written, leaving the cleared depth of `1.0`) is treated as **sky**: `skyFade = 1.0`, sky
   color / clouds / aurora composited over it. That is literally "sky shows through terrain."
2. **Distance fog** — `BORDER_FOG` and `ATMOSPHERIC_FOG` (both default-ON) blend the pixel toward
   sky/fog color by an amount derived from the **reconstructed view-space distance**
   `lViewPos = length(gbufferProjectionInverse * (vec4(texCoord, z0, 1) * 2 - 1))`. If Nvidium's
   written depth is *biased toward 1.0*, `lViewPos` is grossly overestimated and near terrain gets
   render-distance-edge fog — a translucent sky-colored wash over solid blocks.

The **dithered** quality comes from the dither these stages apply when sampling fog/cloud/reflection
(`deferred1.glsl`, `composite.glsl`, `composite1.glsl` all do `dither = fract(dither + goldenRatio*frameCounter)` when TAA is on, plus `mainClouds`/reflections use Bayer/noise). TAA then
temporally shuffles the dithered blend → a shimmering translucent overlay, not a clean tint.

**The depth value is the single common root** behind both #1 and #2. The mesh shader writes
`gl_Position = MVP*(transformMat*pos)` where `MVP = crm.projection() * crm.modelView()`. That matrix
*is* consistent with Iris's captured `gbufferProjection` (both come from the same vanilla projection
that Nvidium forces to far=8192 — see Q2), so in principle the depth should be correct. The two
realistic ways it goes wrong in practice:

  - **(a) The depth attachment isn't actually being written** during the Nvidium primary draw
    (depth-mask state / FBO depth attachment), so the composite reads cleared depth `1.0` → whole
    terrain = "sky." This would produce a *total* see-through, strongest candidate if the overlay is
    uniform across all terrain regardless of distance.
  - **(b) The depth IS written but the 8192 far-plane crushes precision / the `far` uniform
    mismatch (=renderDistance, ~192, NOT 8192) makes fog math read distances wrong**, so terrain is
    fogged/over-blended as if far away. Strongest candidate if the overlay gets worse with distance.

**Cheapest first confirmation (no rebuild):** In the Complementary settings, set
`TAA_MODE` / Anti-Aliasing **off** AND **toggle Border Fog / Atmospheric Fog off** (see Q5). If the
whitish overlay vanishes with fog off, it's the depth→fog path (cause b). If it persists with fog
*and* TAA off but the terrain is still see-through to sky, it's the binary sky test (cause a — depth
not written / reading 1.0). That bisection picks the fix below.

**Top fix:** guarantee Nvidium's primary terrain draw writes correct depth into the bound gbuffer
FBO's depth attachment — explicitly `glDepthMask(true)` + `glDepthFunc(GL_LEQUAL)` immediately before
the Iris primary raster in `RenderPipeline.renderFrame` (it currently only `glEnable(GL_DEPTH_TEST)`
at line 400 and relies on inherited mask state), and verify the bound FBO actually has the shared
depth texture attached. Then, if the overlay is distance-dependent, address the far-plane/`far`-uniform
mismatch (Q6).

---

## Q1 — How the composite decides sky vs terrain, and the mis-classification risk

Complementary is a **deferred** pack. The terrain *gbuffer* fragment only fills G-buffer targets
(see Q3); all sky/fog/lighting decisions happen later in deferred/composite passes, driven by depth.

Files and the exact tests:

- `shaders/program/deferred1.glsl` (the main deferred lighting/sky/fog pass), `main()`:
  - reads `float z0 = texelFetch(depthtex0, texelCoord, 0).r;`
  - `if (z0 < 1.0) { ...lighting, SSAO, reflections, DoFog(...) ... }`
  - `else { // Sky ... skyFade = 1.0; aurora/nebula/nether/end sky color; }`
  - i.e. **`z0 == 1.0` ⇒ the pixel is treated as sky.** Then clouds are composited
    (`if (z0 > 0.56) GetClouds(...)`) and atmospheric/weather fog over it.
  - The DH sub-branch (`#ifdef DISTANT_HORIZONS`, lines ~248-259) samples `dhDepthTex`; with DH off
    it falls straight to "Actual Sky." This is consistent with "overlay persists with DH off."

- `shaders/program/composite.glsl` (reflections), `main()`:
  - `z0 = texelFetch(depthtex0,...)`, `z1 = texelFetch(depthtex1,...)`
  - `if (z0 < 1.0 ...) { reflections... }` — sky again gated on `z0 == 1.0`.

- `shaders/program/composite1.glsl` (refraction / volumetrics / fog), depth-driven throughout:
  - reconstructs `viewPos1` from `z1` via `gbufferProjectionInverse`, computes `lViewPos1`,
  - solids vs translucents distinguished by `z0 == z1 || z0 <= 0.56`,
  - `if (z1 == 1.0) color.rgb = fogColor * 5.0;` (lava-eye case) — another `==1.0` sky branch.

- Distance-fog math (`shaders/lib/atmospherics/fog/mainFog.glsl`):
  - `DoBorderFog`: `fog` from `max(length(playerPos.xz), abs(playerPos.y))` / `renderDistance`,
    blends `color = mix(color, vec4(GetSky(...),0), fog)` and sets `skyFade = fog`. `playerPos`
    derives from `z0`. **Wrong (too-large) depth ⇒ fog≈1 ⇒ pixel becomes sky color.**
  - `DoAtmosphericFog`: `fog = 1 - exp(-(lViewPos*k)^2 ...)`, again from depth-reconstructed distance.
  - Both `BORDER_FOG` and `ATMOSPHERIC_FOG` are `#define`d unconditionally in `lib/common.glsl`
    (lines ~124 and ~352) — **on by default**.

**Verdict for Q1:** Yes — the sky/background test is `depth >= 1.0` (binary) PLUS a continuous
depth→distance fog blend. Nvidium terrain whose depth is pushed toward `1.0` (or unwritten) is
exactly mis-classified as sky and/or over-fogged. This is the prime suspect for "background shows
through terrain," and it fits "dithered translucent whitish" because fog/sky color blended with a
dithered factor under TAA looks like a shimmering wash, not a solid replacement.

---

## Q2 — What our path writes for depth, and is it consistent?

**Does the mesh draw write depth?** The primary terrain raster happens in
`RenderPipeline.renderFrame` line 406 (`terrainRasterizer.raster(...)`), wrapped by
`IrisRenderBridge.beginPrimary/endPrimary`. Immediately before it (line 400) only:

```java
glEnable(GL_DEPTH_TEST);   // line 400 — NO explicit glDepthMask/glDepthFunc here
boolean iris = ... beginPrimary(...);
terrainRasterizer.raster(...);   // line 406
```

The depth **mask** is whatever inherited GL state is current. Later in the same method the mask is
explicitly toggled false for the occlusion/section/visibility rasters (lines 416, 464) and restored
true (lines 448, 471) — so on steady-state frames the mask is likely `true` entering line 400, but
this is **not guaranteed** at this call site (no explicit set). `IrisGbufferBinder.beginPass`
binds `pipeline.getSodiumPrograms().getFramebuffer(pass)` (Iris's gbuffer FBO) and that FBO *does*
carry a depth attachment (Iris `RenderTargets.createGbufferFramebuffer` → `addDepthAttachment(currentDepthTexture)`), and `RenderPipeline` enables `GL_DEPTH_TEST` — but the binder never asserts
the depth mask. **If the mask is off when the Iris primary draw runs, depth is never written →
composite reads cleared `1.0` → whole terrain classified as sky.** This is the highest-value thing
to make explicit (Q6 fix #1).

**Is the depth VALUE consistent with what the composite expects?**

- Nvidium forces the projection far-plane to `16*512 = 8192` for the whole game:
  `MixinGameRenderer.getDepthFar` returns `8192f` when `Nvidium.IS_ENABLED`.
- Iris captures `gbufferProjection` from the **vanilla projection argument** at `renderLevel` HEAD
  (`MixinLevelRenderer` line 91 → `CapturedRenderingState.setGbufferProjection(projection)`), and
  `MatrixUniforms` feeds that straight to the pack as `gbufferProjection`. That projection already
  has far=8192 (Nvidium's mixin is upstream). Iris's far-plane tweak mixin
  (`MixinTweakFarPlane`) is **disabled** ("I have decided to disable this Mixin" — class javadoc),
  so Iris does not change the far plane.
- Sodium's `crm.projection()` (used by Nvidium's MVP and passed to `IrisTerrainProgram.setMatrices`)
  is derived from the same vanilla projection. **So the matrix Nvidium renders with and the
  `gbufferProjection`/`gbufferProjectionInverse` the composite uses are the SAME (far=8192).** The
  position-reconstruction path (`gbufferProjectionInverse * (screenPos*2-1)`) therefore stays
  self-consistent regardless of the far value, and that is reassuring.

**BUT — the `far` *uniform* is NOT 8192.** `CameraUniforms` binds:
```
near = 0.05 (constant), far = getRenderDistanceInBlocks()  // e.g. 192 for 12 chunks
```
Complementary's `GetLinearDepth(d) = 2*near / (far + near - d*(far-near))` and several fog terms use
this `far`(~192), while the depth values in the buffer come from a far=8192 projection. This
**near/far inconsistency** means:
  - `GetLinearDepth` returns wrong (compressed) linear depths for Nvidium pixels,
  - the 8192 far-plane crushes 24-bit depth precision so even *correct* near terrain lands at depth
    values much closer to 1.0 than the pack's `far=192` math assumes.
This is a real second-order contributor to over-fogging / mis-distancing. Note it is a *pre-existing*
Nvidium behavior (the far override is unconditional, not new to the Iris path), so if the overlay is
new with the Iris integration, cause (a) (depth not written) is more likely the trigger than this.

**TAA projection jitter:** Iris does **not** jitter the projection matrix (no jitter mixin on the
game projection; `MatrixUniforms` passes the captured projection verbatim). The jitter is applied
**inside the pack's vertex shader** — see Q3. Therefore `crm.projection()` is **pre-jitter** (no
jitter at all), and so is Nvidium's MVP. The rest of the scene IS jittered (pack vertex shaders call
`TAAJitter`); Nvidium terrain is not. This is a genuine inconsistency (Q3) but is a TAA-quality
issue, not the see-through cause.

---

## Q3 — TAA velocity / previous-frame output, and the jitter mismatch

**Does `gbuffers_terrain` write a velocity / previous-position output?** **No.** Its DRAWBUFFERS:
```
/* DRAWBUFFERS:06 */                                 (gbuffers_terrain.glsl ~368)
gl_FragData[0] = color;                              // colortex0 = albedo/scene color
gl_FragData[1] = vec4(smoothnessD, materialMask, skyLightFactor, 1.0);  // colortex6 = material
// (optional) /* DRAWBUFFERS:064 */ gl_FragData[2] = encoded normal into colortex4 (PBR path)
```
`gbuffers_water` writes `03` (+`6`,`4`,`8` variants). **None of these is a motion-vector buffer.**

**How TAA reprojects:** `shaders/lib/antialiasing/taa.glsl` → `Reprojection(viewPos1)` reconstructs
the previous-frame screen coord purely from **depth + camera-delta matrices**:
```
pos = gbufferModelViewInverse * viewPos1;
previousPosition = pos + (cameraPosition - previousCameraPosition);
previousPosition = gbufferPreviousProjection * gbufferPreviousModelView * previousPosition;
return previousPosition.xy / w * 0.5 + 0.5;
```
So TAA needs **no per-vertex velocity varying** — it only needs correct **depth**. Nvidium's mesh
path does NOT need to emit a velocity output. (This rules out the "missing velocity varying" theory
as a cause of the overlay.)

**The real TAA defect:** `gbuffers_terrain.glsl` vertex (lines 474-482):
```
gl_Position = gl_ProjectionMatrix * gbufferModelView * position;
#ifdef TAA
    gl_Position.xy = TAAJitter(gl_Position.xy, gl_Position.w);   // sub-pixel jitter per frame
#endif
```
`TAAJitter` (`lib/antialiasing/jitter.glsl`) offsets clip-space `.xy` by an 8-sample pattern indexed
by `framemod8`. **Nvidium's mesh shader applies NO jitter** (`mesh.glsl`: plain `MVP * transformMat *
pos`). Consequences:
  - Nvidium terrain samples the scene at the *un-jittered* pixel center every frame while TAA's
    history/reprojection logic and `DoTAA`'s neighborhood clamp assume jittered inputs → the
    `NeighbourhoodClamping` / `ClipAABB` history blend is fed inconsistent samples.
  - `DoTAA` is gated `if (z1 > 0.56) prvCoord = Reprojection(...)` and blends current toward history
    with `blendFactor` up to `blendConstant=0.7`. With a jitter mismatch + (possibly) bad depth, the
    history blend smears — contributing the **shimmering/dithered** look on top of the fog wash.

**Is the dithered translucent overlay consistent with broken TAA reprojection?** Partly. Broken TAA
gives ghosting/shimmer/edge-dither, but it does **not** by itself make solid terrain transparent to
the sky. The *see-through* part is Q1 (depth → sky/fog). The *dithered/shimmer* part is TAA + the
dithered fog/cloud sampling reprojected over time. So the overlay is a **compound** symptom: bad/
absent depth (see-through) amplified and textured by TAA + dither.

---

## Q4 — Dither source

Dither/Bayer/noise usage (search across `shaders/program` + `shaders/lib`, by count):
- `lib/util/dither.glsl` — `Bayer2..Bayer256` ordered-dither ladder. Included by composite/deferred.
- `deferred1.glsl` (11 hits), `composite.glsl` (5), `composite1.glsl` (9) — all do
  `dither = texture2DLod(noisetex, ...).b; #ifdef TAA dither = fract(dither + goldenRatio*frameCounter);`
  then use `dither` to jitter **reflection** roughness samples and **fog/SSAO** sampling. TAA-animated
  dither is the classic source of a moving stippled pattern.
- `lib/atmospherics/clouds/mainClouds.glsl` / `unboundClouds.glsl` (6) — cloud raymarch dithers the
  step offset; composited where `z0 > 0.56` (i.e. over far/sky-classified terrain).
- `lib/materials/materialMethods/reflections.glsl` (9) / `worldSpaceRef.glsl` (11) — SSR ray dither.
- `lib/antialiasing/fxaa.glsl` + `taa.glsl` — the AA stages.

**Which matches "translucent whitish overlay over terrain"?** The combination of **(fog/sky blend)
× (TAA-animated dither)**: `deferred1`/`composite1` blend terrain toward bright sky/fog color
(`GetSky`, `fogColorM`, clouds) by a factor that, when the pixel is mis-distanced/mis-classified,
is large; the blend's sampling is dithered and then temporally reprojected → a shimmering whitish
veil. Pure TAA accumulation alone would ghost but stay opaque; pure alpha-dither would be static.
The whitish-and-see-through signature points at **fog/sky compositing driven by wrong depth**,
textured by dither+TAA.

---

## Q5 — Cheap in-game confirmations (NO rebuild)

Flip these in the in-game shader settings (Options → Video Settings → Shader Packs → Complementary
Unbound → settings), one at a time, and observe whether the overlay changes. Each isolates a stage:

1. **Anti-Aliasing / TAA → OFF** (`TAA_MODE 0`, under Misc/Post-Processing).
   - Overlay's *shimmer/dither* disappears but a static whitish wash remains ⇒ TAA was only
     texturing the problem; root is depth→fog/sky (cause a/b). **Run this first** alongside #2.
   - Overlay fully gone ⇒ the dominant artifact was TAA history mixing (jitter mismatch). Less likely
     given "see-through," but possible.

2. **Border Fog → OFF** and **Atmospheric Fog → OFF** (Atmospherics/Fog section: `BORDER_FOG`,
   `ATMOSPHERIC_FOG`).
   - Overlay vanishes or sharply reduces ⇒ **confirmed depth→distance-fog cause (b)**: terrain is
     being fogged as if at the render-distance edge because its reconstructed distance is too large
     (far-plane/`far`-uniform issue). This is the cleanest single confirmation.

3. **Clouds → OFF** (Atmospherics → Clouds).
   - If a chunk of the whitish veil is clouds composited over sky-classified terrain, this removes it
     and points at the binary `z0==1.0` sky test (cause a).

4. **Set the in-game render distance LOWER (e.g. 8 chunks) then HIGHER (e.g. 24).**
   - Overlay strength scales with distance / worsens far away ⇒ depth-precision / far-plane (cause b).
   - Overlay uniform regardless of distance and present even 5 blocks away ⇒ depth not written at all
     (cause a). This distance test discriminates (a) vs (b) without touching settings files.

5. **Iris/Nvidium:** there is a debug hook already wired: launch with
   `-Dnvidium.iris.debug=flat` (writes solid white to colortex0). Because the pack is deferred, the
   composite still reprocesses it — so if `flat` terrain still goes see-through/whitish, it
   **proves the cause is in the depth/composite stage, not shading** (shading is bypassed). This is
   the definitive bisection and needs no rebuild (it is a JVM `-D` flag, set in the launcher).

Expected most-informative pair: **(1)+(2) together** — TAA off + fog off. If terrain becomes solid
and opaque, the entire overlay is the depth→fog/sky+TAA chain (this note's thesis), and the fix is Q6.

---

## Q6 — Prioritized fix plan (Nvidium integration)

Ordered by likelihood and cost. Do the in-game confirmation (Q5 #1+#2, and the distance test #4)
first to pick (a) vs (b); the fixes are complementary, not exclusive.

### Fix 1 (highest priority) — Guarantee depth is written by the primary Iris draw
`RenderPipeline.renderFrame` enables depth test (line 400) but never sets the depth mask/func before
the Iris primary raster. Mirror what Sodium's own terrain draw does, immediately before line 406:
```java
glEnable(GL_DEPTH_TEST);
glDepthFunc(GL_LEQUAL);     // match the rest of the pipeline (and Nvidium's own line 415)
glDepthMask(true);          // <-- make depth-write explicit, do not inherit
```
and confirm in `IrisGbufferBinder.beginPass` that the bound FBO
(`pipeline.getSodiumPrograms().getFramebuffer(pass)`) has the shared depth texture attached (it
should via Iris `RenderTargets`, but assert/log the attachment once). If Q5 #4 shows a uniform
distance-independent overlay, this is almost certainly the fix.

### Fix 2 — Make Nvidium's projection match the pack's TAA jitter
The pack jitters in clip space inside its vertex stage; Nvidium's mesh stage does not, so Nvidium
terrain is the only un-jittered geometry and TAA history is inconsistent for it. Options, cheapest
first:
  - **Bake the jitter into the projection Nvidium uses.** Before computing `MVP` for the Iris path,
    post-multiply `crm.projection()` by the same per-frame sub-pixel translation Complementary uses
    (`jitterOffsets[framemod8] * scale / viewport`, from `lib/antialiasing/jitter.glsl`, gated by the
    pack's `TAA_JITTER`). This must be applied to the **clip xy** the same way `TAAJitter` does
    (`gl_Position.xy += offset * gl_Position.w`), i.e. add `offset` to rows of the projection that
    scale x/y by `w`. Plumb `framemod8`/frameCounter + viewport into the `SceneData` UBO (or as a
    uniform on the Iris program) and apply in `mesh.glsl`'s `transformVertex`. This keeps the depth
    correct (jitter is xy-only) while matching the rest of the scene.
  - Simpler interim: if jitter can't be matched cleanly, this only costs TAA *quality* (shimmer), not
    correctness — defer until after Fix 1/3 if the see-through is gone.

### Fix 3 — Resolve the far-plane / `far`-uniform distance mismatch (if overlay is distance-dependent)
The depth buffer uses far=8192 (Nvidium's `MixinGameRenderer.getDepthFar`), but Complementary's
`far` uniform = renderDistanceInBlocks (~192) and its `GetLinearDepth`/fog math assume that. Two
avenues:
  - **Don't override the far plane while in the Iris SHADERS path.** Investigate whether the 8192
    override is actually needed when terrain renders through Iris (it exists for Nvidium's own
    extended-distance rendering). If the override can be scoped to VANILLA mode (or set to match
    `getRenderDistanceInBlocks()`-derived vanilla far), the depth distribution and `far` uniform line
    up and the fog math is correct. Lowest-risk place: condition the `cir.setReturnValue` in
    `MixinGameRenderer` on `Nvidium.MODE != RenderMode.SHADERS`, or return the vanilla far when a
    shaderpack with this profile is active.
  - If the 8192 plane must stay (for DH-less extended draw distance), the mismatch is inherent to the
    pack's `far`-based math and cannot be fully fixed from the Nvidium side without the pack reading a
    matching `far`. In that case prefer scoping the override (above).

### Fix 4 (only if Q5 shows TAA is the dominant artifact) — verify history isn't poisoned
`DoTAA` early-outs on `tempColor == 0 || isnan`. If Nvidium terrain ever writes NaN/zero into
colortex0 on edge frames, history seeds badly. Low probability given shading is fixed; check only if
Fixes 1-3 don't clear it.

**Recommended sequence:** confirm with Q5 #4 (distance test) + #1/#2 (TAA/fog off) → if uniform/see-
through, do **Fix 1** → re-test → if distance-dependent fog wash remains, do **Fix 3** (scope the far
override) → finally **Fix 2** for TAA shimmer polish.

---

## Uncertainty / caveats

- I could not run the game, so I cannot directly read the depth texture to confirm whether Nvidium's
  primary draw is writing depth `< 1.0`. The Q5 distance test (#4) and `-Dnvidium.iris.debug=flat`
  are the decisive runtime confirmations and should be run before committing a fix.
- The depth-mask-at-line-400 concern is inferred from the code (no explicit `glDepthMask(true)`); on
  steady-state frames the inherited state is probably `true`, so Fix 1 may be a no-op if depth is in
  fact written — in which case the distance/far mismatch (Fix 3) becomes primary. The distance test
  in Q5 disambiguates.
- Far-plane mismatch (Fix 3) is a pre-existing Nvidium behavior, so if the overlay appeared *only*
  with the new Iris terrain hand-off, weight Fix 1 (depth write in the new FBO path) above Fix 3.
- TAA velocity output (Q3) is conclusively NOT required by this pack (reprojection is depth-based);
  do not spend effort adding a motion-vector varying.

## Key files (absolute paths)

Nvidium integration:
- `C:\Users\Ryan-PC\Desktop\MC Stuff\Custom Mods\src\nvidium-1.21.1\src\main\java\me\cortex\nvidium\RenderPipeline.java` (depth state at lines 400-411, 414-471)
- `...\compat\iris\IrisGbufferBinder.java` (FBO bind / matrices / textures; no depth-mask assert)
- `...\compat\iris\IrisProgramBridge.java` (program build; MatrixUniforms/CelestialUniforms wiring)
- `...\compat\iris\IrisTerrainProgram.java` (`setMatrices` — modelView/projection, no jitter)
- `...\compat\iris\IrisRenderBridge.java` (begin/end primary+translucent)
- `...\src\main\resources\assets\nvidium\shaders\terrain\mesh.glsl` (`gl_Position = MVP*transformMat*pos`, no jitter)
- `...\src\main\java\me\cortex\nvidium\mixin\minecraft\MixinGameRenderer.java` (far=8192 override)

Iris reference (`...\nvidium-1.21.1\claude-reference\Iris-1.21.1\common\src\main\java\net\irisshaders\iris\`):
- `uniforms\CameraUniforms.java` (`near=0.05`, `far=getRenderDistanceInBlocks()`)
- `uniforms\MatrixUniforms.java` + `uniforms\CapturedRenderingState.java` (gbufferProjection = vanilla projection)
- `mixin\MixinLevelRenderer.java` (captures projection at renderLevel HEAD)
- `mixin\MixinTweakFarPlane.java` (DISABLED — Iris does not tweak far)
- `targets\RenderTargets.java` (`createGbufferFramebuffer` → `addDepthAttachment(currentDepthTexture)`; `depthtex1` = noTranslucents copy)
- `pipeline\programs\SodiumPrograms.java` (`getFramebuffer`, terrain drawbuffers `06`)

Complementary Unbound r5.7.1 (inside `...\run\shaderpacks\ComplementaryUnbound_r5.7.1.zip`):
- `shaders/program/deferred1.glsl` (z0<1.0 vs sky; DoFog; clouds)
- `shaders/program/composite.glsl`, `composite1.glsl`, `composite6.glsl` (reflections / fog / DoTAA)
- `shaders/program/gbuffers_terrain.glsl` (DRAWBUFFERS:06; vertex TAAJitter at 480-482)
- `shaders/program/gbuffers_water.glsl` (DRAWBUFFERS:03...)
- `shaders/lib/antialiasing/{taa,jitter}.glsl` (Reprojection depth-based; clip-space jitter)
- `shaders/lib/atmospherics/fog/mainFog.glsl` (BORDER_FOG, ATMOSPHERIC_FOG — depth-distance driven)
- `shaders/lib/util/dither.glsl` (Bayer ladder)
- `shaders/lib/common.glsl` (defaults: BORDER_FOG, ATMOSPHERIC_FOG, TAA via TAA_MODE 1, TAA_JITTER 1)
