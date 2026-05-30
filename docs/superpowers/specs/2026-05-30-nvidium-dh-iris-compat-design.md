# Nvidium ↔ Distant Horizons + Iris Shaders Compatibility — Design

**Date:** 2026-05-30
**Status:** Approved (design); pending spec review
**Builds on:** the NeoForge port of Nvidium (branch `nvidium-neoforge-port`,
spec `2026-05-30-nvidium-neoforge-port-design.md`). Nvidium 0.4.1-beta10 now
runs on NeoForge 1.21.1 and renders terrain via NVIDIA mesh shaders, but
**disables itself whenever an Iris shaderpack is active** and **conflicts with
Distant Horizons** (it seizes the far-plane, fog, and render-distance culling).

## Goal

Make Nvidium coexist with the pack's other rendering mods:

1. **Distant Horizons (DH 3.0.2):** Nvidium renders near terrain; DH renders far
   LODs; they compose correctly (occlusion, fog, no z-fighting) — no shaders.
2. **Iris shaders:** Nvidium renders terrain *through* the active shaderpack so
   terrain is shaded consistently with the rest of the scene.
3. **Colorwheel:** Create contraptions still render correctly under shaders with
   Nvidium active.

All of this behind a **hard fallback floor**: if any integration is unavailable
or fails for a given configuration, Nvidium cleanly yields to the existing,
working Sodium(+Iris+DH) path and never crashes or regresses.

## Reference material (in `claude-reference/`)

- `distanthorizons-main/` — DH 3.x source (1.21.1). DH exposes a real
  integration API; Iris's own DH-compat layer is the reference implementation.
- `Iris-1.21.1/` — Iris source (1.21.1). The `compat/sodium/` mixin package is
  how Iris makes Sodium's terrain use shaderpack programs.
- `Colorwheel-1.21.1-dev/` — a Flywheel↔Iris bridge that renders custom geometry
  into Iris gbuffers with shaderpack programs via accessor-mixins into Iris
  internals. Closest existing template for Nvidium's Iris integration **and** a
  coexistence constraint.
- `sodium-1.21.1-stable/` — Sodium source (already used for the port).
- `Iris-1.18.2/` — older Iris, reference only if 1.21.1 lacks something.

## Key facts established during exploration

### Distant Horizons (officially integrable)
- DH renders LODs into its **own** FBO + `DEPTH32F` depth texture, then
  composites onto MC's main framebuffer with a screen-space shader that compares
  **DH's depth vs MC's main depth texture** (`getGlDepthTextureId()`). So once
  Nvidium's near-terrain depth is in MC's main depth buffer (it is — Nvidium
  renders during the terrain pass into the main FBO) and projections are
  consistent, DH occludes LODs behind near terrain automatically.
- DH's LOD pass uses **its own** projection (far plane
  `≈ (lodChunkDist*16 + 512) * √2`); it does not alter MC's projection. Nvidium's
  `getDepthFar`→8192 mixin changes MC's projection, which DH *captures* as
  `mcProjectionMatrix` — so the depth comparison stays consistent.
