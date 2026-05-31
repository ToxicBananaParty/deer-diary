# shader-02-optimizations — running log of zero-visual-change efficiency edits

The editable work copy (`shader-tuning/work/…`) is gitignored (license-safe), so **this file is the
tracked record** of every change made to it. Each entry: what changed, where, why output is identical,
the measured result, and status. Mandate: **identical visual output**, efficiency only — no quality/sample/
resolution reductions, no feature disabling. Colored Lighting stays pixel-identical.

Method per change: edit work copy → `build-pack.ps1` → Ryan A/B (`-tuned` vs stock: FPS avg + 1%-low at a
fixed scene + visual identity check) → keep if faster AND visually identical, else revert.

---

## Vetting note — parallel scout round (2026-05-30)

Three analysts swept Colored Lighting / Reflections / Atmospherics for identical-output wins. **Most proposals
failed vetting** and were rejected (recorded so we don't re-chase them):

- "Cache `GetVoxelVolume(newPos)`" in the leak-fix filter — **misread**; leak-check fetches different
  (axis-neighbor) voxels, no redundancy. REJECTED.
- "Reciprocal for `/6.42`", "hoist `voxelVolumeSize-1`" — **compile-time constants**; compiler already
  folds/reciprocates. No-ops. REJECTED.
- "Eliminate dead `OPTIMIZATION_ACT_SHARED_MEMORY` branch" — it's a `#ifdef`, already absent. REJECTED.
- "`pow2(pow2(x))` → fewer mults" — already 2 mults (optimal). REJECTED.
- Reflections "redundant edgeFactor `pow2`" — the asymmetric `pow2` on `.x` only is **intentional falloff**,
  not redundancy; "fixing" it would change the look. REJECTED.
- Atmospherics "hoist cloud noise out of sample loop" — **wrong/dangerous**; `GetCloudNoise` is per-sample
  (tracePos varies). Hoisting would break clouds. REJECTED.

**Conclusion:** Colored Lighting and the reflection raymarch core are already tight at the code level — the
cost is largely inherent. The genuine survivors are loop-invariant hoists out of the raymarch loops.

---

## OPT-001 — Cloud raymarch loop-invariant hoist  [status: PENDING A/B]

**File:** `shaders/lib/atmospherics/clouds/unboundClouds.glsl` (the active cloud style for Unbound).

**Change:** Five computations inside the per-sample cloud loop used only loop-invariant inputs yet recomputed
every opaque sample. Hoisted them to just before the loop:
- `cloudSkyColor = GetSky(VdotU, VdotS, dither, …)` (+ `ATM_COLOR_MULTS` scale) — the expensive one.
- `pow(abs(VdotSM1M), 90.0)` → `cloudVdotSPow90`.
- `skyMult1`/`skyMult2` → `cloudSkyMult1`/`cloudSkyMult2`; `pow2(1.0 - maxBlindnessDarkness)` → `cloudBlindMult`.

**Why output is identical:** every hoisted expression depends only on values fixed before the loop
(`VdotU/VdotS/dither/skyFade/sunVisibility2/nightFactor/VdotSM1M/maxBlindnessDarkness`), none mutated inside
the loop. Same inputs → same value each iteration; computing once and reusing is bit-identical (no
reassociation). Only behavioral delta: in a no-cloud-hit pixel the hoisted `GetSky` runs once where it
previously ran zero times — negligible, and the *output* is unchanged (value only consumed inside the hit
branch).

**Why it should help:** in cloudy views `GetSky` (sky-color: trig/pow/mixing) + `pow(x,90)` ran once per
opaque cloud sample before alpha saturates — multiple times per cloud pixel. Now once. Compilers often won't
hoist a function call like `GetSky` themselves. Helps exactly the cloud-heavy / atmospheric scenes Ryan
flagged; no effect on particle/Create-only scenes.

**Result:** _pending — Ryan to A/B at a cloudy scene (FPS stock vs -tuned + confirm clouds identical)._

---

## Deferred candidates (verify before implementing)

- **Reflection projection-matrix sparsity** (`reflections.glsl:106,110`) — replace the two per-iteration
  `gbufferProjection`/`Inverse` mat4 mults with the sparse perspective terms. Real but modest (loop is partly
  depth-fetch-bound; GPU does mat4×vec4 as 4 FMAs). **Blocked on verifying** TAA jitter isn't baked into
  `gbufferProjection` (Complementary applies jitter to `gl_Position` in the vertex stage, likely leaving the
  uniform clean — must confirm before trusting). If wrong → reflection wobble.
- **Reflection dither-factor hoist** (`reflections.glsl:121`, `0.95 + 0.1*dither`) — safe, tiny.
- More volumetric-light loop-invariant hoists (`volumetricLight.glsl`) — pending a closer read.
