# shader-01-audit — ComplementaryUnbound r5.7.1 (Deer Diary, this stack)

Phase 1 research + baseline. Every claim cites an on-disk path. No GLSL edited.
Pack = vanilla `ComplementaryUnbound_r5.7.1.zip` (no Euphoria). License = Complementary License 1.6.
Citations into `…/special-sodium-modernport/claude-reference/**` are read-only reference source.

Research streams (Tasks 1–6) were run by parallel read-only subagents; the controller spot-checked the
decision-critical citations (license clause, buffer formats, Colorwheel fallback, compute support) against
source — all confirmed. Items not directly re-verified are marked **[reported]**; inferences not proven from
source are marked **[hypothesis]**.

---

## 0. Headline findings (synthesis)

1. **Distribution is effectively off the table — local-only is forced (high confidence).** Complementary
   License 1.6 §1.3(d) requires any redistributed *Modified Pack* to "look noticeably different from the
   Original Pack… regardless of the setting or variable changes" (`License.txt:73-76`). Our entire goal is
   **zero perceptible visual change**, which structurally cannot satisfy that clause. §1.2(c) separately bars
   shipping a *modified* pack via the modpack-dependency path. So a tuned build cannot be bundled into the
   published `deer-diary` pack within the license. Local personal use isn't explicitly granted either, but
   §3.2 acknowledges discretionary non-enforcement of harmless cases — personal local tuning is low-risk.
   **Net: this project is inherently local-only.** (Full analysis §6.)

2. **Top per-frame cost suspects to measure (ranked by a-priori suspicion, pending §8 numbers):**
   shadowcomp colored-lighting floodfill compute (`COLORED_LIGHTING=128`) · the shadow pass ×
   shadow-distance interaction · volumetric lightshafts (composite1) · SSR/reflections (composite + reflection
   lib) · SSAO (deferred1) · bloom pyramid (composite4/5). Details + hypotheses §2.

