# ComplementaryUnbound Efficiency Tune — Phase 1 (Research + Baseline) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce `shader-tuning/claude-audit/shader-01-audit.md` — a cited inventory of the pack, the Iris 1.8.13 / Sodium 0.8 feature surface, the Colorwheel/DH coupling invariants, the license position, and a measured baseline (FPS + per-pass GPU cost) — ending in a ranked cost list and a scope-sign-off gate, with **zero edits to any GLSL**.

**Architecture:** Set up an isolated, license-safe work tree (`shader-tuning/`) with a pristine pack stash and a read-only extracted copy. Run six independent research streams (pack anatomy, cost-suspect GLSL, Iris surface, Sodium-0.8 surface, coupling, license) that each append a cited section to the audit doc — these parallelize cleanly via Explore subagents. Then two collaborative streams (profiling-tool feasibility + methodology, then baseline capture) where **Ryan runs the live game and reports numbers/screenshots** because the agent cannot drive the GPU or see the screen. Finish by synthesizing the ranked cost list and stopping at Ryan's scope gate.

**Tech Stack:** Windows 11 / **PowerShell with absolute paths** (no Bash — see `windows-shell-tooling`). **WSL git** for commits. Iris 1.8.13 live shader reload (`R`) for the dev loop. NVIDIA Nsight Graphics (primary profiler, feasibility-gated) or in-game disable-pass-and-measure (fallback). BetterF3 frametime HUD. PowerShell `System.IO.Compression` for read-only zip inspection.

---

## Plan-format adaptation (read before executing)

This is **not** a pytest/TDD codebase. Phase 1 produces *documentation and measurements*, not code. So the usual "write failing test → implement → pass" loop is replaced by:

- **"Verification" = a concrete artifact check** — the named audit section exists, contains the required fields, and **every claim cites an exact on-disk path** (`file:line` where possible). Fabricated facts or uncited claims are failures, exactly like a failing test.
- **Collaborative tasks (7, 8)** hand Ryan an exact, copy-pasteable run recipe; the "expected output" is the **data shape** he reports back. Do not invent numbers — if Ryan hasn't run it, the value stays empty and the task is blocked.
- **No GLSL is edited in Phase 1.** Editing begins only in Phase 2, after the gate.

**Hard rules for every task:**
- PowerShell, absolute paths. Searches dispatched as **Explore** subagents (Glob/Grep/Read), never Bash.
- Reading from `Custom Mods/src/special-sodium-modernport/**` (incl. `claude-reference/`) is fine. **Never write into it and never `git add` it.** Never stage the workspace-root `.gitignore`.
- Commits use `wsl git add "<explicit path>"` — **never** `git add -A`/`.`. Only ever stage paths under `shader-tuning/` (and only the tracked ones — see §gitignore). Co-author trailer on every commit: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.

---

## File Structure

Created in Phase 1:

| Path | Responsibility | Tracked in git? |
|---|---|---|
| `shader-tuning/.gitignore` | Keep upstream GLSL + binaries + raw captures local-only until license clears | **yes** |
| `shader-tuning/stock/ComplementaryUnbound_r5.7.1.zip` | Pristine, never-edited restore source | no (gitignored) |
| `shader-tuning/stock/SHA256.txt` | Restore-integrity hash | no (gitignored) |
| `shader-tuning/work/ComplementaryUnbound_r5.7.1/` | Read-only extracted copy for research (becomes Phase 2 edit copy) | no (gitignored) |
| `shader-tuning/claude-audit/shader-01-audit.md` | **The deliverable** — built up section-by-section | **yes** |
| `shader-tuning/measurements/methodology.md` | Exact repeatable fixed-scene + capture recipe | **yes** |
| `shader-tuning/measurements/diff.py` | Perceptual no-regression check (MAE/peak on TAA-off captures) | **yes** |
| `shader-tuning/measurements/baseline/` | Baseline FPS logs (.md) + reference screenshots (.png) | `.md` yes, images no |

