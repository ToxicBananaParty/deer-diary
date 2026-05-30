# Nvidium → NeoForge 1.21.1 Port — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert the Fabric-only Nvidium 0.4.1-beta10 (NVIDIA mesh-shader rendering backend for Sodium) into a single-module NeoForge 1.21.1 mod, verify it renders terrain on the local NVIDIA GPU, and ship it in the Deer Diary pack as a default-off optional mod.

**Architecture:** In-place toolchain swap from fabric-loom to ModDevGradle (matching `trmt-neoforge-1.21.1` / `deer-diary-commands`). The mod's rendering code, GLSL shaders, Sodium-targeting mixins, and config GUI are loader-agnostic and carry over unchanged. Only three files use Fabric APIs (`FabricLoader`/`ModContainer`); each maps to a one-line NeoForge equivalent (`ModList` / `FMLPaths`). NeoForge runs Mojang mappings natively, so no mixin refmap/remap is needed.

**Tech Stack:** Java 21, ModDevGradle `0.1.96`, NeoForge `21.1.172`, Parchment `1.21.1:2024.11.17`, Gradle `9.4.0`, Sodium-NeoForge `0.6.13`, Iris-NeoForge `1.8.8` (soft), SpongePowered Mixin (bundled by NeoForge).

**Working directory for all tasks:** `C:\Users\Ryan-PC\Desktop\MC Stuff\Custom Mods\src\nvidium-1.21.1`
(referred to below as `<proj>`). Shell is PowerShell unless a step says otherwise. Git identity is configured in this shell; commit locally, **do not push**.

**Note on verification:** This is a GPU rendering port — unit tests do not apply. Each phase is verified by build/launch/render gates (compile clean, dev client loads, terrain renders on hardware, clean fallback). Those gates ARE the tests.

---

## File Map

**Replaced/created at project root (`<proj>`):**
- `settings.gradle` — replace (loom plugin repos → NeoForged/MDG)
- `gradle.properties` — replace (Fabric props → NeoForge props)
- `build.gradle` — replace (loom → ModDevGradle, deps, dist mirror)
- `gradle/wrapper/gradle-wrapper.properties` — bump Gradle to 9.4.0

**Resources (`<proj>/src/main/resources`):**
- `fabric.mod.json` — delete
- `META-INF/neoforge.mods.toml` — create
- `nvidium.mixins.json` — edit (bump compatibility level)

**Java (`<proj>/src/main/java/me/cortex/nvidium`):**
- `NvidiumNeoForge.java` — create (minimal `@Mod` client entry)
- `Nvidium.java` — edit (static init: `ModList`/`IModInfo`)
- `config/NvidiumConfig.java` — edit (config dir: `FMLPaths`)
- `sodiumCompat/IrisCheck.java` — edit (mod-loaded check: `ModList`)

**Pack pipeline (repo root, Phase 4 only):**
- `prism-to-modrinth-sync/config.toml` — edit (`[[custom_mods]]`, `[packwiz.self_host]`, `[packwiz.optional_mods]`)

---

## Phase 0 — Build system conversion

### Task 1: Replace `settings.gradle`

**Files:**
- Modify (overwrite): `<proj>/settings.gradle`

- [ ] **Step 1: Overwrite `settings.gradle` with the MDG/NeoForged plugin repos**

```groovy
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        maven {
            name = 'NeoForged'
            url = 'https://maven.neoforged.net/releases'
        }
    }
}

plugins {
    id 'org.gradle.toolchains.foojay-resolver-convention' version '0.8.0'
}

rootProject.name = 'nvidium'
```

- [ ] **Step 2: Commit**

```powershell
git add "Custom Mods/src/nvidium-1.21.1/settings.gradle"
git commit -m "nvidium(neoforge): convert settings.gradle to ModDevGradle plugin repos"
```

---

### Task 2: Replace `gradle.properties`

