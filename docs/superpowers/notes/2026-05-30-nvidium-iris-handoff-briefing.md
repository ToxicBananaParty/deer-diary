# Session Handoff — Nvidium NeoForge Port + DH/Iris Compatibility

**Date:** 2026-05-30 (late-night session)
**Branch:** `nvidium-neoforge-port` (NOT merged to `main`; do all work here)
**For:** the next agent picking up this work. Read this first, then the spec + plan (links in §9).

---

## 1. TL;DR

We ported **Nvidium** (NVIDIA mesh-shader terrain renderer for Sodium) from Fabric to **NeoForge 1.21.1**, then made it coexist with **Distant Horizons** and render terrain **through Iris shaderpacks**.

- **DONE & hardware-verified:** the NeoForge port, DH coexistence.
- **MILESTONE hit:** Nvidium renders terrain *through* the Complementary Unbound shaderpack (links, `SHADERS` mode, draws into Iris gbuffers). This is the research-grade achievement — upstream Nvidium refuses to run under Iris at all.
- **OPEN PROBLEM (where you pick up):** under shaders, near terrain renders **visually corrupted** — shredded/torn geometry, dark, reflective-looking land. Distant terrain (DH LODs) is correct. This is **Phase 3 visual correctness**.
- **Safety:** a fallback floor means any failure → Nvidium yields to stock Sodium+Iris and never crashes. The pack is ALWAYS safe regardless of this bug.

Two in-game screenshots from the user this session show the corruption (not stored in repo; described in §4).

---

## 2. Orientation / how to work

- Project dir (`<proj>`): `Custom Mods/src/nvidium-1.21.1/`. Java root: `<proj>/src/main/java/me/cortex/nvidium/`.
- Build: `cd <proj>; .\gradlew.bat build` (PowerShell, Windows). Java 21.
- Run the dev client: `.\gradlew.bat runClient` (launch in background, read `run/logs/` or the task output). **You cannot pilot the game** — the USER must enable the shaderpack and load a world. Coordinate gates with them.
- Hardware: user has an **NVIDIA RTX 4090** (Turing+, required for Nvidium). Nvidium auto-disables on unsupported GPUs.
- Commit locally; **do not push** (user pushes deliberately; repo uses WSL git for SSH but local commits work with the configured identity). End commit messages with the `Co-Authored-By: Claude Opus 4.8 (1M context)` trailer.
- Execution method this session: subagent-driven (fresh subagent per task, controller reviews). Hardware gates need the user.

---

## 3. What's DONE and VERIFIED

- **Phase 0 — dev runtime stack:** Sodium/Iris/DH load in the dev client. Key gotcha: `clientAdditionalRuntimeClasspath` does NOT get mods FML-loaded in dev — use `implementation` (Sodium/Iris/DH are `implementation`; Colorwheel is `compileOnly`, deferred to Phase 4 because it needs Flywheel/Create).
- **Phase 1 — RenderMode arbiter (the fallback floor):** `me.cortex.nvidium.RenderMode` resolves `DISABLED`/`VANILLA`/`SHADERS` per frame. `MixinRenderSectionManager` sets `Nvidium.MODE`/`IS_ENABLED` from it. **Verified** no-op vs the port.
- **Phase 2 — DH coexistence:** `compat/dh/{NvidiumDhCompat, DhBridge}` bind DhApi events (overdraw radius + fog ownership). `MixinFogRenderer` yields fog to DH when active. **Hardware-verified:** user flew 512-render-distance DH at a steady 120fps, correct LOD/occlusion/fog, no artifacts.
- **Phase 3 MILESTONE — Nvidium-through-Iris:** all three terrain passes (SOLID/CUTOUT/TRANSLUCENT) build shaderpack-paired mesh programs and render into Iris gbuffers. Log proof: `Nvidium-Iris: built terrain programs for SOLID/CUTOUT/TRANSLUCENT`. No crash; fallback floor intact.

---

## 4. THE OPEN PROBLEM (Phase 3 visual correctness)