**Why upstream source is gitignored:** Complementary's license is unread until Task 6, and this repo has a GitHub remote. Committing the pack's GLSL would be a redistribution act we haven't cleared — mirror the FTB caution in `CLAUDE.md`. We track only our own analysis/measurement docs.

---

## Task 0: Work-tree setup + pristine stash (license-safe)

**Files:**
- Create: `shader-tuning/.gitignore`
- Create: `shader-tuning/stock/ComplementaryUnbound_r5.7.1.zip` (copied)
- Create: `shader-tuning/stock/SHA256.txt`
- Create (extract): `shader-tuning/work/ComplementaryUnbound_r5.7.1/`
- Create: `shader-tuning/claude-audit/shader-01-audit.md` (stub)

- [ ] **Step 1: Write `shader-tuning/.gitignore`**

```gitignore
# Upstream Complementary GLSL + binaries + raw captures stay LOCAL until the
# license review (Task 6) clears redistribution. Track only our own docs.
stock/
work/
measurements/**/*.png
measurements/**/*.jpg
measurements/**/*.jpeg
measurements/**/*.zip
```

- [ ] **Step 2: Stash the pristine pack and record its hash**

Run (PowerShell):
```powershell
$live = "C:\Users\Ryan-PC\AppData\Roaming\PrismLauncher\instances\Deer Diary\minecraft\shaderpacks\ComplementaryUnbound_r5.7.1.zip"
$dst  = "C:\Users\Ryan-PC\Desktop\MC Stuff\shader-tuning\stock\ComplementaryUnbound_r5.7.1.zip"
Copy-Item $live $dst
(Get-FileHash $dst -Algorithm SHA256).Hash | Out-File "C:\Users\Ryan-PC\Desktop\MC Stuff\shader-tuning\stock\SHA256.txt" -Encoding ascii
```

- [ ] **Step 3: Verify the stash is a byte-perfect copy of the live pack**

Run:
```powershell
(Get-FileHash "C:\Users\Ryan-PC\Desktop\MC Stuff\shader-tuning\stock\ComplementaryUnbound_r5.7.1.zip" -Algorithm SHA256).Hash
```
Expected (exact): `5CBF4D99BC7CE4F0DF5C15974D276E0949BF6974D48D88A77D5E307B0B9BE8AF`
Also confirm size = `522973` bytes. If the hash differs, STOP — the live pack changed since planning; reconcile with Ryan before continuing.

- [ ] **Step 4: Extract the read-only research copy**

Run:
```powershell
Expand-Archive -Path "C:\Users\Ryan-PC\Desktop\MC Stuff\shader-tuning\stock\ComplementaryUnbound_r5.7.1.zip" `
  -DestinationPath "C:\Users\Ryan-PC\Desktop\MC Stuff\shader-tuning\work\ComplementaryUnbound_r5.7.1" -Force
(Get-ChildItem "C:\Users\Ryan-PC\Desktop\MC Stuff\shader-tuning\work\ComplementaryUnbound_r5.7.1" -Recurse -File).Count
```
Expected: `404` files (matches the zip's file count).

- [ ] **Step 5: Create the audit-doc stub**

Create `shader-tuning/claude-audit/shader-01-audit.md` with this skeleton (sections filled by later tasks):
```markdown
# shader-01-audit — ComplementaryUnbound r5.7.1 (Deer Diary, this stack)

Phase 1 research + baseline. Every claim cites an on-disk path. No GLSL edited.