**Files:**
- Modify (overwrite): `<proj>/gradle.properties`

- [ ] **Step 1: Overwrite `gradle.properties` with NeoForge properties**

The `mod_version` keeps Nvidium's upstream version and appends the pack's MC-version suffix convention used by the other custom mods.

```properties
org.gradle.jvmargs=-Xmx2G
org.gradle.parallel=true

# NeoForge Properties
minecraft_version=1.21.1
neoforge_version=21.1.172
parchment_minecraft_version=1.21.1
parchment_mappings_version=2024.11.17

# Mod Properties
mod_version=0.4.1-beta10-1.21+1.21.1
maven_group=me.cortex
mod_id=nvidium
```

- [ ] **Step 2: Commit**

```powershell
git add "Custom Mods/src/nvidium-1.21.1/gradle.properties"
git commit -m "nvidium(neoforge): convert gradle.properties to NeoForge coordinates"
```

---

### Task 3: Replace `build.gradle`

**Files:**
- Modify (overwrite): `<proj>/build.gradle`

- [ ] **Step 1: Overwrite `build.gradle` with the ModDevGradle build**

Mirrors `trmt-neoforge-1.21.1/build.gradle`. The `runs.client` keeps Nvidium's large heap + ZGC (the loom build used `-Xmx8G -XX:+UseZGC`). The `copyJarToDist` task mirrors the jar into `Custom Mods/dist/nvidium/` for the publish pipeline. The git short-hash is baked into `mods.toml` via `processResources` to preserve Nvidium's `version-commit` string.

```groovy
plugins {
    id 'java'
    id 'net.neoforged.moddev' version '0.1.96'
}

version = project.mod_version
group = project.maven_group

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    withSourcesJar()
}

neoForge {
    version = project.neoforge_version

    parchment {
        minecraftVersion = project.parchment_minecraft_version
        mappingsVersion = project.parchment_mappings_version
    }

    runs {
        client {
            client()
            // Nvidium is memory-hungry; mirror the loom build's run args.
            jvmArguments.addAll("-Xmx8G", "-XX:+UseZGC")
        }
    }

    mods {
        nvidium {
            sourceSet sourceSets.main
        }
    }
}

repositories {
    // Sodium (required) and Iris (soft-compat) come from Modrinth's maven.
    maven {
        url = "https://api.modrinth.com/maven"
        content { includeGroup "maven.modrinth" }
    }
}

dependencies {
    // Required: Nvidium is an alternate rendering backend for Sodium. NeoForge build.
    implementation "maven.modrinth:sodium:mc1.21.1-0.6.13-neoforge"

    // Soft-compat: Iris API for shader-pack detection. compileOnly so the mod
    // loads fine without Iris present.
    compileOnly "maven.modrinth:iris:1.8.8+1.21.1-neoforge"
}

// Short git commit baked into mods.toml's [modproperties.nvidium].commit so
// Nvidium.MOD_VERSION can stay "<version>-<commit>" as on Fabric.
def gitCommitHash = providers.exec {
    commandLine 'git', 'rev-parse', '--short', 'HEAD'
}.standardOutput.asText.map { it.trim() }

processResources {
    var replaceProperties = [
        version          : version,
        commit           : gitCommitHash.get(),
        loader_version   : project.hasProperty('loader_version') ? project.loader_version : '[4,)',
        neoforge_version : project.neoforge_version,
        minecraft_version: project.minecraft_version,
        mod_id           : project.mod_id,
    ]
    inputs.properties(replaceProperties)
    filesMatching(['META-INF/neoforge.mods.toml']) {
        expand replaceProperties
    }
}

// Mirror the built jar into Custom Mods/dist/nvidium/ so the publish pipeline
// (prism-to-modrinth-sync) picks it up as a [[custom_mods]] source.
tasks.register('copyJarToDist', Copy) {
    dependsOn jar
    from jar.archiveFile
    into rootProject.file('../../dist/nvidium')
}
build.finalizedBy(copyJarToDist)
```

