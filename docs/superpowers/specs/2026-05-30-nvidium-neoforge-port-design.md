# Port Nvidium 0.4.1-beta10 to NeoForge 1.21.1 — Design

**Date:** 2026-05-30
**Status:** Approved (design); pending spec review
**Source mod:** `Custom Mods/src/nvidium-1.21.1/` (Fabric/Quilt, fabric-loom)
**Target:** NeoForge 21.1.x / Minecraft 1.21.1, single-module ModDevGradle project

## Goal

Produce a working **NeoForge** build of Nvidium — the NVIDIA mesh-shader
rendering backend for Sodium — from the existing Fabric source, verify it
renders terrain on supported NVIDIA hardware, and ship it in the Deer Diary
pack as a **default-off, player-toggleable optional mod**.

Build target is **NeoForge-only, single module** (the Fabric build is dropped).
A multi-loader common/fabric/neoforge restructure was explicitly rejected as
YAGNI for a NeoForge-only pack.

## Why this is tractable

The Fabric coupling is thin. The mod's heavy lifting — OpenGL device/buffer
code (`gl/`), mesh-shader renderers (`renderers/`), region/section managers
(`managers/`), utilities (`util/`), the GLSL shaders, and the Sodium-targeting
mixins — is loader-agnostic and compiles unchanged.

Confirmed during exploration:

- **Zero** `net.fabricmc.fabric.api.*` usage. The Fabric-API modules in the
  loom `build.gradle` (`fabric-block-view-api-v2`, `fabric-rendering-fluids-v1`,
  `fabric-renderer-api-v1`, `fabric-rendering-data-attachment-v1`,
  `fabric-resource-loader-v0`) are transitive Sodium-Fabric requirements, not
  direct nvidium dependencies. They have no NeoForge equivalent to port because
  nvidium never calls them.
- The only Fabric imports in the entire source are `FabricLoader` /
  `ModContainer`, in exactly 3 files.
- The config GUI uses Sodium's own options API
  (`net.caffeinemc.mods.sodium.client.gui.options.*`), which is identical on the
  NeoForge Sodium build — not Cloth Config or any Fabric screen API.
- Sodium 0.6.13 ships a NeoForge build with the same `net.caffeinemc` class
  names (shared `common` module), so every Sodium-targeting mixin keeps its
  target.
