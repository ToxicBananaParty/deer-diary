# Design — Custom ComplementaryUnbound efficiency tune (Deer Diary)

**Status:** approved design / pre-implementation. Spec for the brainstorming → writing-plans handoff.
**Date:** 2026-05-30
**Stack:** RTX 4090 / Ryzen 9 7950X3D / Windows 11 25H2 / MC 1.21.1 NeoForge 21.1.228, with our
custom **Sodium 0.8.12-SNAPSHOT** backport and **Iris 1.8.13-snapshot-local**.
**Supersedes assumptions in:** `Custom Mods/src/special-sodium-modernport/claude-audit/shader-optimization-onboarding.md`
(corrections in §1).

---

## 0. Mission

Produce a **personal, local build of `ComplementaryUnbound_r5.7.1`** tuned to this exact machine and
modpack, that claws back shader frame-time the same way the Sodium swap clawed back terrain time —
**by removing inefficiency, not by reducing visual quality.**

Ryan's framing (verbatim intent): *not* "this is an acceptable visual loss to gain FPS," but "this
algorithm is inefficient / wasting VRAM / wasting cycles — fix that." Every win must be free of any
perceptible visual change.

## 1. Current ground truth (corrects the onboarding doc)

The onboarding doc was written from a **stale launch log**. Verified on-disk state 2026-05-30:

| Fact | Verified value | Source |
|---|---|---|
| Active shaderpack | **`ComplementaryUnbound_r5.7.1.zip`** (vanilla Unbound) | `config/iris.properties` → `shaderPack=` |
| EuphoriaPatches | **NOT installed** — no Euphoria jar in `mods/` | `mods/` listing |
| Supplemental Patches | **NOT used** | confirmed by Ryan + iris.properties |
| Colorwheel | `colorwheel-neoforge-1.2.4` + `colorwheel_patcher-neoforge-1.0.5` installed | `mods/` listing |
| Colorwheel in-pack | `clrwl_gbuffers`, `clrwl_gbuffers_translucent`, `clrwl_shadow`, `colorwheel.properties` **already baked into the zip** | zip inventory |
| DH integration | `dh_terrain`, `dh_water` passes present in the pack | zip inventory |
| Compute shaders | 3 `.csh` present — pack already uses compute | zip inventory |
| Ryan's tuned options | 9 changed options saved today (ATM_FOG_MULT, AURORA_CONDITION, BLOCK_REFLECT_QUALITY, COLORED_LIGHTING=128, NIGHT_NEBULAE, NIGHT_STAR_AMOUNT, WATER_REFLECT_QUALITY, WAVING_LEAVES=false, shadowDistance=160) | `ComplementaryUnbound_r5.7.1.zip.txt` |
| Shadow distance mismatch | pack `shadowDistance=160.0` vs `iris.properties maxShadowRenderDistance=32` | both files — **candidate inefficiency #1, to verify** |

**Consequences of the correction:** no EuphoriaPatches layer to reverse-engineer, and no EuphoriaPatches
licensing tangle (it was the specially-distributed, restrictive component). We edit clean upstream
Complementary GLSL. The Colorwheel/Create and DH couplings remain load-bearing.

**Pack pipeline shape (deferred):** per-dimension program sets (`world0` / `world1` / `world-1`,
67 programs each): gbuffers_* → `shadow` + `shadowcomp` → `deferred1` → `composite`…`composite7`
(sparse numbering) → `final`. Properties manifests: `shaders.properties`, `block.properties`,
`colorwheel.properties`, `dimension.properties`, `entity.properties`, `item.properties`.

## 2. Success criteria (win conditions)

With shaders on, in-world, on this stack:

1. **Floor:** never below **100 FPS**.
2. **Trend:** toward **120 FPS** typical, with dips bottoming around the 100 floor.
3. **Visual gate:** **zero perceptible change** vs. stock at the fixed benchmark scenes (objective
   pixel-diff, §4), preserving Ryan's 9 tuned options.

Baseline today: 144 FPS shaders-off (stable); shaders-on struggles to hold 90.

All three are desirable; #1 is the hard commitment, #2 the stretch. We treat #3 as a non-negotiable
constraint on *how* #1/#2 are achieved — gains come only from efficiency, never from cutting an
effect Ryan would notice.