- **Overdraw:** DH discards LODs within a near-clip radius =
  `overdrawPreventionRadius (0..1) × MC.options.getEffectiveRenderDistance()`.
  There is **no API to hand DH a near-clip distance in blocks directly.** If
  Nvidium renders real chunks far past MC's reported render distance, DH must be
  told via the `overdrawPreventionRadius` config (a fraction of MC's RD), or rely
  on the depth composite to suppress overdraw.
- **Fog:** DH disables vanilla fog (`MixinFogRenderer`, unconditional at RETURN
  when `enableVanillaFog=false`) and renders its own. Nvidium *also* forces fog
  to ∞. These will fight unless one owner is chosen.
- **Public API** (`com.seibel.distanthorizons.api.DhApi`): always-available
  `DhApi.events` (bind/unbind) and `DhApi.overrides`; after
  `DhApiAfterDhInitEvent`, `DhApi.Delayed.{configs,renderProxy,worldProxy}`.
  Relevant events: `DhApiBeforeRenderSetupEvent`, `DhApiBeforeRenderPassEvent`
  (carries near/far clip + both matrices), `DhApiBeforeTextureClearEvent`,
  `DhApiBeforeApplyShaderRenderEvent` (cancelable — stops DH's own composite),
  `DhApiAfterRenderEvent`. Overrides: `IDhApiFramebuffer`, `IDhApiShaderProgram`,
  `IDhApiCullingFrustum`, `IDhApiShadowCullingFrustum`. `DhApi.Delayed.renderProxy`
  exposes `getDhDepthTextureId()`, `getDhColorTextureId()`,
  `getNearClipPlaneDistanceInBlocks(partialTicks)`.
- Iris's DH integration (the template) lives in
  `Iris-1.21.1/common/.../compat/dh/LodRendererEvents.java`: binds a custom
  `IDhApiFramebuffer` to redirect DH into Iris gbuffers, reads near/far clip from
  the pass event, disables DH fog via config, reconnects DH's depth texture,
  cancels DH's apply-shader.

### Iris (no external-renderer API — mixin-only)
- Sodium support is achieved **entirely** by Iris mixing into Sodium:
  `compat/sodium/mixin/MixinShaderChunkRenderer.redirectIrisProgram` intercepts
  Sodium's `ShaderChunkRenderer.begin`, **binds Iris's gbuffer FBO**
  (`SodiumPrograms.getFramebuffer(pass).bind()`) and **returns Iris's
  shaderpack-compiled program** (`SodiumPrograms.getProgram(pass)`) instead of
  Sodium's. `MixinRenderSectionManager` swaps in Iris's extended vertex format;
  `MixinChunkMeshBuildTask` populates Iris's per-block attributes.
- Shaderpack terrain programs come from `ProgramId.{TerrainSolid,TerrainCutout,
  Water}` and shadow `ProgramId.{ShadowSolid,ShadowCutout,ShadowWater}`,
  resolved through the `gbuffers_terrain` fallback chain. `SodiumPrograms` patches
  the GLSL (`TransformPatcher.patchSodium`), compiles + links it (binding Iris
  attributes at fixed locations), and owns the `GlProgram` + per-pass
  `GlFramebuffer`. Uniforms/samplers are set in `SodiumShader`
  (`ChunkShaderInterface`) via `CommonUniforms`/`MatrixUniforms`/`IrisSamplers`,
  sourced from `CapturedRenderingState`.
- **Shaderpacks are inseparable vertex+fragment programs.** There is no
  fragment-only link path, no public API to fetch the gbuffer FBO, the patched
  shader source, or the `IrisRenderingPipeline`. The only integration route is
  mixin/accessor into Iris internals — which is exactly what **Colorwheel** does
  (`accessors/iris/{IrisRenderingPipelineAccessor, ProgramSetAccessor,
  ProgramSourceAccessor, RenderTargetsAccessor, ShadowRenderTargetsAccessor, …}`
  + `compile/{ClrwlPipelineCompiler, IrisShaderComponent}`).
- `IrisApi.getInstance()` only exposes `isShaderPackInUse()`,
  `isRenderingShadowPass()`, config, sun-path rotation. `Iris.getPipelineManager()`
  / `Iris.getCurrentPack()` are reachable but not "public API".
- Iris's terrain vertex interface (the varyings a shaderpack `gbuffers_terrain.fsh`
  expects, populated by the vsh) includes: position, color, UV0 (atlas), UV2
  (lightmap), normal, `mc_Entity` (block id + render type), `mc_midTexCoord`,
  `at_tangent`, `at_midBlock` (+ emission). A Nvidium mesh shader pairing with the
  shaderpack fragment must emit matching `out` varyings by name/location.

## Architecture

A **render-mode arbiter** plus **three isolated, soft-dependency compat layers**.
Every layer is individually guarded; any failure degrades to the fallback floor
rather than crashing.

### Component 1 — Render-mode arbiter
**Files:** new `me/cortex/nvidium/RenderMode.java` (or fold into `Nvidium`);
refactor `sodiumCompat/IrisCheck.java`; touch `mixin/sodium/MixinRenderSectionManager`
(where `IS_ENABLED` is currently computed).

Replace the binary `IS_ENABLED = compatible && IrisCheck.checkIrisShouldDisable()`
with an explicit per-frame mode:
- `DISABLED` — not compatible HW, or force-disabled.
- `VANILLA` — Nvidium active, no shaderpack in use (today's working path).
- `SHADERS` — Nvidium active **and** the Iris integration is available **and**
  the active shaderpack is supported (resolves a usable terrain program). Else →
  fall back to Nvidium-inactive (Sodium+Iris renders) = the floor.

`IS_ENABLED` becomes `mode != DISABLED && mode != (shaders-but-unsupported)`.
The DH layer runs whenever DH is present, in both `VANILLA` and `SHADERS`.
This arbiter **is** the fallback floor; it must default to yielding on any
uncertainty.

### Component 2 — DH coexistence (`me/cortex/nvidium/compat/dh/`)
**Files:** new `NvidiumDhCompat.java` (+ small event-handler classes). Soft dep:
all DH references isolated here, guarded by `ModList.isLoaded("distanthorizons")`,
class-load-guarded so the rest of Nvidium runs without DH present.

On `DhApiAfterDhInitEvent`, bind handlers that:
- **Fog ownership:** pick one owner so Nvidium and DH don't both fight vanilla
  fog. Preferred: let DH own far fog (it already renders a fog pass); Nvidium's
  `MixinFogRenderer` ∞-override is gated to only apply when DH is *absent*.
- **Overdraw/near-clip:** set `DhApi.Delayed.configs.graphics()
  .overdrawPreventionRadius()` so DH's LOD discard radius matches Nvidium's actual
  near render radius (derived from `Nvidium.config.region_keep_distance` and MC's
  effective RD). Goal: DH draws LODs only beyond Nvidium's real chunks.
- **Depth/projection consistency:** confirm Nvidium writes near-terrain depth to
  MC's main depth buffer and that DH's `mcProjectionMatrix` capture reflects
  Nvidium's far-plane, so DH's composite occludes correctly.

**v1 = minimal coordination** (above). **Escalate** to binding an
`IDhApiFramebuffer` override (redirect DH into a Nvidium-controlled target, à la
Iris) only if hardware testing shows seam artifacts / incorrect occlusion the
minimal path can't fix.

### Component 3 — Iris shader terrain integration (`me/cortex/nvidium/compat/iris/`)
**Files:** new `NvidiumIrisCompat.java`, `IrisProgramBridge.java`,
`MeshShaderProgramBuilder.java`, accessor mixins under `mixin/iris/` (modeled on
Colorwheel's `accessors/iris/*`). Soft dep: guarded by `ModList.isLoaded("iris")`.

Responsibilities (the research-grade half):
1. **Access Iris internals** via accessor mixins (Colorwheel's set is the model):
   the active `IrisRenderingPipeline`, its `ProgramSet`/`SodiumPrograms`, the
   per-pass gbuffer `GlFramebuffer`, the shadow render targets, and the patched
   shaderpack fragment source for the terrain/shadow programs.
2. **Build a mesh-shader program per pass:** combine Nvidium's task/mesh GLSL
   (adapted to emit exactly the varyings the shaderpack terrain fragment expects)
   with the shaderpack's (patched) fragment source, linked via Nvidium's existing
   `Shader`/GL program path extended for `GL_NV_mesh_shader`. Cache per
   (pass, shaderpack, pipeline-version).
3. **Render Nvidium terrain into Iris's gbuffers** during the terrain phase:
   bind the Iris gbuffer FBO for the pass, bind the mesh-shader program, set the
   uniforms/samplers the shaderpack expects (matrices, fog, lightmap, gtexture,
   shadow maps, colortex render targets, custom uniforms — replicating
   `SodiumShader.setupState`/`IrisSamplers`/`CommonUniforms`), draw, restore MC's
   FBO afterward.
4. **Shadow pass:** when `IrisApi.isRenderingShadowPass()`, render Nvidium terrain
   into Iris's shadow framebuffer with the shadow program.
5. **Supported-pack gate:** target **Complementary Unbound (plain r5.7.1)** and
   **BSL v10.1.3**. If program construction or varying-matching fails for the
   active pack → mark unsupported, arbiter yields to the floor (no crash).

### Component 4 — Colorwheel coexistence (`me/cortex/nvidium/compat/colorwheel/`)
**Files:** likely small — a verification-and-guard concern, plus a mixin only if
needed. Soft dep: guarded by `ModList.isLoaded("colorwheel")`.

Colorwheel renders Create's Flywheel instances into Iris gbuffers using the same
Iris internals Nvidium will touch. Requirement: Nvidium's gbuffer binding +
GL-state changes must be **scoped and fully restored** so Colorwheel's pass sees
the state it expects. Primarily ensured by disciplined save/restore of FBO + GL
state in Component 3, and verified on hardware (Create contraptions render
correctly under shaders with Nvidium active). Add a targeted mixin only if a
concrete conflict surfaces.

### Component 5 — Build / dependencies
**File:** `Custom Mods/src/nvidium-1.21.1/build.gradle` (+ `gradle.properties`).
- Add **DH API** `compileOnly` (the `DhApi`; sourced from the DH maven/API jar
  for 1.21.1 NeoForge).
- Add **Colorwheel** `compileOnly` (for accessor-style coexistence / any mixin).
- Deepen the **Iris** dependency from API-only to the full Iris jar so Nvidium can
  accessor/mixin into `IrisRenderingPipeline`/`ProgramSet`/`RenderTargets`
  internals (Colorwheel's accessor mixins are the model; Iris internal classes
  are present in the published jar).
- Register the new mixin configs (`mixin/iris/*`, any `mixin/dh|colorwheel/*`) in
  `nvidium.mixins.json` (or a second client mixin config).
- Pin to the pack's exact versions: Iris 1.8.x, Sodium 0.6.13, DH 3.0.2,
  Colorwheel 1.x. Documented maintenance constraint (these mixins are
  version-coupled to mod internals).

## Success criteria

**Hard requirements (must pass on the RTX 4090):**
- **Nvidium + DH, no shaders:** near terrain + far LODs render together; correct
  occlusion at the seam; consistent fog; no z-fighting; no crash.
- **Nvidium + Iris:** terrain renders correctly and consistently shaded through
  **at least one of** Complementary Unbound (plain r5.7.1) **or** BSL v10.1.3.
- **Nvidium + Iris + Colorwheel:** Create contraptions render correctly under
  shaders with Nvidium active.
- **Fallback floor:** an unsupported shaderpack → clean yield to Sodium+Iris (no
  crash, no visual regression vs today); non-NVIDIA / unsupported HW → Nvidium
  disabled as today; DH/Iris/Colorwheel absent → Nvidium behaves as the port does.
- **No regression:** shaders off + DH off → today's `VANILLA` Nvidium path is
  unchanged.

**Stretch (best-effort, not gating):**
- All three simultaneously (Nvidium terrain + DH LODs + Iris shaders in one frame).
- The second of {Complementary Unbound, BSL}.
- EuphoriaPatches / Supplementary Complementary variants; other packs (Photon,
  VanillaPlus).

## Verification gates (hardware, RTX 4090; dev `runClient` carries the deps)

Tested in order; each is a launch-and-observe gate (no unit tests — GL rendering):
1. **Regression:** shaders off, DH off → terrain renders as before (`VANILLA`).
2. **DH coexistence:** DH on, shaders off → near terrain + LODs compose correctly.
3. **Iris (target pack):** Complementary Unbound (and/or BSL) on → terrain shaded
   correctly; compare against Sodium+Iris reference for parity.
4. **Colorwheel:** with shaders on + Nvidium on, Create contraptions render shaded.
5. **All three (stretch):** DH + Iris + Nvidium together.
6. **Fallback:** unsupported pack (e.g. EuphoriaPatches variant or Photon) →
   clean yield to Sodium+Iris, no crash; toggling Nvidium off mid-session →
   clean fallback (as in the port's gate 4).

The dev `runClient` will need Sodium + Iris + DH + Colorwheel (+ Create/Flywheel
for the Colorwheel gate) on the classpath; gates that need a specific shaderpack
require loading it in-game.

## Risks & mitigations

- **Iris integration is genuinely hard and version-coupled.** Re-linking arbitrary
  shaderpack fragment stages against a mesh-shader vertex stage is pack-specific
  (varying sets differ). *Mitigation:* bound to two named packs; Colorwheel is a
  working template; the fallback floor guarantees no regression if it fails.
- **Mesh-shader ↔ shaderpack varying mismatch.** The mesh shader must emit exactly
  what each pack's fragment reads. *Mitigation:* derive the required interface from
  the patched fragment + Iris's terrain varying set; fail safe to the floor on
  mismatch.
- **DH overdraw API gap** (no block-distance setter). *Mitigation:* drive
  `overdrawPreventionRadius` from Nvidium's render distance; rely on the depth
  composite; escalate to framebuffer override if needed.
- **Fog double-ownership.** *Mitigation:* explicit single-owner rule (DH owns far
  fog when present; Nvidium's fog mixin gated off when DH active).
- **Colorwheel state interference.** *Mitigation:* strict FBO/GL save-restore;
  hardware verification; targeted mixin only if a conflict appears.
- **Iris internal-API drift across versions.** *Mitigation:* pin versions; isolate
  all internal access behind accessor mixins so breakage is localized.

## Out of scope
- Universal shaderpack support; EuphoriaPatches/Supplementary Complementary
  variants as hard targets.
- Non-NVIDIA / pre-Turing hardware.
- A Fabric build.
- Changes to Nvidium's core mesh-shader terrain algorithm beyond what the
  shaderpack-fragment pairing requires.
- Publishing/upstreaming.

## Phasing (single spec, internally sequenced so wins land early)
1. **Mode arbiter + fallback floor** — refactor enablement into explicit modes;
   guarantees no regression and is the safety net for everything after.
2. **DH coexistence (no shaders)** — the achievable, clearly-testable win.
3. **Iris terrain integration** — the research-grade half; target Unbound/BSL.
4. **Colorwheel coexistence** — verify + guard.
5. **All-three integration** — best-effort, on top of 2–4.

Each phase is independently verifiable; the plan can stop after any phase with a
working, non-regressing result.