- NeoForge runs **Mojang mappings natively**, so the vanilla-targeting mixins
  need no refmap/remapping (loom's intermediary remap step goes away entirely).

## Architecture of the change

### 1. Toolchain (loom → ModDevGradle)

Convert the project in place to ModDevGradle, mirroring the workspace
convention in `Custom Mods/src/trmt-neoforge-1.21.1/` and
`Custom Mods/src/deer-diary-commands/`:

- `settings.gradle` — NeoForged + gradlePluginPortal + mavenLocal plugin repos,
  `foojay-resolver-convention`, `rootProject.name = 'nvidium'`.
- `build.gradle` — `id 'net.neoforged.moddev' version '0.1.96'`, Java 21
  toolchain, `withSourcesJar()`, `neoForge { version = …; parchment { … };
  runs { client { client() } }; mods { nvidium { sourceSet sourceSets.main } } }`.
  (Client-only mod, but a `server` run may be kept to confirm the client mixins
  don't apply server-side.)
- `gradle.properties` — `mod_version`, `maven_group=me.cortex`,
  `neoforge_version`, `parchment_minecraft_version`,
  `parchment_mappings_version`, `minecraft_version=1.21.1`, `mod_id=nvidium`.
- Bump the Gradle wrapper to the version MDG 0.1.96 requires (whatever
  trmt/ddc use).
- `processResources` expands `version`, `commit`, `buildtime`,
  `neoforge_version`, `minecraft_version`, `mod_id` into `neoforge.mods.toml`
  (replacing loom's `fabric.mod.json` expansion). The git-short-hash `commit`
  helper is carried over.

**Dependencies** (Modrinth maven, same repo block trmt uses):

- `maven.modrinth:sodium:mc1.21.1-0.6.13-neoforge` — **required**, present at
  both compile time and dev runtime.
- `maven.modrinth:iris:1.8.8+1.21.1-neoforge` — `compileOnly` (soft compat,
  same as the Fabric build).

### 2. Metadata & mixin registration

- **Delete** `src/main/resources/fabric.mod.json`.
- **Add** `src/main/resources/META-INF/neoforge.mods.toml`:
  - `modLoader = "javafml"`, `loaderVersion`, `license = "LGPL-3.0"`.
  - `[[mods]]` with `modId="nvidium"`, expanded `version`, display name,
    description, authors, `logoFile` pointing at the existing
    `assets/nvidium/nvidium.png`.
  - `[modproperties.nvidium]` table carrying `commit = "${commit}"` so the
    original `MOD_VERSION = version + "-" + commit` string is preserved.
  - `[[mixins]]` with `config = "nvidium.mixins.json"`.
  - `[[dependencies.nvidium]]` entries:
    - `neoforge` (required, range matching the pack's NeoForge).
    - `minecraft` (`[1.21.1,1.22)` or matching the pack).
    - `sodium` — **required**, `versionRange = "[0.6.13,0.7)"`, `side = "CLIENT"`.
- **Add** a minimal `@Mod(value = "nvidium", dist = Dist.CLIENT)` class
  (e.g. `me.cortex.nvidium.NvidiumNeoForge`) with an empty/no-op constructor.
  Initialization stays mixin-driven (`MixinWindow` →
  `Nvidium.checkSystemIsCapable()`); the `@Mod` class exists to satisfy
  NeoForge's modId↔class expectation and to provide a clean future hook.
- **Keep** `nvidium.mixins.json` (package `me.cortex.nvidium.mixin`, same client
  mixin list). Drop loom's `mixin.defaultRefmapName`; no `refmap` field is needed.
  Optionally bump `compatibilityLevel` from `JAVA_17` to `JAVA_21`.

### 3. The three loader-API swaps (the only source edits)

| File | Fabric call | NeoForge replacement |
|---|---|---|
| `Nvidium.java` (static init) | `FabricLoader.getInstance().getModContainer("nvidium")` → `getMetadata().getVersion().getFriendlyString()` + `getCustomValue("commit").getAsString()` | `ModList.get().getModContainerById("nvidium")` → `getModInfo().getVersion().toString()` + `getModInfo().getModProperties().get("commit")` |
| `NvidiumConfig.java` (`getConfigPath`) | `FabricLoader.getInstance().getConfigDir()` | `FMLPaths.CONFIGDIR.get()` |
| `IrisCheck.java` (`IRIS_LOADED`) | `FabricLoader.getInstance().isModLoaded("iris")` | `ModList.get().isLoaded("iris")` |

All three sites are touched only during/after mod load and rendering, so
`ModList.get()` / `FMLPaths` are safely initialized by the time they run. No
loader-abstraction layer is introduced (single-loader target).

Everything else in `src/main/java` and `src/main/resources` (shaders, lang,
icon) is carried over unchanged.

## Verification gates

This is a GPU rendering port; unit tests do not meaningfully apply. Success is
defined by these gates, checked in order:

1. **Compiles** — `gradlew build` succeeds against sodium-neoforge.
2. **Dev client loads** — `gradlew runClient` with Sodium on the classpath:
   nvidium loads, all mixins apply, no crash, no missing-target errors.
3. **Renders on hardware** — on the NVIDIA Turing+ GPU:
   - Log shows "All capabilities met" then "Enabling Nvidium".
   - The **Nvidium options page appears** inside Sodium's video settings.
   - **Terrain renders correctly** through the mesh-shader pipeline.
4. **Clean fallback** — toggling the in-GUI "Disable nvidium" reverts to normal
   Sodium terrain rendering without artifacts or crash.

## Pack wiring (final phase, after standalone verification)

- MDG copies the built jar into `Custom Mods/dist/nvidium/` (mirror task, same
  pattern trmt uses to feed the publish pipeline).
- Add a `[[custom_mods]]` entry in `prism-to-modrinth-sync/config.toml`:
  `name = "nvidium"`, `source_dir` → the dist folder, default `source_pattern`
  and `target_dir = "mods"`.
- Surface nvidium as a **default-off, player-toggleable optional mod**, the way
  Sodium Dynamic Lights is exposed.

**Open item / risk:** the existing optional-mod mechanism
(`[packwiz.optional_mods]`) keys on a Modrinth `.pw.toml` slug. Sodium Dynamic
Lights works because it is a Modrinth-backed mod with a `.pw.toml` (shipped
`.jar.disabled`, its metafile force-emitted). A **locally-built custom jar has no
`.pw.toml`**, so making nvidium toggleable through packwiz-installer likely
requires a **small extension to the publish pipeline to support optional custom
mods** (emit a packwiz metafile / optional flag for a direct custom jar). The
implementation plan will scope this work explicitly rather than assume it is
free; if it proves large, shipping nvidium as a plain (always-on, but
self-disabling on unsupported hardware) custom mod is the fallback.

## Risks & mitigations

- **Sodium internal mixin targets must match the neoforge jar.** The mixins into
  `RenderSectionManager`, `ChunkBuilder`, `RenderRegionManager`, etc. target
  Sodium's internal classes. They are expected to match (shared `common`
  module) but this is the most likely friction point — caught at build time
  (compile against the neoforge jar) and runtime gate 2.
- **Dev-runtime resolution of sodium-neoforge under MDG.** May need an
  `additionalRuntimeClasspath` / `localRuntime` entry so the Sodium mod is on
  the dev client classpath, not just the compile classpath.
- **NV_mesh_shader hardware dependency.** Verification gate 3 requires the
  supported NVIDIA GPU; confirmed available.

## Out of scope

- Keeping or maintaining a Fabric build.
- Any change to Nvidium's rendering algorithms, shaders, or feature set — this
  is a port, not a refactor or enhancement.
- Upstreaming the port.