3. **Candidate #1 (the spec's smoking gun) survives as a hypothesis, not a fact.** Pack runs
   `shadowDistance=160` (Ryan's value) while `iris.properties maxShadowRenderDistance=32`. The shaders use
   `shadowDistance` to size the shadow **projection/frustum** and bias (`common.glsl:19,680`). IF Iris caps
   the chunks actually rasterized into the shadow map at ~32 blocks, the 160-block projection is mostly empty
   → shadow detail is spread thin and/or work is wasted, and shrinking `shadowDistance` toward the real cap
   could be **free and invisible** (possibly a quality *gain* in the visible range). **Unproven:** the exact
   Iris `maxShadowRenderDistance` semantics were not confirmed from Iris source — Phase 2 must verify both the
   Iris behavior (read `iris/shadows/**`) and the visual effect empirically before touching it. **[hypothesis]**

4. **Three couplings are load-bearing and constrain every edit** (§5): Colorwheel (Create-under-shaders) —
   keep all `clrwl_*` files present or it drops to fallback mode and Create stops rendering; preserve OIT on
   colortex4/6/8. Distant Horizons — don't redefine `dhProjection*` or the DH vertex inputs / `DH_BLOCK_*`
   IDs. Sable — only touches `dynamic_sublevel/*` shaders + a Java-side Flywheel light store, so it does
   **not** override the main terrain/water/shadow passes (low risk), but don't break gbuffer light inputs.

5. **Capability boundaries are now known:** our Iris build **does** support compute shaders, SSBOs, custom
   images, per-buffer blending, and a rich uniform set (§3) — so the colored-lighting voxel path is fully in
   play. RTX hardware (RT cores / DLSS / tensor) is **unreachable** from OpenGL Iris (§4); realistic GPU wins
   are GL4.6/DSA paths and cutting wasted work, not ray tracing.

---

## 1. Pack anatomy & settings

### 1.1 Pass I/O (world0 canonical) — `shaders/program/*.glsl`, `shaders/world0/*`

Deferred pipeline: gbuffers → `shadow`+`shadowcomp` → `deferred1` → `composite`…`composite7` (composite2 is an
unused stub, `composite2.glsl:25`) → `final`; plus `dh_terrain`/`dh_water` and `clrwl_*`. Selected passes
(outputs = DRAWBUFFERS colortexN; conditional buffers in parens):

| Pass | Type | Outputs | Key inputs |
|---|---|---|---|
| `shadow` | raster | `0`; `01` if `COLORED_LIGHTING>0` (`shadow.glsl:190,198`) | tex, normals, specular, shadowtex0/1 |
| `shadowcomp` | **compute** | image3D: `floodfill_img`, `floodfill_img_copy` (`shadowcomp.glsl:149-150`) | voxel_sampler(usampler3D), floodfill_sampler(s) (`shadowcomp.glsl:37-38`) |
| `gbuffers_terrain` | raster | `06` (`064` if `BLOCK_REFLECT_QUALITY>=2 && RP_MODE!=0`, `gbuffers_terrain.glsl:368,373`) | tex, normals, specular, noisetex, shadowtex/color |
| `gbuffers_water` | raster | `03`/`036`/`03648` (`gbuffers_water.glsl:295-309`) | tex, normals, specular, colortex3, depthtex0/1, shadow* |
| `deferred1` | raster | `05` (`054` if reflect+RP, `deferred1.glsl:335,341`) | colortex0/4/6, depthtex0, noisetex, shadow* (`deferred1.glsl:149,202,225`) |
| `composite` | raster | `7` (`71` cond., `composite.glsl:202,207`) | colortex0/1/4/6/7, depthtex0/1, noisetex |
| `composite1` | raster | `0` (`05` if `LIGHTSHAFT_QUALI>0`, `composite1.glsl:285,295`) | colortex0/3/4/5/7, depthtex0/1, noisetex, shadow* |
| `composite3` | raster | `0` (`composite3.glsl:154`); DOF reads depthtex if `WORLD_BLUR>0` | colortex0 |
| `composite4` | raster | `3` (`30` if motion blur, `composite4.glsl:151,155`) | colortex0 (bloom tiles), depthtex1 |
| `composite5` | raster | `3` (`composite5.glsl:231`) — tonemap/bloom/lensflare | colortex0/3/5/6, depthtex0, noisetex |
| `composite6` | raster | `32` (`composite6.glsl:43`) — TAA | colortex3, depthtex1 if TAA |
| `composite7` | raster | `3` (`composite7.glsl:35`) — FXAA; **skipped if `FXAA_DEFINE==-1`** | colortex3 |
| `final` | raster | `0` (`final.glsl:156`) — sharpen/chroma/distort | colortex3, noisetex |
| `dh_terrain` | raster | `0` (`dh_terrain.glsl:124`) | tex, dhDepthTex, shadow* |
| `dh_water` | raster | `0`/`048` (`dh_water.glsl:177,181`) | tex, colortex0/5, dhDepthTex, shadow* |

### 1.2 Buffer formats — `shaders/lib/pipelineSettings.glsl:2-21` (✅ verified)

| Buffer | Format | bpp | Role | Cleared? / res |
|---|---|---|---|---|
| colortex0 | R11F_G11F_B10F | 32 | main HDR color | clear; full-res |
| colortex1 | RGB8_SNORM | 24 | half-res normals (TAA/reflect) | no-clear; **half-res** |
| colortex2 | RGB16F | 48 | TAA history | no-clear; persistent |
| colortex3 | RGBA8 | 32 | cloud/water → bloom → final LDR | clear; multipurpose |
| colortex4 | RGBA8_SNORM | 32 | world normals + reflect strength | no-clear; **half-res**; OIT |
| colortex5 | RGBA8 | 32 | scene-for-water-reflect / VL factor | no-clear |
| colortex6 | RGB8 | 24 | smoothnessD/materialMask/skyLight | clear; OIT |
| colortex7 | RGBA16F | 64 | reflection temporal + prev depth | no-clear; **half-res** |
| colortex8 | RGBA16F | 64 | SSR-for-WSR / topmost translucent opacity | clear; OIT |

`REFLECTION_RES=0.5` → colortex1/4/7 are quarter-pixel-count. colortex4/6/8 are OIT targets (see §5).

### 1.3 Profiles — `shaders/shaders.properties:2-8`

POTATO→ULTRA scale `SHADOW_QUALITY` (-1..4), `shadowDistance` (64..256), `LIGHTSHAFT_QUALI` (0..3),
`SSAO_QUALI` (2..2), `WATER/BLOCK_REFLECT_QUALITY`, `COLORED_LIGHTING` (0..512), `WORLD_SPACE_REFLECTIONS`
(-1/1), `ENTITY_SHADOW`, `DETAIL_QUALITY`, `CLOUD_QUALITY`, `ANISOTROPIC_FILTER`, `FXAA_DEFINE`. (Ryan is on
"Custom", not a named profile.)

### 1.4 Cost-relevant options — `shaders/lib/common.glsl` (allowed-value lists) **[reported]**

`SHADOW_QUALITY[-1..5]` (`:18`; -1 disables shadow pass) · `shadowDistance` list incl 160 (`:19`) ·
`LIGHTSHAFT_QUALI[0..4]` (`:24`; 0=off) · `SSAO_QUALI[0,2,3]` (`:20`; 0=off) · `WORLD_SPACE_REFLECTIONS[-1,1]`
(`:31`; needs `COLORED_LIGHTING>0`) · `WATER_REFLECT_QUALITY[-1..2]` (`:25`) · `BLOCK_REFLECT_QUALITY[0,1,3]`
(`:26`) · `COLORED_LIGHTING[0,128..1024]` (`:30`; 0 disables shadowcomp) · `ENTITY_SHADOW[-1,1,2]` (`:28`) ·
`BLOOM_ENABLED[-1,1]` (`:137`) · `TAA_MODE[0,1]` (`:149`) · `FXAA_DEFINE[-1,1]` (`:21`; -1 skips composite7) ·
`POM_QUALITY[16..512]` (`:205`) · `POM_DISTANCE` (`:206`) · `DETAIL_QUALITY[0,2,3]` (`:22`) ·
`CLOUD_QUALITY[0..3]` (`:23`).

### 1.5 Ryan's 9 tuned options → effect (**[reported]**, `path:line` per row)

| Option (value) | Consumed at | Gates |
|---|---|---|
| `ATM_FOG_MULT=0.50` | `lib/atmospherics/fog/mainFog.glsl:131` | fog density scale (0.50 = min); pure arithmetic, no branch |
| `AURORA_CONDITION=4` | `lib/atmospherics/auroraBorealis.glsl:11` | aurora visible in snowy OR dark-moon; sample loop runs when visibility>0 (`:15`) |
| `BLOCK_REFLECT_QUALITY=1` | `common.glsl:494-498`; `gbuffers_terrain.glsl:372`; `composite.glsl:206` | =1 → LIGHT_HIGHLIGHT only, **no PBR_REFLECTIONS**; skips colortex4 normal write + composite SSR temporal |
| `COLORED_LIGHTING=128` | `shaders.properties:152-155` | voxel grid 128×64×128; floodfill images + SSBO (~tens of MB); shadowcomp 16×8×16 workgroups |
| `NIGHT_NEBULAE=1` | `deferred1.glsl:116`; `clouds/mainClouds.glsl:135`; `gbuffers_water.glsl:99` | night nebula sampling in sky/cloud/reflection bg |
| `NIGHT_STAR_AMOUNT=3` | `lib/atmospherics/stars.glsl:34-36` | star threshold/brightness; no branch cost |
| `WATER_REFLECT_QUALITY=1` | `lib/materials/materialMethods/reflections.glsl:59…`; `ggx.glsl:44` | SSR path on, no WSR, **no GGX specular**; avoids colortex5 scene-copy of value 2 |
| `WAVING_LEAVES=false` | `wavingBlocks.glsl:138`; `common.glsl:222,612` | drops leaves/vines vertex displacement (vertex-only cost) |
| `shadowDistance=160` | `common.glsl:19,680`; `lightVoxelization.glsl:10`; `unboundClouds.glsl:153` | shadow frustum radius=160, bias `1-25.6/160=0.84`; ACT radius `min(128,320)=128` (matches COLORED_LIGHTING grid). Iris cap=32 → see §0.3 |

---

## 2. Cost-suspect passes (GLSL) — overworld hot path **[reported]**

**shadowcomp.csh (colored-lighting floodfill)** — multi-pass flood-fill propagating voxel light into two
double-buffered 3D images. Dispatch scales with `COLORED_LIGHTING`: =128 → 16×8×16 workgroups
(`shadowcomp.csh:15-30`); per-invocation 6 neighbor samples + interpolation (`shadowcomp.glsl:54-63`); two
`imageStore` per voxel/frame (`:149-150`). Has built-in throttles `OPTIMIZATION_ACT_HALF_RATE_SPREADING` and
`OPTIMIZATION_ACT_BEHIND_PLAYER`. `[H]` many invocations process air/never-sampled voxels — measure whether
half-rate is on and whether the grid is oversized for our view distance (`shadowcomp.csh:15`).

**shadow pass** — caustics/material handling for shadow casters; light-shaft color prepass into `01`
(`shadow.glsl:191-200`). Geometry extent is Iris-driven. `[H1]` projection sized to 160 but casters possibly
capped at 32 by Iris → wasted/over-spread shadow map (`common.glsl:19,680`) — **verify Iris side first**.

**deferred1 — SSAO** — `SSAO_QUALI=2` → 4 samples/pixel (`deferred1.glsl:54-102`), 2 depth fetches each,
full-res. `[H]` 4-sample Poisson is noisy/low-stratification; a better-distributed 4-sample pattern could hold
quality without raising count (`deferred1.glsl:64-96`).

**composite1 — volumetric lightshafts** — raymarch through shadow map; `LIGHTSHAFT_QUALI=2` → 10 (night)/20
(day) samples, **doubled if `!TAA`** (`lib/atmospherics/volumetricLight.glsl:76-88`). `[H]` near-zero
contribution at low sun visibility still runs full loop (`volumetricLight.glsl:47-60,141`).

**reflections (composite + reflections.glsl)** — SSR raymarch: no-voxel path 30 samples + 6 refine; voxel path
38 + 10 (`lib/materials/materialMethods/reflections.glsl:93-97`). Ryan: `WATER_REFLECT_QUALITY=1`,
`BLOCK_REFLECT_QUALITY=1` → lighter path. `[H]` refinement precision below TAA reprojection threshold may be
invisible (`reflections.glsl:93-123`).

**composite4/5 — bloom** — 7-LOD pyramid, 49-tap per tile (`composite4.glsl:69-84`); tonemap+lensflare in
composite5. `[H]` top LODs cheap on a 4090 but bandwidth-heavy at high res; verify if any tile is invisible at
current bloom strength.

**final** — sharpening 4-tap, optional chroma/distortion (`final.glsl:68`), full-res.

> All `[H]` items are hypotheses for Phase-2 measurement, NOT changes. Ranking by *measured* cost is §9.

---

## 3. Iris 1.8.13 feature surface — `…/claude-reference/Iris-1.21.1/common/src/main/java/net/irisshaders/iris/` **[reported]**

**Directives:** comment `DRAWBUFFERS`/`RENDERTARGETS` (`CommentDirectiveParser.java`, `CommentDirective.java:34-36`;
last-wins, `ProgramDirectives.java:149-161`); `const` ints/floats/vec/ivec3/bool (`ConstDirectiveParser.java:62-84`);
render-target formats `const int colortexNFormat`, `colortexNClear`, `colortexNClearColor`, `MipmapEnabled`,
legacy `GAUX4FORMAT` (`PackRenderTargetDirectives.java:75-124`); pack consts `noiseTextureResolution`,
`sunPathRotation`, `ambientOcclusionLevel`, `wetness/drynessHalflife`, etc. (`PackDirectives.java:258-282`);
max 16 color buffers (`IrisLimits.java:11`); compute workgroup directives `…WorkGroups`/`…WorkGroupsRelative`
(`ComputeDirectiveParser.java`).

**Feature flags** (`FeatureFlags.java:11-23`): `SEPARATE_HARDWARE_SAMPLERS`, `HIGHER_SHADOWCOLOR`,
**`CUSTOM_IMAGES`**, **`PER_BUFFER_BLENDING`**, **`COMPUTE_SHADERS`**, `TESSELLATION_SHADERS`,
`ENTITY_TRANSLUCENT`, `REVERSED_CULLING`, `BLOCK_EMISSION_ATTRIBUTE`, `CAN_DISABLE_WEATHER`, **`SSBO`** — all
the flags the colored-lighting voxel path needs are available.

**Uniforms:** large set across CameraUniforms/Viewport/WorldTime/SystemTime/Biome/Celestial/Matrix/IdMap/Fog/
IrisExclusive + a BSL/Complementary compat shim `HardcodedCustomUniforms.java:25-91` (`timeAngle`,
`timeBrightness`, `shadowFade`, `rainStrengthS`, `isSnowy`, `day/night/dawnDusk`, …). Custom uniforms via
`variable./uniform.<type>.<name>` in shaders.properties (`ShaderProperties.java:572-589`; types bool/float/int/vec2-4).

**Compute support: YES (✅ corroborated).** `ProgramBuilder.beginCompute()` compiles `GL_COMPUTE_SHADER`
(`ProgramBuilder.java:70-84`); `ComputeProgram` reads workgroup size + dispatches with memory barriers
(`ComputeProgram.java`, `CompositeRenderer.java:253-275`); loader resolves `<name>.csh` and `_a`–`_z` variants
(`ProgramSet.java:150-211`); shadow computes via `ShadowCompositeRenderer.java:192`. (The pack ships 3 `.csh`
and loads cleanly — independent corroboration.)

**stareval / unknown-identifier boundary** (`CustomUniforms.java`): parse failure → warn + drop (`:324-326`);
resolve failure → warn `"Failed to resolve uniform <name>"` + drop (`:70-73`); **unknown at eval →
`RuntimeException("Unknown variable: "+name)`** (`:287-296`); circular refs → pipeline fails (`:166-172`).
`BIOME_PALE_GARDEN` is absent from `BiomeCategories` enum → it's a **tolerated warning**, not a pipeline
failure. **Lesson for Phase 2:** referencing an undefined identifier is usually warn-and-drop, but don't rely
on it; circular custom-uniform deps hard-fail.

---

## 4. Sodium 0.8 shader surface **[reported]**

**Terrain vertex format (compact, 20 bytes — `CompactChunkVertex.java:14-19`)** exposes to shaders:
position (20-bit quantized XYZ), color (AO-premultiplied ARGB, `:61`), texture UV (centroid-biased, `:54-56`),
and packed light+material+section index (lightmap, 8-bit material idx, 8-bit section Y — `:116-120`). **Iris
mixins add** per-vertex blockId override, blockEmission, renderType (fluid flag), and local block position
(`MixinChunkVertex.java:10-56`, `MixinBlockRenderer.java:62-84`). **NOT exposed:** per-vertex smooth
normals/tangents, separate midTexCoord/midBlock, legacy `mc_Entity`/`mc_MidTexCoord` (Iris uses blockId +
localPos instead) — **[internal-only]**.

**Translucency sorting** (`SortBehavior.java`): OFF/STATIC(BSP)/DYNAMIC; transparent to shaders (Sodium
reorders before upload; shaders can't customize or read sort metadata).

**Cost removed vs older Sodium** (from `…/claude-audit/00-master-audit.md`): `SectionRenderDataUnsafe`
64→48 bytes, indexed-rendering option — **internal CPU/draw wins, no shader-observable surface change**. So
Sodium 0.8 mostly helps terrain CPU time (already cashed in by the backport), not a new shader lever.

**RTX reality check:** Iris is OpenGL — RT cores / DLSS / tensor / OptiX are **unreachable**. Reachable levers:
OpenGL 4.6 core (compute, SSBO, tessellation, bindless if present; `IrisRenderSystem.java`), DSA (used
internally, not a shader lever), and `GL_NV_*` GLSL extensions **only if explicitly opted-in** (parse-fail on
non-NVIDIA, no fallback — high portability risk; not worth it for a personal pack unless a specific win
appears). **No ray tracing. No DLSS.**

---

## 5. Coupling invariants (Colorwheel + DH + Sable)

**Colorwheel (Create-under-shaders) — load-bearing.** Detection is **file-presence based**: if the pack's
`clrwl_*` programs exist, Colorwheel uses them; if absent, it sets `colorwheel$isFallbackMode=true` and Create
stops getting the instanced path (`ClrwlBackend.java:47-51`, `ProgramSetAccessor.colorwheel$isFallbackMode()`
✅ verified). `colorwheel.properties:8-25` (✅ verified) declares **OIT** with `oit=true`, coefficient ranks,
and frontmost targets on **colortex6 (RGB8), colortex4 (RGBA8_SNORM), colortex8 (RGBA16F)**, plus
`blend.clrwl_gbuffers_translucent.colortex4=off` / `colortex8=off`. **Must-preserve:** keep all
`clrwl_gbuffers{,_translucent}` + `clrwl_shadow` files present; don't change the OIT buffer
roles/formats on colortex4/6/8; keep the `clrwl_*` hook call sites in the gbuffer programs.
**[hypothesis]** that editing (not deleting) clrwl files is clobber-safe — **verify empirically** that Create
still renders after a pack edit (the agent's "won't clobber" claim was not pinned to a precise source line).

**Distant Horizons.** `dh_terrain`/`dh_water` consume `dhProjection`/`dhProjectionInverse` (injected by DH at
runtime; **don't redefine** — `dh_terrain.glsl:36-37`), a DH-supplied vertex layout (`mat`, `lmCoord`, dir
vecs, normal, playerPos, glColor — **don't re-declare**), `DH_BLOCK_*` material IDs, and single-output
`/* DRAWBUFFERS:0 */` (`dh_terrain.glsl:124-125`). `DH_TERRAIN`/`DH_WATER` defines gate-out conflicting
features in `common.glsl:32`. **[reported]** exact `dhProjection` origin not found in pack source (DH-injected).

**Sable.** Overrides only `dynamic_sublevel/*` shaders (name-filtered, `FancySubLevelShaderProcessor.java:28-40`)
and refreshes matrices on Iris's ExtendedShader (`ExtendedShaderMixin.java:47-55`); its light-storage
replacement is a **Java-side** Flywheel `LightStorage` extension (`SableFlywheelLightStorage.java`). **It does
NOT modify the main terrain/water/shadow/clrwl/dh passes** — low risk. Don't break vanilla gbuffer light
inputs; don't redefine Sable's sublevel-only uniforms.

---

## 6. License & distribution — `License.txt` (Complementary License 1.6) ✅ verified

**Verbatim:** §0.2 — "Normal Usage… NOT considered Normal Usage: a. Redistributing (parts of) The Pack;
b. Modifying code of The Pack" (`:9-14`). §1.3 redistribution-as-Modified-Pack conditions (`:58-79`), incl.
**(d) "must look noticeably different from the Original Pack in multiple common gameplay scenarios that must
include daytime overworld visuals, regardless of the setting or variable changes"** (`:73-76`). §1.2(c) — when
adding to a modpack, "contents… must not be modified in any way, including simple variable changes" (`:52`).
§2.1 — anything not explicitly granted is "All Rights Reserved" (`:83-86`). §3.2 — vendor "reserves its rights
to not take action against cases it deems harmless" (`:99-101`).

**LOCAL personal use:** not explicitly granted, but no clause prohibits private never-distributed
modification, and §3.2 signals discretionary non-enforcement → **low practical risk; proceed local-only.**

**DISTRIBUTION into published deer-diary:** **NOT viable for this project.** §1.2(c) bars the unmodified-
dependency path for a modified pack; §1.3(d) requires a redistributed Modified Pack to look *noticeably
different* — which a zero-visual-change efficiency tune cannot satisfy by construction. The only routes are
(a) ship stock Complementary as an external dependency with no edits, or (b) ask Complementary Development for
explicit permission. **Ryan's call, but the default and recommended position is local-only** (matches the
spec's win condition anyway).

---

## 7. Measurement methodology        <!-- Task 7 — PENDING: needs Nsight feasibility + fixed scenes from Ryan -->
*(Not started. See plan Task 7: Nsight-vs-fallback decision, fixed benchmark scenes, diff.py.)*

## 8. Baseline (stock pack)          <!-- Task 8 — PENDING: needs Ryan to run the game -->
*(Not started. See plan Task 8: FPS + per-pass cost at fixed scenes on stock.)*

## 9. Ranked cost list + Phase 2 candidates  <!-- Task 9 — PENDING: needs §8 measurements -->
*(Not started. Synthesis of §2 hypotheses against §8 measured cost; gated on Ryan's scope sign-off.)*