## 3. Invariants (must not break)

- **Local-only.** No `publish-all.bat` / `publish.bat` / `packwiz-publish` / `prism_sync`. The live
  instance is in an experimental swapped state; shipping it would be bad. Distribution is a separate,
  later, license-gated decision (Complementary's own license must be read first; default to local-only).
- **Back up first.** Stash the pristine `ComplementaryUnbound_r5.7.1.zip` outside `shaderpacks/`
  before any edit (Ryan's standing "with backups!" rule).
- **Don't regress what works:** Create-under-shaders (Colorwheel passes), Distant Horizons integration,
  Sable's shader overrides / light-storage replacement, and Ryan's 9 tuned options all keep working.
- **Stay inside Iris 1.8.13's feature set.** Anything it can't parse fails like `BIOME_PALE_GARDEN`.
  An Iris-side change is a separate, coordinated rebuild from `claude-reference/Iris-1.21.1/`, not done
  casually.
- **Windows / PowerShell, absolute paths.** No Bash for builds/searches (see `windows-shell-tooling`).
  Git is **WSL git**. ASCII only in any publish-tooling `print()`.

## 4. Measurement harness (the backbone)

The "none perceptible" bar requires **objective** checks, and the operating model is collaborative:
**the agent authors analysis + candidate edits + exact run steps; Ryan executes runs on the live game
and reports numbers/screenshots; the agent diffs and decides.** The agent cannot run the GPU or see
the screen — Ryan is the instrument.

- **Fixed benchmark scenes** — locked position, time, weather, seed, render distance, and the 9 tuned
  options, so runs are comparable. Minimum set:
  - (a) clean overworld vista (sky + terrain + water + shadows in frame),
  - (b) a Create build in view **on the server** (Colorwheel/Flywheel path exercised) — singleplayer
    alone is not a sufficient test (brain lesson).
  - Consider a night scene (nebulae/stars/aurora — several of Ryan's tuned options live here).
- **No-regression check = pixel diff.** Capture before/after screenshots under identical locked state;
  diff them. "Identical or sub-threshold" is the gate; anything visibly different is reverted or reworked.
  *Caveat:* Complementary uses **TAA + other temporal effects**, so two frames at identical config are
  not byte-identical (jitter alone would trip a naive diff). The diff protocol therefore captures with
  **TAA / temporal accumulation disabled** for the comparison set (or averages N settled frames) and uses
  a **perceptual threshold**, not exact equality. This *proves* zero perceptible change rather than
  trusting eyeballs.
- **Per-pass GPU timing.**
  - **Primary:** NVIDIA Nsight Graphics for per-pass GPU time. **Feasibility check first** — confirm it
    attaches to the LWJGL/Java (`javaw`) process for our OpenGL app.
  - **Fallback (if attach fails):** disable-one-pass-and-measure — toggle/short-circuit a pass, Iris
    live-reload, read frametime delta via BetterF3. Cruder but reliable; also useful as a cross-check.
- **Repeatability:** record exact methodology (coords, time set, settings hash) in the audit doc so any
  measurement is reproducible later.

## 5. Phase 1 — research + baseline (gate before any GLSL edit)

Deliverable: `shader-tuning/claude-audit/shader-01-audit.md`. Tasks:

1. **Finish pack inventory** — extract to a read-only working copy; map each pass and its rough role;
   read the GLSL of the suspected expensive passes (shadow, deferred1, composite chain, final).
2. **Map our Iris 1.8.13 feature surface** — supported directives/uniforms/extensions, custom-uniform
   expression engine (`kroppeb.stareval`), and **compute-shader support** on this build. Source:
   `claude-reference/Iris-1.21.1/`. Cite findings; no fabrication.
3. **Map Sodium 0.8 shader-facing surface** — what the new terrain vertex/data path, translucency
   sorting, and the Iris↔Sodium 0.8 bridge expose that a shader can exploit or that removes per-frame
   cost. Source: `special-sodium-modernport/` (`render/`, compat mixins) + the real Sodium 0.8 changelog.
   Cite each feature's origin.
4. **Confirm Colorwheel + DH coupling** — how the `clrwl_*` passes / `colorwheel.properties` integrate,
   whether `colorwheel_patcher` re-patches on launch or detects "already patched," and how `dh_terrain`
   / `dh_water` hook in. Establish what an edited pack must preserve to keep Create-under-shaders and DH.
5. **License check** — read Complementary's `LICENSE`/credits; state the local-only-vs-distributable
   position explicitly.
6. **Baseline capture** — FPS + per-pass GPU time (or fallback frametime) at the fixed scenes on the
   **stock** pack, plus baseline screenshots for the pixel-diff reference set.

**Gate:** Ryan reviews `shader-01-audit.md` and confirms scope before any line of GLSL changes.

## 6. Phase 2 — iterative optimization protocol

Loop, **one change-class at a time**:

1. Pick the highest-value candidate from the audit's ranked cost list.
2. Form an efficiency hypothesis ("this is redundant / over-precise / invisible at our settings").
3. Make the surgical edit in the working copy.
4. Ryan runs the fixed scenes → reports FPS delta + screenshots.
5. Pixel-diff the screenshots. **Keep** only if FPS improved AND diff is sub-threshold. Else **revert**.
6. Log the result (change, hypothesis, FPS before/after, diff verdict) in the audit trail.

The pristine stash stays untouched throughout; the working copy is always restorable.

**Candidate lever *categories* (hypotheses to verify — NOT commitments):**
- **Invisible / out-of-range work** — e.g. shadow geometry rendered to `shadowDistance=160` while Iris
  caps sampling at `maxShadowRenderDistance=32` (candidate #1).
- **Buffer format & precision right-sizing** — oversized/over-precise colortex/gbuffer formats, redundant
  buffers, unnecessary full-res where half-res is byte-identical at our settings.
- **Redundant / foldable passes** — work recomputed per-frame that could be cached; mergeable composites.
- **GL 4.6 / DSA / Sodium-0.8 data-path levers** — shedding per-frame CPU/GPU cost via features our
  stack now exposes (verified in Phase 1, cited).
- **Dead weak-GPU fallback branches** — compatibility codepaths that only add cost on a 4090.

Each lands only if it is **free *and* invisible**.

## 7. Deliverables & decision gates

- `shader-01-audit.md` (research + baseline) → **Ryan scope sign-off.**
- Optimization log with per-change before/after numbers + diff verdicts.
- **Ryan visual sign-off** at the fixed scenes on the final candidate.
- Explicit **local-only vs. distributable** position (license-backed), agreed with Ryan.
- Reproducible build/measure steps + before/after numbers recorded in `shader-tuning/claude-audit/`.
- Pristine pack stashed and restorable.

## 8. Work tree layout

New top-level **`shader-tuning/`** (GLSL tree, not a Gradle mod, so `Custom Mods/src/` is a poor fit):

```
shader-tuning/
  claude-audit/        # this design + shader-01-audit.md + optimization log
  stock/               # pristine ComplementaryUnbound_r5.7.1.zip stash (created in Phase 1)
  work/                # extracted working copy under edit (created in Phase 1)
  measurements/        # baseline + per-change screenshots, FPS logs, diff outputs
```

## 9. Open items / risks

- **Nsight attach to javaw** unconfirmed — fallback path defined (§4).
- **Colorwheel re-patch behavior** unconfirmed — must not break Create-under-shaders; resolved in Phase 1.4.
- **Sable interaction** — warns it does extensive shader overrides + full light-storage replacement; watch
  for edits that collide.
- **Distribution** out of scope until license is read and Ryan decides; default local-only.
- **RTX reality check** — Iris is OpenGL; RT cores / DLSS / tensor are unreachable. Wins come from GL 4.6 /
  DSA / NVIDIA GLSL extensions (parse-risk, test carefully) and spending headroom on the right work.

## 10. References

- Onboarding: `Custom Mods/src/special-sodium-modernport/claude-audit/shader-optimization-onboarding.md`
- Sodium brain: `…/special-sodium-modernport/claude-audit/00-master-audit.md` (§7.2 GPU-bound diagnosis)
- Iris source: `…/special-sodium-modernport/claude-reference/Iris-1.21.1/`
- Sodium backport: `…/special-sodium-modernport/` (`render/`, Iris compat mixins)
- Memory: `windows-shell-tooling.md`, `sodium-08-backport-project.md`,
  `complementary-shader-optimization-project.md`
