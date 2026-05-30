# Nvidium ↔ DH + Iris Compatibility — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the NeoForge Nvidium build coexist with Distant Horizons and render terrain through Iris shaderpacks (Complementary Unbound or BSL) with Colorwheel intact, behind a hard never-crash/never-regress fallback floor.

**Architecture:** A per-frame render-mode arbiter (`DISABLED`/`VANILLA`/`SHADERS`) that is itself the fallback floor, plus three isolated, soft-dependency compat layers — `compat/dh` (DhApi events/overrides), `compat/iris` (re-link shaderpack fragment with Nvidium's mesh/task stages, render into Iris gbuffers via accessor-mixins, modeled on Iris-Sodium + Colorwheel), and `compat/colorwheel` (state-restoration + verification). Each layer is guarded so any failure degrades to the floor.

**Tech Stack:** Java 21, NeoForge 21.1.x, ModDevGradle, SpongePowered Mixin, `GL_NV_mesh_shader`; soft-deps Sodium 0.6.13, Iris 1.8.12, Distant Horizons 3.0.2-b, Colorwheel 1.2.4 (versions pinned — these mixins touch mod internals).

**Working dir for all tasks:** `C:\Users\Ryan-PC\Desktop\MC Stuff\Custom Mods\src\nvidium-1.21.1` (`<proj>`). Repo root `C:\Users\Ryan-PC\Desktop\MC Stuff`. Branch: continue on `nvidium-neoforge-port` (this builds directly on the port). Shell: PowerShell. Commit locally; do not push.

**Reference source (read-only) under `<proj>\claude-reference\`:** `distanthorizons-main/`, `Iris-1.21.1/`, `Colorwheel-1.21.1-dev/`, `sodium-1.21.1-stable/`. These are the integration templates; cite exact paths when a task says "model on".

**Verification model:** This is GPU rendering — there are no unit tests. Each phase ends in **hardware gates** run via `.\gradlew.bat runClient` on the RTX 4090, observing the game. Treat the gate's pass/fail as the test. Rendering bugs found at a gate → use superpowers:systematic-debugging.

---

## File Map

**Phase 1 — mode arbiter**
- Create `src/main/java/me/cortex/nvidium/RenderMode.java` — the enum + resolver.
- Modify `src/main/java/me/cortex/nvidium/Nvidium.java` — hold `MODE`, derive `IS_ENABLED`.
- Modify `src/main/java/me/cortex/nvidium/sodiumCompat/IrisCheck.java` — feed the resolver (keep Iris-present/shader-active queries; drop the binary disable decision).
- Modify `src/main/java/me/cortex/nvidium/mixin/sodium/MixinRenderSectionManager.java` — call the resolver where `IS_ENABLED` is set (currently line ~53).

**Phase 2 — DH coexistence**
- Create `src/main/java/me/cortex/nvidium/compat/dh/NvidiumDhCompat.java` — guarded init + DhApi event binding + fog/overdraw coordination.
- Modify `src/main/java/me/cortex/nvidium/NvidiumNeoForge.java` — call `NvidiumDhCompat.init()` on client setup.
- Modify `src/main/java/me/cortex/nvidium/mixin/minecraft/MixinFogRenderer.java` — gate Nvidium's fog override off when DH owns fog.
- Modify `build.gradle` (+ `gradle.properties`) — DH API `compileOnly`.
- Modify `src/main/resources/META-INF/neoforge.mods.toml` — optional `distanthorizons` dependency.

**Phase 3 — Iris terrain integration**
- Create `src/main/java/me/cortex/nvidium/mixin/iris/*Accessor.java` — accessor mixins into Iris internals (model: `Colorwheel-1.21.1-dev/.../accessors/iris/*`).
- Create `src/main/java/me/cortex/nvidium/compat/iris/NvidiumIrisCompat.java` — pipeline detection + supported-pack gate.
- Create `src/main/java/me/cortex/nvidium/compat/iris/IrisProgramBridge.java` — build+cache mesh-shader programs from shaderpack fragment.
- Create `src/main/java/me/cortex/nvidium/compat/iris/IrisGbufferBinder.java` — bind/restore Iris gbuffer FBO + uniforms/samplers around a pass.
- Modify `src/main/java/me/cortex/nvidium/RenderPipeline.java` and the `renderers/*Rasterizer.java` — use Iris program + gbuffer in SHADERS mode.
- Modify `build.gradle` — Iris full-jar `compileOnly`; register `mixin/iris` config.
- Modify `src/main/resources/nvidium.mixins.json` (or add `nvidium.iris.mixins.json`).

**Phase 4 — Colorwheel coexistence**
- Modify `build.gradle` — Colorwheel `compileOnly`; `neoforge.mods.toml` optional dep.
- Create `src/main/java/me/cortex/nvidium/compat/colorwheel/*` only if a concrete conflict needs a mixin (else this phase is verification + state-restoration hardening in Phase 3).

**Phase 0 — dev runtime stack** (prerequisite for gates)
- Modify `build.gradle` — put Sodium + Iris + DH + Colorwheel (+ Create/Flywheel for the Colorwheel gate) on the dev `runClient` runtime classpath.

---

## Phase 0 — Dev runtime stack (so the gates can run)

### Task 0.1: Put the full mod stack on the dev runClient classpath

**Files:** Modify `<proj>/build.gradle`; `<proj>/gradle.properties`.

The port's dev client only loaded Sodium. Every gate here needs DH/Iris/Colorwheel (and Create+Flywheel for Colorwheel) loaded at runtime. NeoForge ModDevGradle loads runtime-classpath mods that have a `neoforge.mods.toml`.

- [ ] **Step 1: Add version properties** to `gradle.properties` (confirm exact Modrinth file strings in Step 3):

```properties
# Compat mod versions (match the Deer Diary pack)
iris_version=1.8.12+1.21.1-neoforge
sodium_version=mc1.21.1-0.6.13-neoforge
distanthorizons_version=3.0.2-b-1.21.1-neoforge
colorwheel_version=1.2.4+mc1.21.1-neoforge
```

- [ ] **Step 2: Update `dependencies` + `runs.client`** in `build.gradle`. Replace the existing `dependencies { ... }` block with:

```groovy
dependencies {
    // Required, compile + dev runtime.
    implementation "maven.modrinth:sodium:${project.sodium_version}"

    // Soft-compat deps. compileOnly for build; localRuntime so the dev client loads them.
    compileOnly "maven.modrinth:iris:${project.iris_version}"
    compileOnly "maven.modrinth:distanthorizons:${project.distanthorizons_version}"
    compileOnly "maven.modrinth:colorwheel:${project.colorwheel_version}"

    localRuntime "maven.modrinth:iris:${project.iris_version}"
    localRuntime "maven.modrinth:distanthorizons:${project.distanthorizons_version}"
    localRuntime "maven.modrinth:colorwheel:${project.colorwheel_version}"
}
```

(If MDG `localRuntime` does not get the mod loaded by FML in the dev run, switch those three to `additionalRuntimeClasspath` — verify in Step 4.)

- [ ] **Step 3: Resolve-check the coordinates.** Run from `<proj>`:

```powershell
.\gradlew.bat dependencies --configuration runtimeClasspath
```

Expected: `BUILD SUCCESSFUL` and all four mods resolve. If any coordinate 404s, find the exact NeoForge file string at the mod's Modrinth versions page for MC 1.21.1 and fix the property. (Create + Flywheel for the Colorwheel gate are added in Task 4.x, not here.)

- [ ] **Step 4: Smoke-launch.** Run `.\gradlew.bat runClient`, reach the main menu, confirm the mod list shows sodium, iris, distanthorizons, colorwheel, nvidium with no load error, then close. If DH/Iris/Colorwheel are absent from the list, switch `localRuntime`→`additionalRuntimeClasspath` (Step 2) and re-run.

- [ ] **Step 5: Commit**

```powershell
git add "Custom Mods/src/nvidium-1.21.1/build.gradle" "Custom Mods/src/nvidium-1.21.1/gradle.properties"
git commit -m "nvidium(compat): load full mod stack (sodium/iris/DH/colorwheel) in dev client"
```

---

## Phase 1 — Render-mode arbiter (the fallback floor)

Goal: replace the binary enable/disable with explicit modes so every later layer can fail safely. End state must be a **no-op behavioral change** vs the port (still `VANILLA` when no shaders, still disabled under shaders — that flips in Phase 3).

### Task 1.1: Add the `RenderMode` enum + resolver

**Files:** Create `<proj>/src/main/java/me/cortex/nvidium/RenderMode.java`.

- [ ] **Step 1: Create `RenderMode.java`**

```java
package me.cortex.nvidium;

import me.cortex.nvidium.sodiumCompat.IrisCheck;

/**
 * Per-frame decision of how Nvidium participates in rendering. This is the
 * single fallback-floor authority: anything uncertain resolves to a mode that
 * yields to Sodium, never a crash.
 */
public enum RenderMode {
    /** Hardware unsupported or force-disabled: Nvidium does nothing. */
    DISABLED,
    /** Nvidium renders terrain, no shaderpack active (the original working path). */
    VANILLA,
    /** Nvidium renders terrain THROUGH the active, supported Iris shaderpack. */
    SHADERS;

    /**
     * Resolve the mode for the current frame. Called where IS_ENABLED was set.
     * SHADERS is only returned once Phase 3 reports a usable Iris integration
     * for the active pack; until then, shaders-active resolves to DISABLED
     * (i.e. yield to Sodium+Iris), preserving the port's behavior.
     */
    public static RenderMode resolve() {
        if (!Nvidium.IS_COMPATIBLE || Nvidium.FORCE_DISABLE) {
            return DISABLED;
        }
        if (IrisCheck.isShaderPackActive()) {
            // Phase 3 replaces this with: return IrisCompatGate.supportsActivePack() ? SHADERS : DISABLED;
            return DISABLED;
        }
        return VANILLA;
    }
}
```

- [ ] **Step 2: Commit**

```powershell
git add "Custom Mods/src/nvidium-1.21.1/src/main/java/me/cortex/nvidium/RenderMode.java"
git commit -m "nvidium(compat): add RenderMode arbiter enum (fallback floor)"
```

### Task 1.2: Refactor `IrisCheck` to expose queries (not the disable decision)

**Files:** Modify `<proj>/src/main/java/me/cortex/nvidium/sodiumCompat/IrisCheck.java`.

- [ ] **Step 1: Replace the body** with query methods the resolver uses:

```java
package me.cortex.nvidium.sodiumCompat;

import net.irisshaders.iris.api.v0.IrisApi;
import net.neoforged.fml.ModList;

public class IrisCheck {
    public static final boolean IRIS_LOADED = ModList.get().isLoaded("iris");

    /** True when Iris is present AND a shaderpack is currently in use. */
    public static boolean isShaderPackActive() {
        return IRIS_LOADED && IrisApi.getInstance().isShaderPackInUse();
    }

    /** True while Iris is rendering its shadow pass (Phase 3 uses this). */
    public static boolean isRenderingShadowPass() {
        return IRIS_LOADED && IrisApi.getInstance().isRenderingShadowPass();
    }
}
```

(The old `checkIrisShouldDisable()` is removed; its only caller — `MixinRenderSectionManager` — is updated next.)

- [ ] **Step 2: Commit**

```powershell
git add "Custom Mods/src/nvidium-1.21.1/src/main/java/me/cortex/nvidium/sodiumCompat/IrisCheck.java"
git commit -m "nvidium(compat): IrisCheck exposes shaderpack/shadow queries"
```

### Task 1.3: Drive `IS_ENABLED` + `MODE` from the resolver

**Files:** Modify `<proj>/src/main/java/me/cortex/nvidium/Nvidium.java`; `<proj>/src/main/java/me/cortex/nvidium/mixin/sodium/MixinRenderSectionManager.java`.

- [ ] **Step 1: Add `MODE` to `Nvidium.java`.** After the `IS_ENABLED` field declaration add:

```java
    public static RenderMode MODE = RenderMode.DISABLED;
```

- [ ] **Step 2: Update the gate site.** In `MixinRenderSectionManager.java`, the line that currently reads (around line 53):

```java
        Nvidium.IS_ENABLED = (!Nvidium.FORCE_DISABLE) && Nvidium.IS_COMPATIBLE && IrisCheck.checkIrisShouldDisable();
```

replace with:

```java
        Nvidium.MODE = RenderMode.resolve();
        Nvidium.IS_ENABLED = Nvidium.MODE != RenderMode.DISABLED;
```

Add `import me.cortex.nvidium.RenderMode;` to that file.

- [ ] **Step 3: Build + regression gate.** Run `.\gradlew.bat build` (expect SUCCESS), then `.\gradlew.bat runClient`, enter a world with **shaders off, DH off**. Confirm terrain renders exactly as the port did (`VANILLA`), log shows "Enabling Nvidium", and enabling a shaderpack makes Nvidium yield to Sodium with no crash (still `DISABLED` under shaders for now). Close.

- [ ] **Step 4: Commit**

```powershell
git add "Custom Mods/src/nvidium-1.21.1/src/main/java/me/cortex/nvidium/Nvidium.java" "Custom Mods/src/nvidium-1.21.1/src/main/java/me/cortex/nvidium/mixin/sodium/MixinRenderSectionManager.java"
git commit -m "nvidium(compat): drive IS_ENABLED from RenderMode (no behavior change yet)"
```

---

## Phase 2 — Distant Horizons coexistence (no shaders)

Goal: with shaders off + DH on, Nvidium near terrain and DH LODs compose correctly. Approach = minimal coordination (fog owner + overdraw radius + depth/projection consistency); escalate to an `IDhApiFramebuffer` override only if a gate shows artifacts.

### Task 2.1: Add the DH API dependency + optional mod dependency

**Files:** Modify `<proj>/build.gradle` (done in 0.1 if coordinate resolved), `<proj>/src/main/resources/META-INF/neoforge.mods.toml`.

- [ ] **Step 1: Confirm DH on the compile classpath.** The DH jar is `compileOnly` from Task 0.1. Verify the `DhApi` type resolves:

```powershell
.\gradlew.bat compileJava
```

If `com.seibel.distanthorizons.api.DhApi` is not found, the Modrinth `distanthorizons` jar may ship the API in a separate `coreSubProjects/api` artifact — check the jar contents (`jar tf` the resolved file under `~/.gradle/caches`) and, if needed, add the DH API maven (`maven { url = "https://maven.modmuss50.me/" }` or DH's documented API coordinate) as the `compileOnly` source instead.

- [ ] **Step 2: Add the optional dependency** to `neoforge.mods.toml` (after the `iris` optional dependency block):

```toml
[[dependencies.nvidium]]
    modId = "distanthorizons"
    type = "optional"
    versionRange = "[3.0.0,)"
    ordering = "AFTER"
    side = "CLIENT"
```

- [ ] **Step 3: Commit**

```powershell
git add "Custom Mods/src/nvidium-1.21.1/build.gradle" "Custom Mods/src/nvidium-1.21.1/src/main/resources/META-INF/neoforge.mods.toml"
git commit -m "nvidium(compat): add Distant Horizons API dependency"
```

### Task 2.2: DH compat init + event binding (guarded, no-op without DH)

**Files:** Create `<proj>/src/main/java/me/cortex/nvidium/compat/dh/NvidiumDhCompat.java`; modify `<proj>/src/main/java/me/cortex/nvidium/NvidiumNeoForge.java`.

Reference template: `<proj>/claude-reference/Iris-1.21.1/common/src/main/java/net/irisshaders/iris/compat/dh/LodRendererEvents.java` (how Iris binds DhApi events/overrides).

- [ ] **Step 1: Create `NvidiumDhCompat.java`.** All DH symbols isolated in this class; entered only when DH is loaded so the JVM never links DH classes otherwise.

```java
package me.cortex.nvidium.compat.dh;

import me.cortex.nvidium.Nvidium;
import net.neoforged.fml.ModList;

/**
 * Coordinates Nvidium near-terrain rendering with Distant Horizons far LODs.
 * Soft dependency: every DH symbol lives behind init()'s isLoaded() guard so
 * Nvidium runs unchanged when DH is absent.
 */
public final class NvidiumDhCompat {
    public static boolean ACTIVE = false;

    private NvidiumDhCompat() {}

    public static void init() {
        if (!ModList.get().isLoaded("distanthorizons")) {
            return;
        }
        try {
            DhBridge.bind();   // separate class: classloads DH types only here
            ACTIVE = true;
            Nvidium.LOGGER.info("Distant Horizons detected — Nvidium DH coexistence active");
        } catch (Throwable t) {
            ACTIVE = false;
            Nvidium.LOGGER.warn("Failed to initialize Nvidium<->DH coexistence; DH may double-draw", t);
        }
    }
}
```

- [ ] **Step 2: Create `DhBridge.java`** (same package) holding the actual DhApi calls. Bind `DhApiAfterDhInitEvent`; once DH is initialized, on each `DhApiBeforeRenderPassEvent` set the overdraw radius and (if Nvidium owns rendering this frame) disable DH's own fog. Use the API surface mapped in the spec:

```java
package me.cortex.nvidium.compat.dh;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.objects.events.DhApiEventRegister; // confirm exact type during impl
import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.RenderMode;

final class DhBridge {
    static void bind() {
        // After DH init, Delayed.* is safe. Bind a handler that, per render pass:
        //  - sets overdrawPreventionRadius so DH skips LODs within Nvidium's real-chunk radius
        //  - leaves fog to DH (Nvidium's fog mixin gates off when ACTIVE; see Task 2.3)
        DhApi.events.bind(com.seibel.distanthorizons.api.methods.events.sharedParameterObjects
                .DhApiAfterDhInitEvent.class, new DhApiAfterDhInitHandler());
    }

    static void onRenderPass() {
        if (Nvidium.MODE == RenderMode.VANILLA) {
            // DH owns far fog; do not let DH draw LODs inside Nvidium's real chunks.
            DhApi.Delayed.configs.graphics().overdrawPreventionRadius()
                .setValue(computeOverdrawFraction());
        }
    }

    private static double computeOverdrawFraction() {
        // DH discards LODs within overdrawPreventionRadius * MC effective render distance.
        // We want LODs to start beyond Nvidium's real chunks. region_keep_distance is in
        // regions (256 blocks). Clamp to [0,1]; refine against visual results in the gate.
        double mcRd = Math.max(1, net.minecraft.client.Minecraft.getInstance()
                .options.getEffectiveRenderDistance());
        double nvidiumChunks = Nvidium.config.region_keep_distance * 16.0;
        return Math.min(1.0, nvidiumChunks / mcRd);
    }
}
```

> NOTE for the implementer: the exact event package/handler-interface names
> (`DhApiAfterDhInitEvent`, the `IDhApiEvent`/abstract handler base, and
> `DhApi.events.bind` signature) must be read from the resolved DH jar /
> `claude-reference/distanthorizons-main/.../api/methods/events/` and matched
> exactly — the names above are from the spec's exploration and may need the
> precise generic signature. Keep `DhBridge` the ONLY file that imports
> `com.seibel.*`.

- [ ] **Step 3: Call `init()` from the client mod entry.** In `NvidiumNeoForge.java`, change the constructor to register a client-setup callback:

```java
package me.cortex.nvidium;

import me.cortex.nvidium.compat.dh.NvidiumDhCompat;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@Mod(value = "nvidium", dist = Dist.CLIENT)
public class NvidiumNeoForge {
    public NvidiumNeoForge(IEventBus modBus) {
        modBus.addListener((FMLClientSetupEvent e) -> e.enqueueWork(NvidiumDhCompat::init));
    }
}
```

- [ ] **Step 4: Build.** `.\gradlew.bat build` → SUCCESS. (DH binding is exercised at runtime in Task 2.4.)

- [ ] **Step 5: Commit**

```powershell
git add "Custom Mods/src/nvidium-1.21.1/src/main/java/me/cortex/nvidium/compat/dh/" "Custom Mods/src/nvidium-1.21.1/src/main/java/me/cortex/nvidium/NvidiumNeoForge.java"
git commit -m "nvidium(compat): bind DH render events + overdraw coordination"
```

### Task 2.3: Gate Nvidium's fog override off when DH owns fog

**Files:** Modify `<proj>/src/main/java/me/cortex/nvidium/mixin/minecraft/MixinFogRenderer.java`.

- [ ] **Step 1: Add the DH-active condition.** Change the `changeFog` body so Nvidium only forces fog to infinity when DH is NOT coordinating fog:

```java
    @ModifyConstant(method = "setupFog", constant = @Constant(floatValue = 192.0F))
    private static float changeFog(float fog) {
        if (Nvidium.IS_ENABLED && !me.cortex.nvidium.compat.dh.NvidiumDhCompat.ACTIVE) {
            return 9999999f;
        } else {
            return fog;
        }
    }
```

(When DH is active it owns far fog; Nvidium leaving vanilla fog alone lets DH's existing fog handling apply. If a gate shows wrong near fog, revisit — this is the documented fog-owner rule.)

- [ ] **Step 2: Commit**

```powershell
git add "Custom Mods/src/nvidium-1.21.1/src/main/java/me/cortex/nvidium/mixin/minecraft/MixinFogRenderer.java"
git commit -m "nvidium(compat): yield fog ownership to DH when DH is active"
```

### Task 2.4: Gate — Nvidium + DH coexistence (RTX 4090)

**Files:** none (verification).

- [ ] **Step 1: Launch + observe.** `.\gradlew.bat runClient`; in-game ensure shaders are OFF and DH is ON (DH defaults on; confirm in its config). Load a world, set a high render distance, fly out.
- [ ] **Step 2: Check correctness:** (a) far terrain shows DH LODs beyond Nvidium's real chunks; (b) no LODs drawn *over* / z-fighting real near terrain (occlusion correct); (c) fog is continuous, not doubled/popping; (d) no crash; log shows no DH errors.
- [ ] **Step 3: If artifacts:** use systematic-debugging. Likely culprits in order: overdraw fraction (Task 2.2 `computeOverdrawFraction`), fog owner (Task 2.3), or depth mismatch → if depth/occlusion is wrong, escalate to binding an `IDhApiFramebuffer` override that points DH at Nvidium's depth (model: Iris `LodRendererEvents`), and add that as a new task. Commit any fix with message `nvidium(compat): fix DH <specific issue>`.
- [ ] **Step 4: Record outcome** (pass, or the escalation taken).

---

## Phase 3 — Iris shaderpack terrain integration (research-grade)

Goal: in `SHADERS` mode, Nvidium renders terrain into Iris's gbuffers using the active shaderpack's terrain fragment program, for **Complementary Unbound (plain) or BSL** (one required). This phase is **spike-driven**: each integration unknown is a short investigation task whose written output feeds the implementation task. Exact GLSL/uniform code is produced by the spikes — do NOT guess it ahead of reading the references. The fallback floor (Phase 1) guarantees no regression while this is incomplete.

> Primary references (read these, don't reinvent):
> - Iris-for-Sodium swap: `claude-reference/Iris-1.21.1/common/.../compat/sodium/mixin/MixinShaderChunkRenderer.java`, `.../pipeline/programs/SodiumPrograms.java`, `.../pipeline/programs/ShaderKey.java`.
> - Colorwheel (custom geometry → Iris gbuffers): `claude-reference/Colorwheel-1.21.1-dev/common/.../accessors/iris/*`, `.../compile/{ClrwlPipelineCompiler,IrisShaderComponent}.java`.
> - Nvidium render hook: `RenderPipeline.renderFrame()` (terrain raster ~line 393-394) and `renderers/PrimaryTerrainRasterizer.java` (owns its `Shader`).

### Task 3.1 (SPIKE): Map the exact Iris internals access path

**Files:** none (investigation; produce written notes appended to this plan under a "Phase 3 findings" section).

- [ ] **Step 1:** From the references above, write down precisely:
  - How to get the active `IrisRenderingPipeline` from a Nvidium class (which Iris/`Iris.getPipelineManager()` call, what accessor mixin Colorwheel uses).
  - How to obtain, per terrain pass, (a) the gbuffer `GlFramebuffer` and (b) the **patched fragment GLSL** for `gbuffers_terrain` (and shadow). Identify whether you read `SodiumPrograms`' already-patched source or must call `TransformPatcher.patchSodium` yourself, and how Colorwheel reaches `ProgramSource`.
  - The exact set of `out` varyings + uniforms + samplers the terrain fragment expects (from `ShaderKey.TERRAIN_*`, `IrisSamplers`, `CommonUniforms`, `MatrixUniforms`).
- [ ] **Step 2:** Decide and record the accessor-mixin list Nvidium needs (mirror the subset of Colorwheel's `accessors/iris/*` that applies). Commit the findings doc: `git commit -m "nvidium(iris): record Iris internals access findings (spike)"`.

### Task 3.2: Accessor mixins into Iris internals

**Files:** Create `<proj>/src/main/java/me/cortex/nvidium/mixin/iris/*Accessor.java`; register a `nvidium.iris.mixins.json`; reference it from `neoforge.mods.toml`.

- [ ] **Step 1:** Implement the accessor mixins identified in 3.1 (e.g. `IrisRenderingPipelineAccessor`, `RenderTargetsAccessor`, `ProgramSetAccessor`/`ProgramSourceAccessor`, `ShadowRenderTargetsAccessor`) — copy Colorwheel's accessor signatures, retargeted to Nvidium's package. Add `nvidium.iris.mixins.json` with `"compatibilityLevel":"JAVA_21"`, package `me.cortex.nvidium.mixin.iris`, listed under `client`, and a `[[mixins]] config="nvidium.iris.mixins.json"` entry in `neoforge.mods.toml`.
- [ ] **Step 2:** `.\gradlew.bat build` → SUCCESS (accessors compile against the Iris jar). Commit: `nvidium(iris): add accessor mixins for Iris pipeline internals`.

### Task 3.3 (SPIKE): Prototype one terrain program (Complementary Unbound, solid pass)

**Files:** `compat/iris/IrisProgramBridge.java` (create, iterate).

- [ ] **Step 1:** Build a single mesh-shader GL program for the SOLID terrain pass: Nvidium's `terrain/task.glsl` + `terrain/mesh.glsl` (modified so the mesh shader's per-vertex outputs match the varyings the shaderpack `gbuffers_terrain.fsh` reads — from 3.1) + the shaderpack's patched fragment source as `ShaderType.FRAGMENT`, linked via `Shader.Builder`. Log link/compile errors (the builder already prints them).
- [ ] **Step 2:** Record what varyings/uniforms had to be added to the mesh shader to satisfy Unbound's fragment, and whether `patchSodium`-style transformation was needed. This is the crux finding; commit the notes + prototype.

### Task 3.4: Gbuffer binding + uniform/sampler state

**Files:** Create `<proj>/src/main/java/me/cortex/nvidium/compat/iris/IrisGbufferBinder.java`.

- [ ] **Step 1:** Implement, modeled on `SodiumShader.setupState`/`resetState` + `IrisSamplers`: `beginPass(pass)` binds the Iris gbuffer FBO and sets matrices/fog/lightmap/gtexture/shadow/colortex samplers + custom uniforms for the bound program; `endPass()` restores MC's main framebuffer (`Minecraft.getInstance().getMainRenderTarget().bindWrite(false)`) and GL state. Strict save/restore (this is also what protects Colorwheel — Phase 4).
- [ ] **Step 2:** `.\gradlew.bat build` → SUCCESS. Commit: `nvidium(iris): gbuffer binder + uniform/sampler state`.

### Task 3.5: Wire SHADERS mode into the render pipeline

**Files:** Modify `RenderPipeline.java`, `renderers/PrimaryTerrainRasterizer.java` (and `TranslucentTerrainRasterizer.java`), `compat/iris/NvidiumIrisCompat.java`, `RenderMode.java`.

- [ ] **Step 1:** Create `NvidiumIrisCompat` with `supportsActivePack()` (true only once a program builds for the active pack) and accessors used by `RenderMode`. Update `RenderMode.resolve()` (Task 1.1) line `return DISABLED;` under shaders to `return NvidiumIrisCompat.supportsActivePack() ? SHADERS : DISABLED;`.
- [ ] **Step 2:** In `RenderPipeline.renderFrame()` (and `renderTranslucent()`), when `Nvidium.MODE == SHADERS`: `IrisGbufferBinder.beginPass(...)` before the `*Rasterizer.raster(...)` call and `endPass()` after; make the rasterizer use the Iris program variant from `IrisProgramBridge` instead of its default `shader` for that frame. Keep the `VANILLA` path untouched.
- [ ] **Step 3:** `.\gradlew.bat build` → SUCCESS. Commit: `nvidium(iris): render terrain into Iris gbuffers in SHADERS mode`.

### Task 3.6: Shadow pass

**Files:** `IrisGbufferBinder.java`, `IrisProgramBridge.java`, render hook.

- [ ] **Step 1:** When `IrisCheck.isRenderingShadowPass()`, render Nvidium terrain into Iris's shadow framebuffer with the shadow-program variant (from 3.1's shadow `ShaderKey`s). Commit: `nvidium(iris): shadow pass support`.

### Task 3.7: Gate — Nvidium + Iris (RTX 4090)

**Files:** none (verification).

- [ ] **Step 1:** `runClient`; enable **Complementary Unbound (plain r5.7.1)**; load a world. Confirm terrain is shaded consistently with the sky/entities (no unshaded/black terrain, correct lighting/shadows), matches a Sodium+Iris reference screenshot reasonably, no crash.
- [ ] **Step 2:** Repeat with **BSL v10.1.3**. **At least one of Unbound/BSL must pass** (hard requirement); record which.
- [ ] **Step 3:** Fallback check: enable an out-of-scope pack (e.g. an EuphoriaPatches variant or Photon). Confirm Nvidium reports the pack unsupported and **yields to Sodium+Iris with no crash** (mode resolves DISABLED).
- [ ] **Step 4:** If the target packs fail: systematic-debugging, iterating on 3.3/3.4/3.6. If after genuine effort a pack can't be made correct, document it; the hard requirement is satisfied if the *other* target pack passes. Commit fixes individually.

---

## Phase 4 — Colorwheel coexistence

Goal: with shaders ON and Nvidium ON, Create contraptions (Flywheel via Colorwheel) still render correctly.

### Task 4.1: Add Colorwheel + Create/Flywheel to the dev runtime; optional dep

**Files:** Modify `<proj>/build.gradle`, `<proj>/gradle.properties`, `neoforge.mods.toml`.

- [ ] **Step 1:** Colorwheel `compileOnly`+`localRuntime` were added in Task 0.1. Add Create + Flywheel to the dev runtime so contraptions exist to test (these are CurseForge/Modrinth deps; resolve the exact 1.21.1 NeoForge coordinates from the pack's metafiles in `docs/packwiz/mods/`). Add a `distanthorizons`-style optional dependency for `colorwheel` to `neoforge.mods.toml`.
- [ ] **Step 2:** Resolve-check + commit: `nvidium(compat): add Colorwheel optional dep + Create/Flywheel dev runtime`.

### Task 4.2: Gate — Colorwheel under shaders with Nvidium (RTX 4090)

**Files:** none (verification).

- [ ] **Step 1:** `runClient` with shaders on (the pack that passed 3.7) + Colorwheel + Create; place/observe a moving Create contraption. Confirm it renders shaded correctly (not unshaded, not invisible, not corrupt) with Nvidium active, matching how it looks with Nvidium off.
- [ ] **Step 2:** If broken: the cause is almost certainly FBO/GL state Nvidium left non-restored (Task 3.4 `endPass`). Fix the save/restore; only add a `compat/colorwheel/` mixin if a genuine ordering conflict remains. Commit: `nvidium(compat): restore GL state so Colorwheel renders under shaders`.

---

## Phase 5 — All three together (best-effort)

### Task 5.1: Gate — Nvidium + DH + Iris in one frame

**Files:** none (verification; glue only if needed).

- [ ] **Step 1:** `runClient` with DH on + shaders on (passing pack) + Nvidium on. DH-under-Iris already works via Iris's own DH compat; confirm Nvidium terrain (shaded) + DH LODs (shaded via Iris) + correct seam/occlusion in one frame.
- [ ] **Step 2:** If DH LODs conflict with Nvidium under shaders: the interaction is between Nvidium's gbuffer pass and Iris's DH path. Investigate with systematic-debugging; this is the documented stretch goal — if it can't be made correct, ensure it degrades gracefully (e.g. DH or Nvidium yields) rather than corrupting, and document. Commit any glue: `nvidium(compat): coordinate DH + Iris + Nvidium`.

### Task 5.2: Final review + branch finish

- [ ] **Step 1:** Dispatch a final code review over the whole Phase 1–5 diff (correctness, fallback-floor integrity: confirm every compat entry point is guarded and yields safely; confirm `VANILLA` path unchanged).
- [ ] **Step 2:** Use superpowers:finishing-a-development-branch.

---

## Self-Review

**Spec coverage:**
- Render-mode arbiter / fallback floor → Phase 1 (Tasks 1.1–1.3). ✓
- DH coexistence (events, fog owner, overdraw, depth) → Phase 2 (2.1–2.4), with escalation path noted. ✓
- Iris terrain integration (accessors, program re-link, gbuffers, uniforms, shadow, supported-pack gate) → Phase 3 (3.1–3.7). ✓
- Colorwheel coexistence → Phase 4 (4.1–4.2). ✓
- All-three (stretch) → Phase 5 (5.1). ✓
- Build/deps (DH API, Colorwheel, deepen Iris, mixin configs, pinned versions) → Tasks 0.1, 2.1, 3.2, 4.1. ✓
- Verification gates (regression, DH, Iris×2, fallback, Colorwheel, all-three) → Tasks 1.3, 2.4, 3.7, 4.2, 5.1. ✓
- Dev runtime stack prerequisite → Phase 0. ✓

**Placeholder honesty:** Phases 1–2, 4 use concrete code. Phase 3 deliberately uses SPIKE tasks because the exact GLSL/uniform code is unknowable until the Iris internals are read — each spike has a concrete deliverable and reference path, which is the honest form of "complete" for research-grade work. The two `NOTE` callouts flag exactly where the implementer must read real signatures rather than trust transcribed names (DH event types; Iris patched-source access).

**Type/name consistency:** `RenderMode {DISABLED,VANILLA,SHADERS}` + `Nvidium.MODE`/`IS_ENABLED` used consistently across 1.1/1.3/3.5; `IrisCheck.isShaderPackActive()`/`isRenderingShadowPass()` defined in 1.2, used in 1.1/3.6; `NvidiumDhCompat.ACTIVE` defined 2.2, used 2.3; `NvidiumIrisCompat.supportsActivePack()` defined 3.5, referenced from `RenderMode.resolve()`. Consistent.