## 1. Pack anatomy & settings        <!-- Task 1 -->
## 2. Cost-suspect passes (GLSL)     <!-- Task 2 -->
## 3. Iris 1.8.13 feature surface    <!-- Task 3 -->
## 4. Sodium 0.8 shader surface      <!-- Task 4 -->
## 5. Coupling invariants (Colorwheel + DH)  <!-- Task 5 -->
## 6. License & distribution         <!-- Task 6 -->
## 7. Measurement methodology        <!-- Task 7 -->
## 8. Baseline (stock pack)          <!-- Task 8 -->
## 9. Ranked cost list + Phase 2 candidates  <!-- Task 9 -->
```

- [ ] **Step 6: Verify the tree**

Run:
```powershell
Get-ChildItem "C:\Users\Ryan-PC\Desktop\MC Stuff\shader-tuning" -Recurse -Directory | Select-Object -ExpandProperty FullName
```
Expected: `stock`, `work`, `work\ComplementaryUnbound_r5.7.1`, `claude-audit`, plus `measurements` will be created in Task 7.

- [ ] **Step 7: Commit (tracked files only)**

Confirm staging is clean, then commit:
```powershell
wsl git add "shader-tuning/.gitignore" "shader-tuning/claude-audit/shader-01-audit.md"
wsl git diff --cached --name-only   # MUST list exactly those two paths; if stock/ or work/ appear, STOP
wsl git commit -m "shader-tuning(phase1): work-tree setup + audit stub + local-only gitignore" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```
Expected: 2 files changed. `stock/` and `work/` must NOT appear (gitignored).

---

## Task 1: Pack anatomy & settings → audit §1

**Files:**
- Read: `shader-tuning/work/ComplementaryUnbound_r5.7.1/shaders/shaders.properties` (268 lines)
- Read: `…/shaders/block.properties`, `colorwheel.properties`, `dimension.properties`, `entity.properties`, `item.properties`
- Modify: `shader-tuning/claude-audit/shader-01-audit.md` (§1)

- [ ] **Step 1: Enumerate passes and buffers**

Read the full pass list (already known: gbuffers_* , `shadow`+`shadowcomp`, `deferred1`, `composite`..`composite7` sparse, `final`, `dh_terrain`, `dh_water`, `clrwl_*`, 3×`.csh`). Grep the GLSL for buffer/format directives to map VRAM footprint:
```
Grep pattern: "const int colortex\d+Format|/\* (RGBA|RGB|R)\d+" in work/**/shaders/**/*.{glsl,fsh,vsh}
Grep pattern: "RENDERTARGETS|DRAWBUFFERS" in work/**/shaders/**/*.fsh
```
Record, per pass: inputs (sampled colortex/depthtex/shadowtex), outputs (RENDERTARGETS), and any compute dispatch.

- [ ] **Step 2: Catalog the option/profile system**

From `shaders.properties`, transcribe into §1: the profile table (POTATO→ULTRA) and the cost-relevant options with their value ranges — at minimum `SHADOW_QUALITY`, `shadowDistance`, `LIGHTSHAFT_QUALI_DEFINE`, `SSAO_QUALI_DEFINE`, `WORLD_SPACE_REFLECTIONS`, `WATER_REFLECT_QUALITY`, `BLOCK_REFLECT_QUALITY`, `COLORED_LIGHTING`, `ENTITY_SHADOW`, `BLOOM_ENABLED`, `TAA`, `POM*`, `GENERATED_NORMALS`, `DETAIL_QUALITY`, `CLOUD_QUALITY`, `FXAA_DEFINE`.

- [ ] **Step 3: Map Ryan's 9 tuned options to their effect and `#define` site**

For each of Ryan's saved values — `ATM_FOG_MULT=0.50`, `AURORA_CONDITION=4`, `BLOCK_REFLECT_QUALITY=1`, `COLORED_LIGHTING=128`, `NIGHT_NEBULAE=1`, `NIGHT_STAR_AMOUNT=3`, `WATER_REFLECT_QUALITY=1`, `WAVING_LEAVES=false`, `shadowDistance=160` — grep `work/**/shaders/**` for where the `#define`/uniform is consumed and note what each gates. These are Ryan's revealed preferences and constrain "what must stay looking the same."

- [ ] **Step 4: Write §1 and verify**

Verification (artifact check): §1 lists every pass with its I/O, the buffer-format table, the profile table, and the 9-option map — **each entry citing a `work/...:line`**. Spot-check 3 random citations resolve to the claimed content.

- [ ] **Step 5: Commit**