- [ ] **Step 2: Commit**

```powershell
git add "Custom Mods/src/nvidium-1.21.1/build.gradle"
git commit -m "nvidium(neoforge): convert build.gradle to ModDevGradle + dist mirror"
```

---

### Task 4: Bump the Gradle wrapper to 9.4.0

**Files:**
- Modify: `<proj>/gradle/wrapper/gradle-wrapper.properties`

- [ ] **Step 1: Set the `distributionUrl` to Gradle 9.4.0**

Open `<proj>/gradle/wrapper/gradle-wrapper.properties` and replace the `distributionUrl` line with:

```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-9.4.0-bin.zip
```

Leave the other lines (`distributionBase`, `zipStoreBase`, etc.) untouched.

- [ ] **Step 2: Verify Gradle resolves the new wrapper and the MDG plugin**

Run (PowerShell, from `<proj>`):

```powershell
.\gradlew.bat --version
```

Expected: prints `Gradle 9.4.0` (the wrapper downloads it on first run). No build errors.

- [ ] **Step 3: Verify the project configures and dependencies resolve**

Run:

```powershell
.\gradlew.bat dependencies --configuration runtimeClasspath
```

Expected: BUILD SUCCESSFUL, and the dependency tree lists `maven.modrinth:sodium:mc1.21.1-0.6.13-neoforge`. If Gradle reports it **cannot resolve** the Sodium or Iris coordinate, the version string is wrong — check the exact NeoForge file names at https://modrinth.com/mod/sodium/versions and https://modrinth.com/mod/iris/versions for MC 1.21.1, update the coordinate in `build.gradle`, and re-run.

- [ ] **Step 4: Commit**

```powershell
git add "Custom Mods/src/nvidium-1.21.1/gradle/wrapper/gradle-wrapper.properties"
git commit -m "nvidium(neoforge): bump Gradle wrapper to 9.4.0 for ModDevGradle"
```

---

## Phase 1 — Metadata, mixin config & entry point

### Task 5: Replace `fabric.mod.json` with `neoforge.mods.toml`

**Files:**
- Delete: `<proj>/src/main/resources/fabric.mod.json`
- Create: `<proj>/src/main/resources/META-INF/neoforge.mods.toml`

- [ ] **Step 1: Delete the Fabric metadata**

```powershell
Remove-Item "Custom Mods/src/nvidium-1.21.1/src/main/resources/fabric.mod.json"
```

- [ ] **Step 2: Create `META-INF/neoforge.mods.toml`**

Create `<proj>/src/main/resources/META-INF/neoforge.mods.toml` with:

```toml
modLoader = "javafml"
loaderVersion = "${loader_version}"
license = "LGPL-3.0"

[[mods]]
    modId = "nvidium"
    version = "${version}"
    displayName = "Nvidium"
    description = "Alternate rendering backend for Sodium that uses NVIDIA mesh shaders to render huge amounts of terrain at high framerates. Requires an NVIDIA Turing+ GPU (GTX 1600-series / RTX 2000-series or newer)."
    authors = "Cortex"
    credits = "Contributor: Drouarb"
    logoFile = "assets/nvidium/nvidium.png"

# Preserves Nvidium's "<version>-<commit>" string read in Nvidium.java's static init.
[modproperties.nvidium]
    commit = "${commit}"

[[mixins]]
    config = "nvidium.mixins.json"

[[dependencies.nvidium]]
    modId = "neoforge"
    type = "required"
    versionRange = "[${neoforge_version},)"
    ordering = "NONE"
    side = "CLIENT"

[[dependencies.nvidium]]
    modId = "minecraft"
    type = "required"
    versionRange = "[${minecraft_version},1.21.2)"
    ordering = "NONE"
    side = "CLIENT"

# Required: Nvidium hooks Sodium internals via mixins; it must load AFTER Sodium.
[[dependencies.nvidium]]
    modId = "sodium"
    type = "required"
    versionRange = "[0.6.13,0.7)"
    ordering = "AFTER"
    side = "CLIENT"

# Soft-compat: Iris shader detection (compileOnly at build time).
[[dependencies.nvidium]]
    modId = "iris"
    type = "optional"
    versionRange = "[1.8.0,)"
    ordering = "AFTER"
    side = "CLIENT"
```

