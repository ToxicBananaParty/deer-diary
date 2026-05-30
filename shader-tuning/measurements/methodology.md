# Measurement methodology — ComplementaryUnbound efficiency tune

This is the repeatable recipe for every measurement in this project. The operating model: **the agent
authors analysis + candidate edits + exact run steps; Ryan executes the runs on the live game and reports
numbers/screenshots back; the agent diffs and decides.** Keep runs identical so before/after is comparable.

Stock pack SHA256 (the "control") = `5CBF4D99BC7CE4F0DF5C15974D276E0949BF6974D48D88A77D5E307B0B9BE8AF`.
Live pack path: `C:\Users\Ryan-PC\AppData\Roaming\PrismLauncher\instances\Deer Diary\minecraft\shaderpacks\ComplementaryUnbound_r5.7.1.zip`

---

## A. Profiling tool — primary (Nsight) with a fallback

### A.1 Nsight Graphics feasibility test (Ryan runs once)
Goal: confirm NVIDIA Nsight Graphics can attach to the LWJGL/Java process and give per-pass GPU time for our
OpenGL app. If it can, it's the primary profiler.

1. Launch Deer Diary normally (Prism), get into a world with shaders on.
2. Find the game's process ID:
   ```powershell
   Get-CimInstance Win32_Process -Filter "Name='javaw.exe'" | Where-Object { $_.CommandLine -match 'Deer Diary' } | Select-Object ProcessId, CommandLine
   ```
   (If it's `java.exe` instead of `javaw.exe`, swap the name.)
3. In Nsight Graphics: **Connect → Attach to Process** (or "Frame Debugger" → attach), pick that PID.
4. Capture a frame; open the **range profiler / GPU trace** and look for an OpenGL pass timeline.

**Report back:** (a) did it attach? (b) does it show per-pass / per-drawcall GPU time for the OpenGL app?
(c) a screenshot of the capture. If attach fails or it can't time OpenGL passes, we use the fallback below.

### A.2 Fallback — disable-one-pass-and-measure (always valid; also a cross-check)
For a suspect pass, short-circuit it in the **work copy** (e.g. set its option off, or early-`return`/`discard`
in the pass), re-zip into a `-tuned` pack, Iris live-reload (`R`), and read the steady-state frametime delta
from **BetterF3**. The frametime drop ≈ that pass's cost. Restore after. Cross-check with the Sodium/Iris debug
HUD. Cruder than Nsight but reliable and needs no external tool.

---

## B. Fixed benchmark scenes (Ryan locks these once, then reuses forever)

Pick stable, representative spots and **record the exact state** so any future run reproduces the frame. Fill
the tables below. Use F3 for XYZ + facing (yaw/pitch). Set deterministic conditions before capturing.

Common setup for every capture:
- Shaders ON, pack = stock control (unless measuring an edit).
- Ryan's 9 tuned options active; render distance = ____ (record it); GUI scale, resolution, windowed/fullscreen recorded.
- `/gamerule doDaylightCycle false`, then `/time set <value>` ; `/weather clear` ; stand still ~3 s before capture.
- For **diff captures specifically**, also disable temporal/edge effects so frames are near-deterministic:
  TAA **off** (shader options → CAMERA → TAA), FXAA **off** (`FXAA_DEFINE=-1`), Motion Blur **off**. (Perf
  captures can leave these as Ryan normally plays — just be consistent within a comparison.)

### Scene A — clean overworld vista (sky + terrain + water + shadows in frame)
| Field | Value |
|---|---|
| World / dimension | _____ (overworld) |
| XYZ | _____ |
| Facing (yaw / pitch) | _____ |
| `/time set` | _____ (e.g. 6000 noon) |
| Weather | clear |
| Render distance | _____ |
| Resolution / mode | _____ |

### Scene B — Create build in view, ON THE SERVER (exercises Colorwheel/Flywheel)
| Field | Value |
|---|---|
| Server + location | _____ |
| XYZ | _____ |
| Facing (yaw / pitch) | _____ |
| `/time set` | _____ |
| Weather | clear |
| Render distance | _____ |
| What Create machinery is in frame | _____ (must show moving Create contraptions under shaders) |

### Scene C — night (nebulae / stars / aurora; covers several tuned options) *(optional but recommended)*
| Field | Value |
|---|---|
| XYZ | _____ |
| Facing (yaw / pitch) | _____ |
| `/time set` | _____ (e.g. 18000 midnight) |
| Weather | clear |
| Notes | aurora needs snowy biome or dark moon (AURORA_CONDITION=4) |

---

## C. Capturing a run

At each scene, with the target pack loaded:
1. **FPS:** read from BetterF3 — record **average** and **1%-low** over ~10 s of standing still (and note GPU
   util % and VRAM use if shown). Also record the same scene **shaders-off** once (the 144 ceiling) to quantify
   the gap.
2. **Per-pass cost:** Nsight per-pass GPU time, OR fallback frametime deltas for the suspect passes.
3. **Reference screenshot:** press **F2**. Screenshots land in
   `C:\Users\Ryan-PC\AppData\Roaming\PrismLauncher\instances\Deer Diary\minecraft\screenshots\`.
   Copy the relevant ones into `shader-tuning/measurements/baseline/` (stock) or a per-change folder.
   Name them `sceneA_stock.png`, `sceneA_<change>.png`, etc.

Report the numbers back; the agent records them in `shader-01-audit.md` §8 and the optimization log.

---

## D. No-regression check (the "zero perceptible change" gate)

For each Phase-2 candidate edit, compare its screenshot against the stock reference **at the same scene with
TAA/FXAA/motion-blur off** (see §B), using `diff.py`:

One-time setup:
```powershell
py -m venv "C:\Users\Ryan-PC\Desktop\MC Stuff\shader-tuning\measurements\.venv"
& "C:\Users\Ryan-PC\Desktop\MC Stuff\shader-tuning\measurements\.venv\Scripts\python.exe" -m pip install pillow numpy
```
Run:
```powershell
& "C:\Users\Ryan-PC\Desktop\MC Stuff\shader-tuning\measurements\.venv\Scripts\python.exe" `
  "C:\Users\Ryan-PC\Desktop\MC Stuff\shader-tuning\measurements\diff.py" sceneA_stock.png sceneA_change.png
```
Output: `MAE=… PEAK=… -> PASS/REGRESSION`. Gate: **MAE < 1.0 AND PEAK < 16** (tiny residual from FP/driver
nondeterminism and leftover noise dither is OK; anything visibly different trips it). A change lands only if it
improves FPS **and** passes the diff. If ImageMagick is preferred instead: `magick compare -metric RMSE
sceneA_stock.png sceneA_change.png diff.png`.

> Note on temporal effects: even with TAA off, reflections (colortex7) and noise-dithered effects use
> `frameCounter`, so two frames aren't byte-identical. Standing still a few seconds + the perceptual threshold
> absorbs this. If a diff is borderline, capture 2–3 frames per side and compare the most-settled pair.