```powershell
wsl git add "shader-tuning/claude-audit/shader-01-audit.md"
wsl git commit -m "shader-tuning(audit): pack anatomy, buffers, option/profile map" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Cost-suspect pass GLSL → audit §2

**Files:**
- Read: the GLSL of `shadow`, `shadowcomp`, `deferred1`, `composite`..`composite7`, `final`, and the 3 `.csh`, under `shader-tuning/work/ComplementaryUnbound_r5.7.1/shaders/world0/` (overworld is the hot path; note world1/world-1 deltas only where they differ)
- Read: `…/shaders/lib/**` includes pulled in by those passes
- Modify: `shader-01-audit.md` (§2)

- [ ] **Step 1: Read each suspect pass and classify its work**

For each pass, summarize in §2: what it computes, loop/sample counts (e.g. SSAO sample count, lightshaft step count, blur taps), buffer resolution (full vs half-res), and dependence on the §1 options. Explicitly resolve these named suspects:
- `COLORED_LIGHTING` voxel path — confirm which `.csh` voxelizes, the volume resolution implied by `COLORED_LIGHTING=128`, and its VRAM/dispatch cost.
- `LIGHTSHAFT_QUALI_DEFINE` volumetric pass — sample count vs quality level.
- `SSAO_QUALI_DEFINE` — sample count, resolution.
- `WORLD_SPACE_REFLECTIONS` / reflections — ray-march steps, resolution.
- Shadow path — how `shadowDistance` (pack=160) and Iris `maxShadowRenderDistance=32` actually interact (the candidate-#1 waste hypothesis from the spec). Cite where shadowDistance enters the shadow projection.

- [ ] **Step 2: Flag efficiency hypotheses (do NOT act)**

For each suspect, write a one-line falsifiable hypothesis tagged `[H]` (e.g. `[H] shadow geometry rasterized to 160 blocks but only sampled to 32 → ~Nx redundant shadow draw`). These seed Phase 2; they are not changes.

- [ ] **Step 3: Write §2 and verify**

Verification: every suspect pass has a work-summary + at least one cited `[H]` hypothesis with a `work/...:line` anchor. No hypothesis without a citation.

- [ ] **Step 4: Commit**

```powershell
wsl git add "shader-tuning/claude-audit/shader-01-audit.md"
wsl git commit -m "shader-tuning(audit): cost-suspect pass analysis + Phase 2 hypotheses" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Iris 1.8.13 feature surface → audit §3

**Files:**
- Read (do not edit, do not commit): `Custom Mods/src/special-sodium-modernport/claude-reference/Iris-1.21.1/common/src/main/java/net/irisshaders/iris/**`
- Focus: shaderpack directive parsing, supported uniforms, the `kroppeb.stareval` custom-uniform engine, compute-shader support, `compat/sodium/**`
- Modify: `shader-01-audit.md` (§3)

- [ ] **Step 1: Determine what directives/uniforms/extensions our Iris build parses**

Grep the Iris source for the directive/uniform registry and compute-program support:
```
Grep pattern: "RENDERTARGETS|DRAWBUFFERS|const .* workGroups|compute|csh|colortex\d+Format|GENERATED|custom uniform|stareval"
in claude-reference/Iris-1.21.1/common/src/main/java/net/irisshaders/iris/**
```
Record in §3: supported directives, the uniform list, whether compute shaders (`.csh`) are supported on this build (they must be — the pack ships 3), and any version-specific limits.

- [ ] **Step 2: Explain the `BIOME_PALE_GARDEN` failure mode concretely**

Find where the custom-uniform expression engine evaluates identifiers and logs unknowns (the `BIOME_PALE_GARDEN` warning). Cite the file:line. This defines the "what Iris silently tolerates vs hard-fails" boundary that constrains Phase 2 edits.

- [ ] **Step 3: Write §3 and verify**

Verification: §3 answers (a) supported directives/uniforms, (b) compute support yes/no with proof, (c) the tolerate-vs-fail boundary — each with `claude-reference/Iris-1.21.1/...:line` citations. **No claim about Iris capabilities without a source line.**

- [ ] **Step 4: Commit** (audit doc only — never stage `special-sodium-modernport`)

```powershell
wsl git add "shader-tuning/claude-audit/shader-01-audit.md"
wsl git diff --cached --name-only   # MUST show only the audit doc
wsl git commit -m "shader-tuning(audit): Iris 1.8.13 shader feature surface" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Sodium 0.8 shader-facing surface → audit §4

**Files:**
- Read (do not edit/commit): `Custom Mods/src/special-sodium-modernport/common/src/**` (the backport's terrain/render path) and `…/claude-reference/Iris-1.21.1/common/src/main/java/net/irisshaders/iris/compat/sodium/**`
- Read: `…/special-sodium-modernport/claude-audit/00-master-audit.md` (esp. §7.2) for the GPU-bound diagnosis context
- Read: the Sodium 0.8 changelog reference if present in the tree (search `claude-reference/OLD-sodium-1.21.1-stable` vs the backport for the delta)
- Modify: `shader-01-audit.md` (§4)

- [ ] **Step 1: Identify shader-relevant data-path changes in Sodium 0.8**

Grep the backport render path and the Iris↔Sodium bridge for the terrain vertex format, translucency sorting, and any per-frame work the new path removes or exposes:
```
Grep pattern: "VertexFormat|terrain|translucen|sort|colortex|normal|tangent|midTexCoord|mc_Entity|at_tangent"
in special-sodium-modernport/common/src/** and .../compat/sodium/**
```
Record in §4 *only* changes a shaderpack can actually exploit, each citing source. Distinguish "exposed to shaders" from "internal-only."

- [ ] **Step 2: State explicit non-capabilities**

Write the RTX reality check into §4: Iris is OpenGL; RT cores/DLSS/tensor are unreachable. List the *reachable* GPU levers (GL 4.6/DSA paths, NVIDIA GLSL extensions with parse-risk noted).

- [ ] **Step 3: Write §4 and verify**

Verification: each "Sodium 0.8 exposes X" line cites a `special-sodium-modernport/...:line`. Anything not found in source is marked "not found — do not assume," not asserted. **No fabricated features.**

- [ ] **Step 4: Commit** (audit doc only)

```powershell
wsl git add "shader-tuning/claude-audit/shader-01-audit.md"
wsl git diff --cached --name-only
wsl git commit -m "shader-tuning(audit): Sodium 0.8 shader-facing surface + RTX reality check" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: Coupling invariants (Colorwheel + DH + Sable) → audit §5

**Files:**
- Read: `shader-tuning/work/ComplementaryUnbound_r5.7.1/shaders/colorwheel.properties` and the `clrwl_gbuffers`, `clrwl_gbuffers_translucent`, `clrwl_shadow` passes
- Read: `shader-tuning/work/ComplementaryUnbound_r5.7.1/shaders/world0/dh_terrain.*`, `dh_water.*`
- Read (do not edit/commit): `…/claude-reference/Colorwheel-1.21.1-dev/**` and `…/distant-horizons-3.0.2b/**` for how the patcher/integration expects the pack to look
- Read (do not edit/commit): `…/claude-reference/sable-main/**` — Sable warns it does extensive shader overrides + a full light-storage replacement; identify what it overrides and which uniforms/buffers it injects so Phase 2 edits don't collide
- Inspect: `colorwheel_patcher-neoforge-1.0.5+mc1.21.1.jar` behavior — does it re-patch on every launch or detect "already patched"? (Check launch-log strings + the jar's patch markers.)
- Modify: `shader-01-audit.md` (§5)

- [ ] **Step 1: Determine the Colorwheel patch contract**

Establish: what files/markers `colorwheel_patcher` writes, whether it re-patches an edited pack or skips an "already patched" one, and which `clrwl_*` hooks Create/Flywheel rendering depends on. Cite the patcher's marker strings and the `colorwheel.properties` hooks.

- [ ] **Step 2: Determine the DH integration contract**

Establish what `dh_terrain`/`dh_water` require to keep Distant Horizons rendering under shaders (uniforms, buffer outputs). Cite.

- [ ] **Step 3: Determine the Sable override surface**

From `claude-reference/sable-main/**`, establish what Sable overrides in the shader pipeline and what its light-storage replacement injects (uniforms, buffers, light-data layout). Record which gbuffer/lighting inputs a Phase-2 edit must not assume it owns. Cite the override sites.

- [ ] **Step 4: Write the "must-preserve" invariant list**

§5 ends with an explicit checklist: *"A Phase-2 edit must not touch / must keep emitting: …"* covering Colorwheel (`clrwl_*` hooks), DH (`dh_*` contracts), and Sable (overridden inputs / light storage), so any later change can be checked against it. This is the guard that prevents breaking Create-under-shaders, DH, or Sable's lighting.

- [ ] **Step 5: Verify and commit**

Verification: §5 states the patch re-application behavior (with evidence) and the Colorwheel + DH + Sable preserve-lists, each cited. Commit:
```powershell
wsl git add "shader-tuning/claude-audit/shader-01-audit.md"
wsl git diff --cached --name-only
wsl git commit -m "shader-tuning(audit): Colorwheel + DH coupling invariants" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: License & distribution finding → audit §6

**Files:**
- Read: `shader-tuning/work/ComplementaryUnbound_r5.7.1/License.txt` (the only license file in the zip) and any credits in `shaders.properties` `info*` lines
- Modify: `shader-01-audit.md` (§6)

- [ ] **Step 1: Read `License.txt` verbatim and extract the redistribution terms**

Quote the exact clauses governing modification and redistribution into §6. Determine: (a) is local personal modification permitted? (b) is bundling a modified derivative into the published `deer-diary` pack permitted, and under what conditions (attribution, permission, share-alike, prohibition)?

- [ ] **Step 2: State the position**

Write the explicit finding: **local-only = OK / NOT OK**, and **distributable = OK / NOT OK / needs-permission**, with the quoted clause as justification. Default conclusion if ambiguous: local-only, mirror the FTB caution.

- [ ] **Step 3: Verify and commit**

Verification: §6 contains a verbatim clause quote + a yes/no on local and on distribution. No hand-waving. Commit:
```powershell
wsl git add "shader-tuning/claude-audit/shader-01-audit.md"
wsl git commit -m "shader-tuning(audit): Complementary license finding (local vs distributable)" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: Profiling-tool feasibility + measurement methodology (COLLABORATIVE — Ryan runs)

**Files:**
- Create: `shader-tuning/measurements/methodology.md`
- Create: `shader-tuning/measurements/baseline/` (dir)
- Modify: `shader-01-audit.md` (§7 = link to methodology.md + the tool decision)

- [ ] **Step 1: Author the Nsight feasibility recipe and hand it to Ryan**

Write into methodology.md the exact steps for Ryan to try attaching **NVIDIA Nsight Graphics** to the running game, including how to find the `javaw.exe` PID for the Prism "Deer Diary" instance:
```powershell
Get-CimInstance Win32_Process -Filter "Name='javaw.exe'" | Where-Object { $_.CommandLine -match 'Deer Diary' } | Select-Object ProcessId, CommandLine
```
Specify: launch via Nsight "Attach" → frame capture → read per-pass GPU time. **Ask Ryan to report:** does Nsight attach and produce per-pass timings for the OpenGL app? (yes/no + a screenshot of the capture).

- [ ] **Step 2: Define the fallback (always document, even if Nsight works)**

Write the **disable-pass-and-measure** protocol into methodology.md: short-circuit one suspect pass (e.g. set its option to off, or `discard`/early-return in the pass — local edit on the *work* copy, reload with `R`), read the steady-state frametime from BetterF3, restore. This yields per-pass frametime deltas without Nsight. Cross-check tool: Iris/Sodium debug HUD + BetterF3 graph.

- [ ] **Step 3: Define the fixed benchmark scenes (Ryan locks them)**

methodology.md specifies the scene recipe; **Ryan picks the actual spots** and records exact reproducible state for each:
- Scene A — clean overworld vista (sky+terrain+water+shadows). Record: coords (`/tp` or F3 XYZ), `/time set` value, weather cleared (`/weather clear`), facing (F3 yaw/pitch), render distance, and confirm the 9 tuned options + `enableShaders=true`.
- Scene B — Create build in view, **on the server** (exercises Colorwheel/Flywheel). Same recorded fields.
- Scene C (optional) — night scene (nebulae/stars/aurora — covers several tuned options). Same fields.
Provide the capture commands: F2 screenshot (lands in `…/Deer Diary/minecraft/screenshots/`), and the BetterF3 frametime read. Define how to lock TAA off for the diff set (the option toggle) so pixel-diffs aren't fooled by jitter.

- [ ] **Step 4: Create the perceptual no-regression diff tool**

The "zero perceptible change" gate needs a concrete, runnable diff. Create `shader-tuning/measurements/diff.py` (self-contained; compares two TAA-off captures at identical config):

```python
# shader-tuning/measurements/diff.py
# No-regression check for stock-vs-edited captures taken with TAA/temporal accumulation OFF
# at identical scene state. Usage: py diff.py stock.png edited.png
import sys
import numpy as np
from PIL import Image

a = np.asarray(Image.open(sys.argv[1]).convert("RGB"), dtype=np.float64)
b = np.asarray(Image.open(sys.argv[2]).convert("RGB"), dtype=np.float64)
if a.shape != b.shape:
    sys.exit(f"FAIL: size mismatch {a.shape} vs {b.shape}")
d = np.abs(a - b)
mae, peak = d.mean(), d.max()
# Gate (TAA-off, identical config): tiny residual from FP/driver nondeterminism is OK.
ok = mae < 1.0 and peak < 16
print(f"MAE={mae:.4f}  PEAK={peak:.0f}  -> {'PASS' if ok else 'REGRESSION'} (gate: MAE<1.0 AND PEAK<16)")
sys.exit(0 if ok else 1)
```

Document the one-time setup in methodology.md:
```powershell
py -m venv "C:\Users\Ryan-PC\Desktop\MC Stuff\shader-tuning\measurements\.venv"
& "C:\Users\Ryan-PC\Desktop\MC Stuff\shader-tuning\measurements\.venv\Scripts\python.exe" -m pip install pillow numpy
```
(Add `measurements/.venv/` to `shader-tuning/.gitignore` in this step.) If Ryan prefers ImageMagick, the equivalent is `magick compare -metric RMSE stock.png edited.png diff.png` — record whichever he has.

Verification: `diff.py` returns PASS (exit 0) when run on a screenshot against an exact copy of itself, and REGRESSION (exit 1) on two visibly different images. Note this is wired into Phase 2's per-change loop; in Phase 1 it only needs to exist and self-test.

- [ ] **Step 5: Record the tool decision in §7 and commit**

After Ryan reports Step 1's result, write the chosen primary tool (Nsight or fallback) into §7. Verification: methodology.md has the PID recipe, the fallback protocol, three reproducible scene definitions with all recorded fields, the TAA-off diff note, and the `diff.py` setup; `diff.py` self-tests; §7 records the tool decision. Commit:
```powershell
wsl git add "shader-tuning/.gitignore" "shader-tuning/measurements/methodology.md" "shader-tuning/measurements/diff.py" "shader-tuning/claude-audit/shader-01-audit.md"
wsl git commit -m "shader-tuning(measure): profiling methodology + fixed scenes + diff tool + tool decision" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: Baseline capture (COLLABORATIVE — Ryan runs the stock pack)

**Files:**
- Create: `shader-tuning/measurements/baseline/baseline.md` (numbers) + `…/baseline/*.png` (reference shots, gitignored)
- Modify: `shader-01-audit.md` (§8)

- [ ] **Step 1: Confirm the live pack is stock for the baseline**

The live `shaderpacks/ComplementaryUnbound_r5.7.1.zip` must equal the stock hash (`5CBF4D99…`) — verify before measuring so the baseline is truly stock:
```powershell
(Get-FileHash "C:\Users\Ryan-PC\AppData\Roaming\PrismLauncher\instances\Deer Diary\minecraft\shaderpacks\ComplementaryUnbound_r5.7.1.zip" -Algorithm SHA256).Hash
```
Expected: `5CBF4D99BC7CE4F0DF5C15974D276E0949BF6974D48D88A77D5E307B0B9BE8AF`.

- [ ] **Step 2: Ryan captures FPS + per-pass cost at each fixed scene**

Using methodology.md, at Scenes A/B(/C), shaders ON, Ryan records into `baseline.md`: average + 1%-low FPS (BetterF3), per-pass GPU time (Nsight) or per-pass frametime delta (fallback), GPU/VRAM utilization, and saves the reference screenshots into `measurements/baseline/`. **Agent fills no numbers it didn't receive from Ryan.**

- [ ] **Step 3: Capture the shaders-OFF reference too**

Record the shaders-off FPS at the same scenes (the 144 ceiling) so the gap-to-close is quantified per scene.

- [ ] **Step 4: Write §8 and verify**

Verification: §8/baseline.md has, per scene, shaders-on FPS (avg + 1% low), shaders-off FPS, and per-pass cost ranked high→low, plus saved reference shots. Numbers are Ryan-sourced. Commit (md only; images are gitignored):
```powershell
wsl git add "shader-tuning/measurements/baseline/baseline.md" "shader-tuning/claude-audit/shader-01-audit.md"
wsl git commit -m "shader-tuning(measure): stock baseline FPS + per-pass cost" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 9: Synthesis — ranked cost list + Phase 2 candidates → audit §9 (SCOPE GATE)

**Files:**
- Modify: `shader-01-audit.md` (§9)

- [ ] **Step 1: Rank the real cost centers**

Combine §2 (suspect analysis) with §8 (measured per-pass cost) into a single table ranked by **measured** GPU cost. Where measurement and hypothesis disagree, trust measurement and note the surprise.

- [ ] **Step 2: Turn each top cost center into a Phase 2 candidate**

For each high-cost pass, write a candidate entry: the efficiency hypothesis (`[H]` from §2), the expected mechanism of the win (redundant work / over-precision / invisible-at-our-settings / Sodium-0.8 lever), the **invariant it must respect** (cross-referenced to §5), and a rough effort/risk tag. **No candidate proposes reducing a visible effect** — every one must be a free/invisible efficiency claim per the spec's win condition.

- [ ] **Step 3: Verify completeness**

Verification: §9 ranks by measured cost, and every top-N pass has a candidate or an explicit "leave alone — already efficient." Each candidate cites its §2/§5/§8 basis.

- [ ] **Step 4: Commit the completed audit**

```powershell
wsl git add "shader-tuning/claude-audit/shader-01-audit.md"
wsl git commit -m "shader-tuning(audit): ranked cost list + Phase 2 candidate set" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

- [ ] **Step 5: STOP at the scope gate**

Present `shader-01-audit.md` to Ryan for scope sign-off. **Do not begin Phase 2 / any GLSL edit until Ryan approves the candidate set.** Phase 2 is a separate plan, written after this gate.

---

## Phase 1 Definition of Done

- `shader-01-audit.md` complete: pack anatomy, cost-suspect GLSL, Iris surface, Sodium-0.8 surface, coupling invariants, license finding, methodology, baseline, ranked cost list + candidates — **every claim cited**.
- Pristine pack stashed (`stock/`, hash-verified) and restorable; upstream source kept local-only (gitignored) pending the license finding.
- Baseline numbers are real and Ryan-sourced; methodology is reproducible.
- Zero GLSL edited.
- Ryan has reviewed the audit and signed off on Phase 2 scope.