- [ ] **Step 3: Commit**

```powershell
git add "Custom Mods/src/nvidium-1.21.1/src/main/resources/fabric.mod.json" "Custom Mods/src/nvidium-1.21.1/src/main/resources/META-INF/neoforge.mods.toml"
git commit -m "nvidium(neoforge): replace fabric.mod.json with neoforge.mods.toml"
```

---

### Task 6: Bump the mixin config compatibility level

**Files:**
- Modify: `<proj>/src/main/resources/nvidium.mixins.json`

The config needs no refmap on NeoForge (Mojmap runtime). The only edit is bumping `compatibilityLevel` from `JAVA_17` to `JAVA_21` to match the toolchain and the other workspace mods (`trmt.mixins.json` uses `JAVA_21`). The package, mixin list, and `injectors` block stay exactly as-is.

- [ ] **Step 1: Edit `nvidium.mixins.json`**

Change the line:

```json
  "compatibilityLevel": "JAVA_17",
```

to:

```json
  "compatibilityLevel": "JAVA_21",
```

Leave everything else (the `package`, `client` array of 21 mixins, `injectors.defaultRequire`) unchanged.

- [ ] **Step 2: Commit**

```powershell
git add "Custom Mods/src/nvidium-1.21.1/src/main/resources/nvidium.mixins.json"
git commit -m "nvidium(neoforge): bump mixin compatibilityLevel to JAVA_21"
```

---

### Task 7: Add the minimal `@Mod` client entry class

**Files:**
- Create: `<proj>/src/main/java/me/cortex/nvidium/NvidiumNeoForge.java`

NeoForge expects a `@Mod` class for the declared `modId`. Nvidium initializes lazily through its mixins (`MixinWindow` → `Nvidium.checkSystemIsCapable()`), so this class holds no logic — it only registers the mod id on the client dist and provides a future hook.

- [ ] **Step 1: Create `NvidiumNeoForge.java`**

```java
package me.cortex.nvidium;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

/**
 * NeoForge entry point. Nvidium does its real initialization lazily from its
 * mixins (see MixinWindow -> {@link Nvidium#checkSystemIsCapable()}); this class
 * exists so NeoForge has a @Mod class for the "nvidium" id on the client.
 */
@Mod(value = "nvidium", dist = Dist.CLIENT)
public class NvidiumNeoForge {
    public NvidiumNeoForge() {
    }
}
```

- [ ] **Step 2: Commit**

```powershell
git add "Custom Mods/src/nvidium-1.21.1/src/main/java/me/cortex/nvidium/NvidiumNeoForge.java"
git commit -m "nvidium(neoforge): add minimal @Mod client entry point"
```

---

## Phase 2 — Loader-API swaps

### Task 8: Port `Nvidium.java` static init to `ModList`

**Files:**
- Modify: `<proj>/src/main/java/me/cortex/nvidium/Nvidium.java`

- [ ] **Step 1: Replace the Fabric imports**

Remove these two lines:

```java
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
```

Add this line (alongside the other imports):

```java
import net.neoforged.fml.ModList;
```

- [ ] **Step 2: Replace the static initializer block**

Replace this block:

```java
    static {
        ModContainer mod = (ModContainer) FabricLoader.getInstance().getModContainer("nvidium").orElseThrow(NullPointerException::new);
        var version = mod.getMetadata().getVersion().getFriendlyString();
        var commit = mod.getMetadata().getCustomValue("commit").getAsString();
        MOD_VERSION = version+"-"+commit;
    }
```