**Symptom (with plain Complementary Unbound r5.7.1, shaders on, in a world):**
- **Near terrain** (Nvidium's real chunks): geometry looks **shredded/torn** — scattered fragments, holes, surfaces at wrong angles — and **dark**, with **mirror/reflective-looking patches on land** (as if land is being shaded as water/wrong material).
- **Distant terrain** (DH LODs, rendered via DH's own correct Iris path): looks **correct**. You can see the seam between broken-near and correct-far.
- Sky, clouds, water all react to shaders correctly.

**KEY DEDUCTION (narrows the hunt):** Vanilla Nvidium geometry is **proven-correct** (port gates 1–3 + the DH flight all render Nvidium terrain perfectly without shaders). Therefore the corruption is **introduced by the Iris gbuffer/depth/shading hand-off, NOT the base mesh geometry/positions.**

**What was tried this session (and the result):**
1. Fixed `$`-in-GLSL-identifier codegen bug (was a hard compile error). ✅ compiles.
2. Rewrote varying generation to be driven by the **fragment's `in` set**, handling multi-variable decls (`flat in vec3 upVec, sunVec, northVec, eastVec;`) and preserving `flat` qualifiers. ✅ links (resolved `"eastVec" not declared` link error).
3. Computed basis vectors + **view-space** normal + eye-relative position from uniforms (per Complementary's own `dh_terrain.glsl`), instead of constants.
4. **Found & fixed a real root cause:** `gbufferModelView`/`sunPosition` were **unbound (zero)** — `IrisProgramBridge` only called `CommonUniforms.addDynamicUniforms`, which does NOT register `gbuffer*`/celestial uniforms (those are in `MatrixUniforms`/`CelestialUniforms`). Added `MatrixUniforms.addMatrixUniforms` + `CelestialUniforms.addCelestialUniforms`.
- **CRITICAL OBSERVATION:** fix #4 (a genuine root cause) did **NOT visibly change** the corruption. This strongly implies the **dominant artifact is NOT a varying/uniform/lighting problem** — it's something those fixes can't touch.

**Leading hypotheses (ranked), for the shredding specifically:**
1. **Depth-buffer / FBO hand-off** (most likely for the *shredding*). Nvidium runs multiple GPU passes (region/section occlusion rasterizers using `GL_NV_representative_fragment_test`, then the terrain raster). When it now renders into the **Iris gbuffer FBO** (bound by `IrisGbufferBinder`), the depth attachment / depth-test interaction across Nvidium's passes vs the gbuffer may be inconsistent → z-fighting/tearing. Investigate `IrisGbufferBinder` FBO/depth handling + `RenderPipeline.renderFrame`'s occlusion passes under SHADERS mode. **Compare:** the `flat` debug mode (§5) bisects this — if `flat` terrain is still shredded, it's depth/FBO/geometry; if `flat` is clean white terrain, geometry/depth is fine and the bug is in shading.
2. **`mat` (material ID) defaulted to 0** → Complementary classifies all terrain as one (wrong) material → reflective/wrong shading on land. Nvidium has no material-id per vertex; would need to derive or supply something sane.
3. **Normal winding flipped** (cross-product `nvFaceNormal`) → inverted/dark lighting. Not flipped this session; flip the `cross(...)` operand order at the two mesh call sites if `normal` debug mode shows inverted.
4. **DRAWBUFFERS / gbuffer outputs:** the deferred pipeline expects the terrain fragment to write multiple gbuffer targets (normal, material, lightmap...). If our program doesn't write all expected targets correctly, composites read garbage → corruption. Check the patched fragment's `/* DRAWBUFFERS */` and that `IrisGbufferBinder` binds the right targets.
5. **Still-unbound uniforms:** `cameraPosition` (CameraUniforms NOT added — procedural world-space detail shifted), `viewWidth/viewHeight`, shadow uniforms. Affects detail/shadows/AA, probably not the gross shredding.

---

## 5. Diagnostic tooling available (added this session — USE THESE)

Launch the dev client with JVM system properties (add to `runClient` JVM args, or `neoForge { runs { client { systemProperty(...) } } }` in build.gradle):

- **`-Dnvidium.iris.dump=true`** — on successful program build, dumps the generated `task`/`mesh`/`fragment` GLSL for each pass to `<proj>/run/nvidium-iris-dump/<pass>-<stage>.glsl`. **Read these offline** to inspect exactly what GLSL is generated (the patched pack fragment, the generated mesh `out` interface + writer, etc.). (Also dumps on failure automatically.)
- **`-Dnvidium.iris.debug=<mode>`** — replaces the pack fragment with a minimal debug fragment to isolate geometry/depth vs shading. Modes:
  - `flat` → solid white. **The key bisection:** clean white terrain shape, no shredding ⇒ geometry/depth is fine, bug is in pack shading. Still shredded ⇒ depth/FBO/geometry problem (hypothesis 1).
  - `normal` → normal as color (check normal correctness/winding).
  - `pos` → `fract(vertexPos)` (check position varying).
  - `uv` → texCoord; `lm` → lmCoord.
  - Deferred caveat: Complementary is deferred, so the debug color lands in the gbuffer (≈colortex0/albedo) and is reprocessed by composites — but gross shredding is still visible and varyings are still indicative.

**Recommended first move next session:** gate with `-Dnvidium.iris.debug=flat`. That single test tells you whether you're chasing a depth/FBO bug (shredding persists) or a shading bug (clean white). Then `-Dnvidium.iris.dump=true` + read the generated GLSL.

Also useful: compare **shaders-on vs shaders-off** (vanilla Nvidium) in the same spot — vanilla is your known-good geometry reference.

---

## 6. Architecture map (key files)

- `RenderMode.java` — the arbiter / fallback floor. `resolve()` returns SHADERS only if `NvidiumIrisCompat.supportsActivePack()`.
- `Nvidium.java` — `MODE`, `IS_ENABLED`, `getModVersion()`.
- `sodiumCompat/IrisCheck.java` — `isShaderPackActive()`, `isRenderingShadowPass()`.
- `compat/dh/{NvidiumDhCompat,DhBridge}.java` — DH coexistence (DhApi events; only file importing `com.seibel.*` is `DhBridge`).
- `compat/iris/`:
  - `NvidiumIrisCompat.java` — `active()`, `supportsActivePack()` (gates SHADERS mode; caches per pipeline).
  - `IrisProgramBridge.java` — builds+caches the per-pass programs (resolve ProgramSource → `TransformPatcher.patchSodium` fragment → varying mapping → link task+mesh+frag → build ProgramUniforms/Samplers via `CommonUniforms`+`MatrixUniforms`+`CelestialUniforms`). Has the debug/dump hooks.
  - `IrisVaryingMapper.java` — parses the patched fragment's `in` set; generates the mesh `out` block + `nvidium_writeIrisVaryings(...)` writer + debug-fragment `in` decls. **This is where varying VALUES are assigned** (basis vectors, normal, position, defaults).
  - `IrisGbufferBinder.java` — per-pass: bind Iris gbuffer FBO, update samplers/uniforms, set matrices; restore main FBO after. **Prime suspect for the depth/FBO hypothesis.**
  - `IrisTerrainProgram.java` — the program wrapper (matrices by location, sampler/uniform holders).
  - `IrisRenderBridge.java` — the per-frame glue `RenderPipeline` calls (`beginPrimary/endPrimary`, `beginTranslucent/endTranslucent`).
- `mixin/iris/{NvidiumIrisRenderingPipelineMixin, NvidiumIrisRenderingPipelineAccessor, NvidiumShadowRendererMixin}` — accessors into Iris internals (`renderTargets`, `resolver`, shadow targets, pack directives). Registered via `nvidium.iris.mixins.json` (`required:false` so it no-ops without Iris).
- `RenderPipeline.java` — terrain raster; SHADERS branch at ~L404 (primary) / ~L531 (translucent) wraps raster with `IrisRenderBridge`. VANILLA path untouched. `lastMatrices` stored for translucent.
- `renderers/{PrimaryTerrainRasterizer,TranslucentTerrainRasterizer}.java` — have an Iris-program override path.
- `src/main/resources/assets/nvidium/shaders/terrain/{mesh.glsl, translucent/mesh.glsl}` — contain the `//__NVIDIUM_IRIS_VARYINGS__` splice marker + call `nvidium_writeIrisVaryings(...)`. Nvidium vars: `nvFaceNormal` (world-space face normal), `nvWorldPos` (eye-relative world pos), decode helpers from the `vertex_format.glsl` import.

---

## 7. Reference material (in `<proj>/claude-reference/`, read-only)

- **`Iris-1.21.1/`** — Iris source. **THE template:** `common/.../iris/compat/dh/IrisLodRenderProgram.java` — Iris rendering DH's LOD terrain through shaderpacks (structurally identical to what we do, but vertex- not mesh-stage). Also `compat/sodium/mixin/MixinShaderChunkRenderer` (how Iris swaps Sodium's program), `pipeline/programs/{SodiumPrograms,ShaderKey,ProgramId}`, `targets/RenderTargets`, `samplers/IrisSamplers`, `uniforms/{CommonUniforms,CelestialUniforms,CameraUniforms,custom/CustomUniforms}`, `shaderpack/transform/TransformPatcher`.
- **`distanthorizons-main/`** — DH source + DhApi.
- **`Colorwheel-1.21.1-dev/`** — Flywheel↔Iris bridge; its `accessors/iris/*` are the accessor-mixin pattern. Relevant for Phase 4.
- **`sodium-1.21.1-stable/`** — Sodium source.
- **The active shaderpack**, for reading its GLSL offline: `<proj>/run/shaderpacks/ComplementaryUnbound_r5.7.1.zip`. Its **`shaders/program/dh_terrain.glsl`** and **`dh_water.glsl`** are the authoritative varying derivations (basis vectors = `gbufferModelView` columns; view-space normal via NormalMatrix; `playerPos` via MV transform). `shaders/program/gbuffers_terrain.glsl` = the SOLID pass's exact varying set.

---

## 8. Project docs

- Port spec/plan: `docs/superpowers/specs/2026-05-30-nvidium-neoforge-port-design.md`, `docs/superpowers/plans/2026-05-30-nvidium-neoforge-port.md`.
- Compat spec: `docs/superpowers/specs/2026-05-30-nvidium-dh-iris-compat-design.md`.
- **Compat plan (the roadmap):** `docs/superpowers/plans/2026-05-30-nvidium-dh-iris-compat.md`. You are mid-**Phase 3** (Task 3.5 done; 3.6 shadow + 3.7 gate remain; visual correctness is the blocker).
- Iris-internals spike findings: `docs/superpowers/notes/2026-05-30-nvidium-iris-internals-findings.md`.

---

## 9. Remaining work (plan phases)

1. **Phase 3 visual correctness (BLOCKER — start here):** fix the shredding/dark/reflective near terrain. Use the `flat` debug mode to bisect depth/FBO vs shading first (§5). Likely involves `IrisGbufferBinder` depth/FBO handling and/or `mat`/normal/DRAWBUFFERS in `IrisVaryingMapper`/`IrisProgramBridge`.
2. **Phase 3.6 — shadow pass** (deferred; structure is pass-keyed so it slots in).
3. **Phase 4 — Colorwheel coexistence:** add Colorwheel + Create/Flywheel to dev runtime; verify Create contraptions render under shaders with Nvidium active (GL-state restoration in `IrisGbufferBinder.endPass` is the protection).
4. **Phase 5 — all three** (DH + Iris + Nvidium in one frame; stretch).
5. `superpowers:finishing-a-development-branch`.

**Success criteria (hard):** at least one of **Complementary Unbound** or **BSL v10.1.3** renders correctly through Nvidium + **Colorwheel** works. If Unbound proves too hard, **BSL is the alternate target** — worth trying BSL early if Unbound's deferred material model is the blocker. Fallback floor is mandatory (never crash/regress).

---

## 10. Gotchas / notes

- `clientAdditionalRuntimeClasspath` ≠ mod-loaded in dev; use `implementation`.
- Iris logs `Unknown variable: BIOME_PALE_GARDEN` (caught WARN) — pre-existing Iris-1.8.12-vs-newer-biome quirk in the pack's custom uniforms; **harmless, not ours.**
- The `cable_facades/mekanism/...` `Error loading class` mixin WARNs are Iris's compat mixins for absent mods — harmless dev noise.
- The `dist/nvidium/*.jar` is tracked and **churns on every build** — expected; commit it at phase milestones, ignore the diff otherwise.
- Version-pinned compat (mod internals): Iris `1.8.12`, Sodium `0.6.13`, DH `3.0.2-b`, Colorwheel `1.2.4`. Changing any may break the accessor mixins.
- All Iris/DH access is guarded (`ModList.isLoaded` / try-catch → fallback). Keep it that way: any new failure path must degrade to the floor, never crash.
- `IrisProgramBridge` builds programs lazily; `supportsActivePack()` caches per pipeline instance — if you change generation logic, a shaderpack reload (re-select the pack) rebuilds.