with:

```java
    static {
        var mod = ModList.get().getModContainerById("nvidium")
                .orElseThrow(NullPointerException::new)
                .getModInfo();
        var version = mod.getVersion().toString();
        var commit = String.valueOf(mod.getModProperties().get("commit"));
        MOD_VERSION = version+"-"+commit;
    }
```

(`getModInfo()` returns an `IModInfo`; `var` keeps the import list minimal. `getModProperties()` reads the `[modproperties.nvidium]` table from `neoforge.mods.toml`.)

- [ ] **Step 3: Commit**

```powershell
git add "Custom Mods/src/nvidium-1.21.1/src/main/java/me/cortex/nvidium/Nvidium.java"
git commit -m "nvidium(neoforge): read mod version/commit via ModList instead of FabricLoader"
```

---

### Task 9: Port `NvidiumConfig.java` config dir to `FMLPaths`

**Files:**
- Modify: `<proj>/src/main/java/me/cortex/nvidium/config/NvidiumConfig.java`

- [ ] **Step 1: Replace the Fabric import**

Remove:

```java
import net.fabricmc.loader.api.FabricLoader;
```

Add:

```java
import net.neoforged.fml.loading.FMLPaths;
```

- [ ] **Step 2: Replace `getConfigPath()`**

Replace:

```java
    private static Path getConfigPath() {
        return FabricLoader.getInstance()
                .getConfigDir()
                .resolve("nvidium-config.json");
    }
```

with:

```java
    private static Path getConfigPath() {
        return FMLPaths.CONFIGDIR.get()
                .resolve("nvidium-config.json");
    }
```

(Both resolve to the instance `config/` dir, so existing `nvidium-config.json` files keep working.)

- [ ] **Step 3: Commit**

```powershell
git add "Custom Mods/src/nvidium-1.21.1/src/main/java/me/cortex/nvidium/config/NvidiumConfig.java"
git commit -m "nvidium(neoforge): resolve config dir via FMLPaths instead of FabricLoader"
```

---

### Task 10: Port `IrisCheck.java` mod-loaded check to `ModList`

**Files:**
- Modify: `<proj>/src/main/java/me/cortex/nvidium/sodiumCompat/IrisCheck.java`

- [ ] **Step 1: Replace the Fabric import**

Remove:

```java
import net.fabricmc.loader.api.FabricLoader;
```

Add:

```java
import net.neoforged.fml.ModList;
```

- [ ] **Step 2: Replace the `IRIS_LOADED` initializer**

Replace:

```java
    public static final boolean IRIS_LOADED = FabricLoader.getInstance().isModLoaded("iris");
```

with:

```java
    public static final boolean IRIS_LOADED = ModList.get().isLoaded("iris");
```

(The `net.irisshaders.iris.api.v0.IrisApi` import and the rest of the class stay unchanged — the Iris API package is identical on the NeoForge build.)

- [ ] **Step 3: Commit**

```powershell
git add "Custom Mods/src/nvidium-1.21.1/src/main/java/me/cortex/nvidium/sodiumCompat/IrisCheck.java"
git commit -m "nvidium(neoforge): check Iris presence via ModList instead of FabricLoader"
```

---

## Phase 3 — Compile & runtime verification

### Task 11: Verification gate 1 — clean compile

**Files:** none (verification only)

- [ ] **Step 1: Full build**

Run (PowerShell, from `<proj>`):

```powershell
.\gradlew.bat build
```

Expected: `BUILD SUCCESSFUL`. A jar appears at `<proj>/build/libs/nvidium-0.4.1-beta10-1.21+1.21.1.jar` and is mirrored to `Custom Mods/dist/nvidium/` by `copyJarToDist`.

- [ ] **Step 2: Triage any compile errors against the expected surface**

If compilation fails, the error is almost certainly in one of:
- The three ported files (Tasks 8-10) — recheck imports/signatures.
- A Sodium-targeting mixin whose target moved in the **neoforge** Sodium jar (the spec's top risk). The error will name a missing class/field/method in `net.caffeinemc.mods.sodium.*`. Open the corresponding class in `claude-reference/sodium-1.21.1-stable/` to find the current name/signature and adjust the mixin's `@At`/target. Fix, re-run Step 1.

Do not proceed until Step 1 is `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit (only if a mixin fix was needed in Step 2)**

```powershell
git add -A "Custom Mods/src/nvidium-1.21.1/src/main/java/me/cortex/nvidium/mixin"
git commit -m "nvidium(neoforge): adjust Sodium mixin targets for the neoforge jar"
```

(Skip this commit if no fix was required.)

---

### Task 12: Verification gate 2 — dev client loads with Sodium

**Files:** none (verification only)

- [ ] **Step 1: Launch the dev client**

The `implementation` Sodium dependency is on the dev runtime classpath, so FML should load it as a mod. Run (from `<proj>`):

```powershell
.\gradlew.bat runClient
```

Let it reach the main menu, then close the window.

- [ ] **Step 2: Confirm Nvidium and Sodium both loaded and mixins applied**

In the run log (console output, or `<proj>/run/logs/latest.log`), confirm:
- Sodium is listed among loaded mods (no "missing dependency: sodium" crash).
- No mixin apply failures for `nvidium.mixins.json` (search the log for `Mixin apply failed` / `nvidium`).
- One of Nvidium's capability messages appears: `All capabilities met` / `Enabling Nvidium`, or `Not all requirements met, disabling nvidium`.

- [ ] **Step 3: If Sodium is NOT loaded as a mod in the dev run**

If the client crashes with a missing `sodium` dependency, MDG didn't treat the `implementation` jar as a runtime mod. Add it explicitly to the run by inserting into `build.gradle`'s `runs.client` block:

```groovy
        client {
            client()
            jvmArguments.addAll("-Xmx8G", "-XX:+UseZGC")
        }
```

and add this dependency line:

```groovy
dependencies {
    implementation "maven.modrinth:sodium:mc1.21.1-0.6.13-neoforge"
    additionalRuntimeClasspath "maven.modrinth:sodium:mc1.21.1-0.6.13-neoforge"
    compileOnly "maven.modrinth:iris:1.8.8+1.21.1-neoforge"
}
```

Re-run Step 1. If a fix was made, commit:

```powershell
git add "Custom Mods/src/nvidium-1.21.1/build.gradle"
git commit -m "nvidium(neoforge): put Sodium on the dev runtime classpath"
```

---

### Task 13: Verification gates 3 & 4 — render on hardware + clean fallback

**Files:** none (verification only). **Requires the NVIDIA Turing+ GPU.**

- [ ] **Step 1: Launch and enter a world**

```powershell
.\gradlew.bat runClient
```

Create/open a singleplayer world and let terrain load.

- [ ] **Step 2: Gate 3 — confirm Nvidium is active and terrain renders**

- Log shows `All capabilities met` and `Enabling Nvidium` (not the "disabling" path).
- Open **Video Settings → Sodium options**: the **Nvidium** option page is present (proves `MixinSodiumOptionsGUI` + `ConfigGuiBuilder` work).
- Terrain renders correctly — no missing chunks, no corruption, no garbage geometry. (Visual check; the operator running the GPU confirms.)

- [ ] **Step 3: Gate 4 — confirm clean fallback to Sodium**

- In the Nvidium options page, tick **"Disable nvidium"**.
- Confirm terrain still renders correctly through stock Sodium (no crash, no artifacts). This proves the mixins guard cleanly on `Nvidium.IS_ENABLED`.

- [ ] **Step 4: Record the outcome**

If all gates pass, the standalone port is done. If terrain is broken while Nvidium is enabled, treat it as a debugging task (use superpowers:systematic-debugging): the failure is in the renderer/mixin interaction with the neoforge Sodium build, not the build setup — gates 1 and 2 already passed. Capture the symptom and the relevant log/shader-compile output before changing code.

- [ ] **Step 5: Commit any fixes made during Step 4** (skip if none)

```powershell
git add -A "Custom Mods/src/nvidium-1.21.1/src"
git commit -m "nvidium(neoforge): fix <specific rendering issue found during hardware verify>"
```

---

## Phase 4 — Pack wiring (default-off optional mod)

> Run only after Phase 3 passes. All edits here are in `prism-to-modrinth-sync/config.toml` at the repo root.

### Task 14: Produce a versioned dist jar

**Files:** none (verification only)

- [ ] **Step 1: Confirm the mirrored jar exists**

The `build` from Task 11 already ran `copyJarToDist`. Confirm:

```powershell
Get-ChildItem "Custom Mods/dist/nvidium/"
```

Expected: `nvidium-0.4.1-beta10-1.21+1.21.1.jar` is present. (The workspace `.gitignore` tracks `Custom Mods/dist/**`, so this jar is committable.)

- [ ] **Step 2: Commit the dist jar**

```powershell
git add "Custom Mods/dist/nvidium/"
git commit -m "nvidium(neoforge): add built jar to dist for the publish pipeline"
```

---

### Task 15: Register Nvidium as a custom mod (client-side)

**Files:**
- Modify: `prism-to-modrinth-sync/config.toml`

- [ ] **Step 1: Add a `[[custom_mods]]` block**

After the `deer-diary-patches` block (around line 106 in `config.toml`), insert:

```toml
[[custom_mods]]
name = "nvidium"
source_dir = "../Custom Mods/dist/nvidium"
side = "client"
```

(`side = "client"` — Nvidium is a client rendering mod; it must not go into the server build. Default `source_pattern` `nvidium-*.jar` and `target_dir` `mods` apply.)

- [ ] **Step 2: Add Nvidium's jar to the Packwiz self-host allowlist**

In the `[packwiz.self_host].allowed_globs` array (around line 187), add under the "Own builds:" group:

```toml
    "mods/nvidium-*.jar",
```

- [ ] **Step 3: Commit**

```powershell
git add prism-to-modrinth-sync/config.toml
git commit -m "pack: ship nvidium as a client-side custom mod via packwiz self-host"
```

---

### Task 16: Make Nvidium a default-off optional toggle (with fallback)

**Files:**
- Modify: `prism-to-modrinth-sync/config.toml`
- Investigate: `prism-to-modrinth-sync/src/prism_sync/` (packwiz emit module)

The existing `[packwiz.optional_mods]` keys on a Prism `.pw.toml` slug; Nvidium is a self-hosted custom jar with **no** `.pw.toml`, so it is unknown whether the pipeline injects the `[option]` block into a self-hosted custom mod's emitted metafile. This task determines that, then takes one of two paths.

- [ ] **Step 1: Find the optional-mod emit logic**

Search the pipeline source for where `optional_mods` is consumed:

```powershell
Select-String -Path "prism-to-modrinth-sync/src/prism_sync/*.py" -Pattern "optional_mods|\[option\]|optional"
```

Read the function that emits packwiz metafiles and applies the `[option]` block. Determine: does it match `optional_mods` keys against **self-hosted/custom** metafiles (by basename/slug), or only against Modrinth `.pw.toml`-derived metafiles?

- [ ] **Step 2a: If self-hosted custom mods CAN be optional** — add the toggle

Add to `[packwiz.optional_mods]` (around line 176, next to `sodium-dynamic-lights`):

```toml
nvidium = { default = false, description = "Nvidium - NVIDIA mesh-shader terrain renderer for Sodium. Massive FPS gains at high render distances, but ONLY works on NVIDIA Turing+ GPUs (GTX 1600-series / RTX or newer). Safe to leave off; it self-disables on unsupported hardware. Leave unchecked unless you have a supported NVIDIA card." }
```

Use whatever slug the metafile actually emits as the key (confirm in Step 1 — likely `nvidium`, matching `mods/nvidium-*.jar`).

- [ ] **Step 2b: If self-hosted custom mods CANNOT be optional** — ship always-on, document

Do **not** add an `optional_mods` entry (it would silently do nothing). Nvidium then ships always-enabled but **self-disables at runtime** on non-NVIDIA/unsupported hardware (`Nvidium.checkSystemIsCapable()` → `IS_ENABLED = false`), so it is harmless for those players. Record this outcome in the run notes and flag the "optional custom mod" pipeline feature as a separate follow-up (out of scope for this plan).

- [ ] **Step 3: Dry-run the Packwiz pipeline to verify emission**

Run (from `prism-to-modrinth-sync`, PowerShell):

```powershell
.\prism_sync packwiz-check --changelog
```

Expected: the changelog/diff lists `mods/nvidium-...jar` as a new self-hosted file with no errors. If Step 2a was taken, confirm (per Step 1's findings) that the emitted `docs/packwiz/mods/nvidium-*.pw.toml` would carry an `[option]` block (inspect the generated metafile after a `packwiz-build` if needed).

- [ ] **Step 4: Commit**

```powershell
git add prism-to-modrinth-sync/config.toml
git commit -m "pack: expose nvidium as a default-off optional mod"
```

(If Step 2b was taken, the commit message is `pack: note nvidium ships always-on (self-disables on unsupported GPUs)` and only includes any documentation changes.)

---

### Task 17: Final pack sanity check

**Files:** none (verification only)

- [ ] **Step 1: Confirm the custom-mod sync picks up Nvidium**

Run (from `prism-to-modrinth-sync`):

```powershell
.\prism_sync packwiz-check --changelog
```

Expected: no errors; `nvidium` appears in the Mods section of the changelog. Do **not** pass `--push` — publishing is a separate, deliberate step the user runs.

- [ ] **Step 2: Report completion**

Summarize: standalone NeoForge build passes gates 1-4, jar is in `dist/`, and the pack config ships it client-side (optional toggle if Task 16 Step 2a applied, else always-on/self-disabling). Leave the actual `--push` publish to the user.

---

## Self-Review

**Spec coverage:**
- Toolchain (loom→MDG, deps, Modrinth maven) → Tasks 1-4. ✓
- Metadata & mixin registration (`mods.toml`, `[modproperties]`, `[[mixins]]`, drop refmap, `@Mod`) → Tasks 5-7. ✓
- Three loader-API swaps (Nvidium/NvidiumConfig/IrisCheck) → Tasks 8-10. ✓
- Verification gates 1-4 → Tasks 11-13. ✓
- Pack wiring (dist mirror, `[[custom_mods]]`, self-host glob, optional toggle + fallback, packwiz-check) → Tasks 14-17. ✓
- Spec risks (Sodium mixin targets; dev-runtime resolution; optional-custom-mod pipeline gap) → addressed in Task 11 Step 2, Task 12 Step 3, Task 16 respectively. ✓

**Placeholder scan:** No "TBD"/"add error handling"/vague steps; every code/edit step shows exact content. The one genuinely conditional branch (Task 16) is a documented investigate-then-choose with both concrete outcomes specified. ✓

**Type/name consistency:** `mod_id=nvidium` across gradle.properties, mods.toml, `@Mod(value="nvidium")`, `ModList.get().getModContainerById("nvidium")`; `commit` property name consistent between `build.gradle` processResources, `[modproperties.nvidium].commit`, and `Nvidium.java`'s `getModProperties().get("commit")`; dist path `../../dist/nvidium` (build.gradle) ↔ `../Custom Mods/dist/nvidium` (config.toml, resolved from the pipeline dir) ↔ self-host glob `mods/nvidium-*.jar`. ✓
