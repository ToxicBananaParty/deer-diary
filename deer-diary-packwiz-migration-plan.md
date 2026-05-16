# Deer Diary: Migration Plan from Modrinth `.mrpack` Publishing to Packwiz Auto-Updating Prism Distribution

> **Status:** Amended 2026-05-16 after a workspace-research pass. The original plan
> is preserved below; corrections and concrete details live in **§ Amendments
> after workspace research** immediately below the Goal. Read the amendments
> first, then read the rest as background context.

## Goal

Migrate the Deer Diary modpack distribution workflow from:

```text
Prism instance -> custom Python pipeline -> .mrpack -> Modrinth publish
```

to:

```text
Prism instance -> custom Python pipeline -> Packwiz web tree -> GitHub Pages/static hosting -> Prism pre-launch auto-update
```

The desired player experience:

1. Player imports a small Prism instance `.zip` once.
2. Player clicks Play in Prism.
3. Prism runs Packwiz Installer before Minecraft starts.
4. Packwiz compares the hosted `pack.toml` / `index.toml` against local files.
5. Packwiz downloads changed mods/configs/resource packs/shaders/custom files.
6. Minecraft launches with the current Deer Diary pack.

Do **not** delete the existing Modrinth pipeline initially. Build this in parallel, prove it, then decide whether to retire or keep `.mrpack` publishing.

---

## Amendments after workspace research

The original plan below is sound. These are the corrections and concrete
hooks discovered after reading the existing codebase, the live Prism
instance, and Packwiz's current format reference.

### A1. Prism's `.pw.toml` files are *already* valid Packwiz metafiles

The plan's Phase 3 talks about "converting" Modrinth-backed Prism mods to
Packwiz metafiles. They're already in the right format. A sample from the
live instance ([3dskinlayers.pw.toml](Deer Diary/minecraft/mods/.index/3dskinlayers.pw.toml)):

```toml
filename = 'skinlayers3d-neoforge-1.11.1-mc1.21.1.jar'
name = '3D Skin Layers'
side = 'client'
x-prismlauncher-dependencies = []
x-prismlauncher-loaders = [ 'neoforge' ]
x-prismlauncher-mc-versions = [ '1.21.1' ]
x-prismlauncher-release-type = 'release'
x-prismlauncher-version-number = '1.11.1'

[download]
hash = 'cdd870...cee4'
hash-format = 'sha512'
mode = 'url'
url = 'https://cdn.modrinth.com/data/zV5r3pPn/versions/lWa5oHuK/skinlayers3d-neoforge-1.11.1-mc1.21.1.jar'

[update.modrinth]
mod-id = 'zV5r3pPn'
version = 'lWa5oHuK'
```

Every required Packwiz field (`name`, `filename`, `[download]` with
`url`/`hash`/`hash-format`) is present, plus the optional `side` and
`[update.modrinth]`. The `x-prismlauncher-*` fields are namespaced
extensions Packwiz can safely ignore.

**Implication:** Phase 3 is not "convert", it's "copy" (with a sanity
check). The generator should:

1. Read `<instance>/minecraft/<sub>/.index/*.pw.toml`.
2. Re-hash the on-disk jar; verify SHA512 matches the metafile's recorded
   hash. (The existing pipeline already does this — see [mrpack.py:255-280](prism-to-modrinth-sync/src/prism_sync/mrpack.py).
   On mismatch, log a warning and skip the copy so we don't ship stale
   metadata.)
3. Write the metafile to `docs/packwiz/<sub>/<slug>.pw.toml`. Optionally
   strip the `x-prismlauncher-*` keys — they're harmless but noisy.
4. Add an `index.toml` entry: `metafile = true`, hash = sha256/sha512 of
   the written metafile bytes.

We do **not** need network lookups for Modrinth-resolved mods in the
common case — Prism already cached everything we need.

### A2. CurseForge-only `.pw.toml` files — self-host with permission

Existing pipeline behavior ([mrpack.py:127-131](prism-to-modrinth-sync/src/prism_sync/mrpack.py))
routes CF-only metafiles (`mode = "metadata:curseforge"`) to overrides.
For Packwiz the same metafiles are technically valid, but they'd force
every player to configure a CurseForge API key in packwiz-installer —
ugly UX.

**Workspace check:** the live instance has **4 CF-only mods**:

- `mods/.index/alltheleaks.pw.toml`
- `mods/.index/configured.pw.toml`
- `mods/.index/extended-beacon-range.pw.toml`
- `mods/.index/iron-chests.pw.toml`

**Decision (revised 2026-05-16):** Self-host all four. Ryan has explicit
redistribution permission from the upstream authors, so the licensing
worry disappears. The `[packwiz.self_host].allowed_globs` list takes
priority over `.pw.toml` metafiles in
[packwiz.py:`build_packwiz`](prism-to-modrinth-sync/src/prism_sync/packwiz.py),
so adding glob entries for each CF jar is enough; the metafiles are
ignored and the jars ship as direct files.

This is also the correct rule even without the CF backstory: if a jar is
in `allowed_globs`, that's an explicit "ship the jar, not a metafile"
directive from the maintainer.

We do **not** rewrite the `.pw.toml` files in place. Prism keeps managing
them like it always has; we just don't propagate them to the published
tree for these jars.

Currently no CF mods are emitted as metafiles. If a future mod lands
that's CF-only AND not in `allowed_globs`, it would emit as a CF metafile
(and the player would need an API key). The build's summary surfaces
those as "CurseForge metafiles" so you notice.

### A3. Hash format strategy

Packwiz `index.toml` requires a root `hash-format` and accepts per-file
overrides. The current hasher
([hashing.py:8-27](prism-to-modrinth-sync/src/prism_sync/hashing.py))
only computes `sha1` + `sha512`. Two paths:

- **Recommended:** Extend `hash_file()` to also compute `sha256`. Use
  `sha256` as the root format in `index.toml` (shorter hashes → smaller
  index file → friendlier diffs in git). Keep `.pw.toml` metafile content
  on `sha512` (it's already there — no re-hash needed).
- Alternative: Use `sha512` throughout. Zero new code, but ~2× larger
  `index.toml`.

Going with the first. Extend `FileHashes` to `(size, sha1, sha256, sha512)`,
add the sha256 stream to `hash_file()`. mrpack code paths ignore sha256
naturally because they read `hashes.sha1` / `hashes.sha512`.

### A4. Reuse boundary: extract `.pw.toml` reader from `mrpack.py`

The `.pw.toml` index reader lives at
[mrpack.py:100-124 `_read_pw_toml_indexes`](prism-to-modrinth-sync/src/prism_sync/mrpack.py)
as a private function. The Packwiz generator needs the same data, so
move that reader (and the small `_pw_toml_*` helpers at lines 127-151)
into a new shared module:

```
prism-to-modrinth-sync/src/prism_sync/pwtoml.py
```

Both `mrpack.py` and the new `packwiz.py` import from it. No behavior
change; pure refactor.

### A5. State file and changelog: share with Modrinth pipeline

The plan implies a Packwiz-specific state file. **Don't.** Both pipelines
walk the same instance and apply the same ignore rules, so the *content*
of the fingerprint should be the same regardless of which pipeline ran
last. Sharing the file makes pipeline switches cheap.

**The non-obvious gotcha:** the natural fingerprint key for the mrpack
pipeline is the *deliverable's* path (e.g. `mods/foo.jar`), while the
natural one for the Packwiz pipeline is the *index entry's* path (e.g.
`mods/foo.pw.toml`). If you key Packwiz by the metafile path, every
mrpack→packwiz switch reports every mod as "removed + added" because
the keys don't overlap.

**Fix used in the implementation:** Packwiz fingerprints key by the
*deliverable's* path with the *deliverable's* SHA-512 as the value
— same shape as mrpack. For a Modrinth metafile, that's
`mods/foo.jar → sha512(foo.jar from disk)`; for a self-hosted file, it's
the file's own path and hash. The .pw.toml's own bytes get hashed for
the `index.toml` entry, but never enter the state fingerprint.

Same call for `CHANGELOG.md`: one file, with `## YYYY.MM.DD (packwiz)`
in the section header. Use `render_file_entry()` from
[changelog.py:88-115](prism-to-modrinth-sync/src/prism_sync/changelog.py)
with `new_version=f"{version} (packwiz)"`.

`PublishedState.notes` distinguishes the source: `{"published_via":
"packwiz"}` vs `{"modrinth_version_id": ..., "modrinth_status": ...}`.

### A6. Don't shell out to the `packwiz` binary

The original plan suggested an optional `run_packwiz_refresh = true` /
`packwiz_exe = "packwiz"` config. **Drop it.** We can write a fully
valid pack tree from Python alone; adding a Go-CLI dependency for Ryan
and CI is unnecessary friction. The only thing `packwiz refresh` would
do is recompute hashes and rewrite `index.toml` — and we already have the
hashes at write time.

Optional later add: a `packwiz-validate` subcommand that, if `packwiz` is
on `PATH`, runs `packwiz refresh --dry-run` (or whatever the equivalent
validator is) against our output as a sanity check. Not required for MVP.

### A7. `pack_format` naming in config vs. output

The original config sample uses `pack_format = "packwiz:1.1.0"`. Keep
snake_case in `config.toml` (consistent with the rest of the file) but
emit the actual key as `pack-format` (hyphenated) in `docs/packwiz/pack.toml`.
Same for `hash-format` in `index.toml`. The Python TOML writer needs to be
careful here — `tomli_w` will preserve whatever keys we give it.

### A8. The `[versions]` key for NeoForge

Packwiz supports NeoForge in `pack.toml`'s `[versions]` table under the
key `neoforge` (confirmed by packwiz's CLI flag `--neoforge-version` /
`--neoforge-latest`, recent commits in late 2025 that disambiguated the
NeoForge versioning format, and the packwiz-installer release notes for
v0.5.14 mentioning MultiMC NeoForge metadata updates).

```toml
[versions]
minecraft = "1.21.1"
neoforge = "21.1.228"
```

Read the version from `mmc-pack.json` via the existing `read_instance()`
([instance.py:57-99](prism-to-modrinth-sync/src/prism_sync/instance.py)) —
`InstanceInfo.loader.mrpack_key` already returns `"neoforge"` for NeoForge
instances. Reuse that key.

### A9. Bootstrap pre-launch command — verified syntax

Prism Launcher's pre-launch hook supports `$INST_JAVA` and `$INST_MC_DIR`.
For NeoForge on Windows the working invocation is:

```
"$INST_JAVA" -jar "$INST_MC_DIR/packwiz-installer-bootstrap.jar" "https://toxicbananaparty.github.io/deer-diary/packwiz/pack.toml"
```

- Forward slashes work cross-platform in Prism's command string.
- **Do NOT add `-g`.** The `-g` flag *disables* the GUI (it's for headless
  server installs — `java -jar ... -g -s server <url>`). Adding it on a
  client install means the Swing option-select dialog never appears, and
  packwiz-installer falls back to "enable all optional mods" regardless
  of each metafile's `default = false`. javaw.exe + no flag = GUI shows
  correctly.
- Quote everything that contains substitutions — Prism's quote handling
  has known historical issues
  ([PrismLauncher#1134](https://github.com/PrismLauncher/PrismLauncher/issues/1134)),
  and the username path almost certainly has a hyphen.

The bootstrap jar pins to a release tag — don't use `latest/download`
in the build pipeline. Pin to a specific release version in config
(e.g. `packwiz-installer-bootstrap v0.0.3`, latest as of 2026-05-16) and
update deliberately.

### A10. The `Custom Mods/dist/key-to-necklace` datapack — decision needed

The plan lists `key-to-necklace.zip` as a self-hosted artifact under
`moonlight-global-datapacks/`. The live instance's
`moonlight-global-datapacks/` is **empty**, and the corresponding
`[[custom_mods]]` block in
[config.toml:89-93](prism-to-modrinth-sync/config.toml)
is commented out.

**Decision needed (Open Question O7 below):** Ship the datapack with the
Packwiz pack (un-comment the `[[custom_mods]]` block and add it to the
`packwiz.self_host.allowed_globs`)? Or leave the datapack out of the
auto-update flow for now? Default: leave it out of the MVP; revisit if
Ryan wants it auto-shipped.

### A11. Concrete reusable surface from existing code

The Packwiz generator should pull from these existing pieces. None of
them need behavior changes; only the `pwtoml.py` extraction in A4 and the
`hashing.py` extension in A3 are required modifications.

| Need | Existing symbol |
|---|---|
| Load config + secrets | [`load_config()` in config.py:63](prism-to-modrinth-sync/src/prism_sync/config.py) |
| Resolve instance dir + MC/loader version | [`read_instance()` in instance.py:57](prism-to-modrinth-sync/src/prism_sync/instance.py) |
| Walk pack files honoring ignores + optional allowlist | [`walk_pack_files()` in instance.py:202](prism-to-modrinth-sync/src/prism_sync/instance.py) |
| Read `.packignore` | [`read_packignore()` in instance.py:102](prism-to-modrinth-sync/src/prism_sync/instance.py) |
| Glob ignore matching | [`IgnoreMatcher` in instance.py:157](prism-to-modrinth-sync/src/prism_sync/instance.py) |
| Sync custom mod jars into instance pre-build | [`sync_custom_mods()` in customs.py:118](prism-to-modrinth-sync/src/prism_sync/customs.py) |
| Hash a file | [`hash_file()` in hashing.py:15](prism-to-modrinth-sync/src/prism_sync/hashing.py) (extend for sha256) |
| Diff two fingerprints | [`diff_fingerprints()` in diff.py:21](prism-to-modrinth-sync/src/prism_sync/diff.py) |
| Render markdown changelog | [`render_changelog()` / `render_file_entry()` in changelog.py:65,88](prism-to-modrinth-sync/src/prism_sync/changelog.py) |
| Read/write published state | [`PublishedState` + `load_state` / `save_state` in state.py](prism-to-modrinth-sync/src/prism_sync/state.py) |
| Git stage/commit/push via WSL | [`gitutil.stage` / `commit` / `push` in gitutil.py](prism-to-modrinth-sync/src/prism_sync/gitutil.py) |
| Read `.pw.toml` indexes | [`_read_pw_toml_indexes()` in mrpack.py:100](prism-to-modrinth-sync/src/prism_sync/mrpack.py) — **move to new `pwtoml.py`** |
| Test if a `.pw.toml` is CF-only | [`_pw_toml_is_curseforge_only()` in mrpack.py:127](prism-to-modrinth-sync/src/prism_sync/mrpack.py) — **move to new `pwtoml.py`** |

### A12. Atomic output on Windows

The plan suggests `docs/packwiz.tmp/` → `docs/packwiz/`. Windows
directory rename isn't atomic if the destination exists. The
right-enough pattern is:

```python
tmp = output_dir.with_suffix(".tmp")
if tmp.exists():
    shutil.rmtree(tmp)
# ...build into tmp...
if output_dir.exists():
    shutil.rmtree(output_dir)
tmp.rename(output_dir)
```

There's a millisecond gap where neither directory exists. That's fine
for this use case — the publish step runs in series with no concurrent
readers, and the post-rename git commit makes the transition atomic
from the perspective of GitHub Pages.

### A13. Status of `docs/`, GH Pages, gh CLI

- `docs/` does not exist in the workspace. The build will create it.
- Workspace `.gitignore` does not exclude `docs/`. Adding tracked files
  there will Just Work.
- Git remote is confirmed: `git@github.com:ToxicBananaParty/deer-diary.git`.
- `gh` is not installed in WSL, so we cannot automate the GH Pages
  enable step — Ryan will do that once via the web UI. The pipeline only
  needs to push commits; GitHub Pages picks them up automatically.

### A14. The `.disabled` optional renderer — keep as direct file for MVP

The original plan's Phase 2 (native Packwiz `[option]` mod) is more user-
friendly but requires the packwiz-installer GUI to surface the toggle.
For MVP, ship `sodiumdynamiclights-neoforge-1.0.10-1.21.1.jar.disabled`
as a direct file in `mods/` (same behavior as today's mrpack). The
existing `optional_files` allowlist already opts it through the ignore
filter. Players enable by stripping `.disabled` — same UX as today.

Promote to native `[option]` in a follow-up only if Ryan wants the
prompt-on-install UX.

### A15. Open questions, restated and refined

The plan's tail has five open questions. Restated against the workspace,
the ones I still need answers on:

- **O1.** GH Pages from `docs/` on `main` — confirm this is the host. (My
  default if no answer: yes.)
- **O2.** Server-side Packwiz updates — out of scope for MVP. (Default:
  defer.)
- **O3.** Promote the `.disabled` Sodium Dynamic Lights to a native
  Packwiz `[option]`? (Default: no, ship as `.disabled` like today.)
- **O4.** Mark any configs `preserve = true` so players can keep local
  edits across updates? (Default: no — pack-controlled configs win on
  update. Player tweaks belong in `defaultconfigs/` or saves.)
- **O5.** Once the Packwiz path is proven, retire `publish.bat` /
  Modrinth, or run both indefinitely? (Default: keep both; cost is
  near-zero and Modrinth gives a search-discoverability surface.)
- **O6.** ~~Any CurseForge-only mods left in the live instance?~~
  **Resolved (A2, revised):** Yes — alltheleaks, configured,
  extended-beacon-range, iron-chests. **Self-hosted** under
  `[packwiz.self_host].allowed_globs` (Ryan has redistribution
  permission from the upstream authors). No CF API key needed in the
  bootstrap instance.
- **O7.** ~~Auto-ship the `key-to-necklace` datapack?~~ **Resolved: yes,
  ship it.** Uncomment `[[custom_mods]]` block in
  [config.toml:89-93](prism-to-modrinth-sync/config.toml), add
  `moonlight-global-datapacks/key-to-necklace.zip` to the Packwiz
  self-host allowlist. The zip is already built at
  `Custom Mods/dist/key-to-necklace/key-to-necklace.zip` (2266 bytes).

**Confirmed defaults (2026-05-16):** O1=yes, O2=defer, O3=keep `.disabled`,
O4=no preserve, O5=keep both pipelines, O6=4 CF mods → native CF metafiles,
O7=ship datapack.

---

## Current Repo / Workflow Assumptions

Use the existing repo layout and workflow as the migration base:

```text
MC Stuff/
├── CLAUDE.md
├── publish.bat
├── Deer Diary/                         # gitignored symlink to live Prism instance
├── prism-to-modrinth-sync/             # existing Python release pipeline
│   ├── src/prism_sync/
│   ├── config.toml
│   ├── config.local.toml               # gitignored secrets
│   ├── .last-published-state.json
│   ├── CHANGELOG.md
│   └── dist/*.mrpack
└── Custom Mods/
    ├── src/
    └── dist/                           # tracked built jars/zips consumed by pipeline
```

Current source of truth is the live Prism instance:

```text
../Deer Diary
```

Keep that for the MVP. The user edits the pack in Prism like normal. The pipeline should read the Prism instance and generate Packwiz output.

---

## Recommended Architecture

### MVP Architecture

```text
Live Prism Instance
      ↓
existing/custom Python sync tool
      ↓
generated Packwiz directory
      ↓
GitHub Pages / static hosting
      ↓
Packwiz Installer Bootstrap in Prism pre-launch
      ↓
players auto-update on launch
```

### Proposed Generated Output

Add a generated static web directory:

```text
docs/
└── packwiz/
    ├── pack.toml
    ├── index.toml
    ├── mods/
    │   ├── create.pw.toml
    │   ├── sodium.pw.toml
    │   ├── deer-diary-commands-X.Y.Z-1.21+1.21.1.jar
    │   └── trmt-X.Y.Z-1.21+1.21.1.jar
    ├── config/
    ├── defaultconfigs/
    ├── resourcepacks/
    ├── shaderpacks/
    └── moonlight-global-datapacks/
```

Use GitHub Pages to serve:

```text
https://toxicbananaparty.github.io/deer-diary/packwiz/pack.toml
```

Actual final URL depends on GitHub Pages settings.

---

## Important Design Decision

For the first pass, **do not make Packwiz the authoring source of truth**.

Instead:

- Prism remains the authoring UI.
- `prism_sync` remains the pack scanner.
- Add a new command that emits a Packwiz-compatible web tree.

Later, after Packwiz generation is stable, decide whether to flip the model and make `pack.toml` / `index.toml` the canonical source.

---

## New CLI Commands

Add these commands to `prism_sync`:

```bash
prism_sync packwiz-build
prism_sync packwiz-check
prism_sync packwiz-publish
```

### `packwiz-build`

Builds the Packwiz tree locally.

Responsibilities:

1. Sync custom mods into the Prism instance using the existing custom-mod logic.
2. Read the Prism instance metadata:
   - `mmc-pack.json`
   - `instance.cfg`
   - `.minecraft/mods/`
   - `.minecraft/mods/.index/`
   - configured include paths
3. Apply existing include/ignore behavior from `config.toml`.
4. Generate Packwiz files into `docs/packwiz/`.
5. Run `packwiz refresh` or generate `index.toml` hashes directly.
6. Fail if forbidden files would be shipped.

### `packwiz-check`

Dry-run validation command.

Responsibilities:

1. Build to a temporary directory.
2. Validate Packwiz structure.
3. Print:
   - number of indexed files
   - number of Modrinth-backed metafiles
   - number of self-hosted files
   - number of optional files
   - skipped files
   - forbidden files
4. Do not modify `docs/packwiz/`.

### `packwiz-publish`

Production command.

Responsibilities:

1. Run `packwiz-build`.
2. Append changelog using existing diff machinery.
3. Commit and push generated Packwiz output.
4. Optionally tag the release.
5. Do **not** call Modrinth APIs.

---

## Config Changes

Extend `prism-to-modrinth-sync/config.toml` with a new `[packwiz]` table.

Example:

```toml
[packwiz]
enabled = true

# Generated web root. Prefer docs/ so GitHub Pages can serve it from main.
output_dir = "../docs/packwiz"

# Final public URL used by Prism pre-launch command.
# This is the URL to pack.toml after GitHub Pages is enabled.
base_url = "https://toxicbananaparty.github.io/deer-diary/packwiz"

# Pack metadata.
pack_format = "packwiz:1.1.0"
author = "ToxicBananaParty"
version = "2026.05.16"

# Whether to call external packwiz binary after generation.
run_packwiz_refresh = true
packwiz_exe = "packwiz"

# Bootstrap instance export.
bootstrap_instance_name = "Deer Diary"
bootstrap_export_dir = "../dist"

# Installer bootstrap jar to place in the Prism instance minecraft folder.
installer_bootstrap_filename = "packwiz-installer-bootstrap.jar"
installer_bootstrap_url = "https://github.com/packwiz/packwiz-installer-bootstrap/releases/latest/download/packwiz-installer-bootstrap.jar"
```

Preserve existing sections:

```toml
include_paths = [
  "mods",
  "config",
  "resourcepacks",
  "shaderpacks",
  "defaultconfigs",
  "moonlight-global-datapacks",
]

extra_ignore = [
  # existing ignore entries
]

optional_files = [
  # existing optional entries
]

[[custom_mods]]
# existing custom mod entries
```

The Packwiz generator should reuse these lists.

---

## Packwiz File Generation Rules

### 1. Generate `pack.toml`

Create:

```toml
name = "Deer Diary"
author = "ToxicBananaParty"
version = "YYYY.MM.DD"
pack-format = "packwiz:1.1.0"

[index]
file = "index.toml"
hash-format = "sha256"
hash = "<sha256 of index.toml>"

[versions]
minecraft = "1.21.1"
neoforge = "21.1.228"
```

Get Minecraft and NeoForge versions from `mmc-pack.json` / Prism metadata, not hardcoded, if possible.

If Packwiz has issues with `neoforge` as a version key, inspect current Packwiz support and adjust to the expected loader key. Prefer native Packwiz output from `packwiz init --modloader neoforge` as reference.

---

### 2. Generate `index.toml`

Packwiz `index.toml` should reference every file in the hosted pack.

There are two categories:

#### A. Direct hosted files

Use direct indexed files for files physically present in `docs/packwiz/`, such as:

```text
config/**
defaultconfigs/**
resourcepacks/**
shaderpacks/**
moonlight-global-datapacks/**
custom mod jars
custom datapack zips
modified TRMT jar
deer-diary-commands jar
```

Example entry:

```toml
[[files]]
file = "config/some-config.toml"
hash = "<sha256>"
```

#### B. Metafiles

Use `.pw.toml` metafiles for externally downloaded mods, especially Modrinth-hosted mods.

Example index entry:

```toml
[[files]]
file = "mods/create.pw.toml"
hash = "<sha256>"
metafile = true
```

---

### 3. Convert Modrinth-backed Prism mods to Packwiz metafiles

For mods already installed through Prism / Modrinth, inspect their `.pw.toml` metadata under:

```text
.minecraft/mods/.index/
```

The existing pipeline already reads these for `.mrpack` generation, so reuse that parser where possible.

Emit a Packwiz metafile like:

```toml
name = "Create"
filename = "create-1.21.1-6.0.10.jar"
side = "both"

[download]
url = "https://cdn.modrinth.com/data/.../create-1.21.1-6.0.10.jar"
hash-format = "sha512"
hash = "<sha512>"

[update.modrinth]
mod-id = "<modrinth project id>"
version = "<modrinth version id>"
```

Rules:

- Preserve exact filename.
- Preserve exact hash.
- Preserve Modrinth project/version IDs if available.
- Preserve side if known.
- If side is unknown, default to `both`.
- Add a manual side override table later if needed.

---

### 4. Handle custom / unresolved files

For files that can legally and technically be self-hosted:

```text
Custom Mods/dist/trmt-neoforge-1.21.1/*.jar
Custom Mods/dist/deer-diary-commands/*.jar
Custom Mods/dist/key-to-necklace/*.zip
other original/redistributable pack files
```

Copy them into `docs/packwiz/` and index them as direct hosted files.

Example:

```text
docs/packwiz/mods/deer-diary-commands-X.Y.Z-1.21+1.21.1.jar
docs/packwiz/mods/trmt-X.Y.Z-1.21+1.21.1.jar
```

Do not use `.pw.toml` unless there is an external stable download URL.

---

### 5. Handle CurseForge-only / restricted / forbidden jars

The Packwiz migration must not become a sneaky copyright bypass.

Rules:

- Keep the existing FTB Essentials / FTB Library exclusions.
- Keep excluding FTB configs and leftover FTB files.
- If a jar is not redistributable and has no legally usable direct URL, fail the build.
- Do not blindly host every unresolved jar.
- Print a clear error listing unresolved jars and their paths.

Suggested failure text:

```text
ERROR: Refusing to self-host unresolved mod jar:
  mods/example.jar

Reason:
  No Modrinth metadata was found, no allowed self-host rule matched, and redistribution status is unknown.

Fix:
  - Add it through Packwiz/Modrinth metadata,
  - add an explicit allowed_self_host entry,
  - or remove it from the pack.
```

Add config:

```toml
[packwiz.self_host]
allowed_globs = [
  "mods/deer-diary-commands-*.jar",
  "mods/trmt-*.jar",
  "moonlight-global-datapacks/key-to-necklace.zip",
]
```

---

### 6. Optional files

Current pipeline has:

```toml
optional_files = [
  "mods/sodiumdynamiclights-neoforge-1.0.10-1.21.1.jar.disabled",
]
```

For Packwiz, prefer native optional metadata instead of shipping `.disabled` files.

Emit a `.pw.toml` like:

```toml
name = "Sodium Dynamic Lights"
filename = "sodiumdynamiclights-neoforge-1.0.10-1.21.1.jar"
side = "client"

[download]
url = "<url>"
hash-format = "sha512"
hash = "<hash>"

[option]
optional = true
default = false
description = "Alternative low-GPU dynamic lights option. Enable only if you are not using the main Iris/Colorwheel setup."
```

If automatic conversion is too much for the MVP, keep the `.disabled` direct-file behavior for now and add native optional conversion as Phase 2.

---

## New Files to Add

### `docs/packwiz/.gitignore`

Generated Packwiz output should probably be tracked if GitHub Pages serves from `main/docs`.

Do **not** ignore the generated files if using GitHub Pages from `docs`.

Instead, add comments explaining that `docs/packwiz` is generated but intentionally tracked.

---

### `publish-packwiz.bat`

Add a double-clickable wrapper next to `publish.bat`.

```bat
@echo off
rem Double-clickable wrapper for the Packwiz release pipeline.
setlocal
title Deer Diary - packwiz-publish

echo Running prism_sync packwiz-publish...
echo.

cd /d "%~dp0prism-to-modrinth-sync"
call prism_sync.cmd packwiz-publish

set EXITCODE=%ERRORLEVEL%
echo.
echo ============================================================
if %EXITCODE% EQU 0 (
  echo Packwiz pipeline finished successfully ^(exit code 0^).
) else (
  echo Packwiz pipeline FAILED with exit code %EXITCODE%.
)
echo ============================================================
pause
```

---

### `PACKWIZ.md`

Add human documentation:

```markdown
# Deer Diary Packwiz Distribution

## Player install

1. Install Prism Launcher.
2. Import the Deer Diary bootstrap instance zip.
3. Click Play.
4. The pack auto-updates before Minecraft launches.

## Maintainer release

1. Edit the live Prism instance normally.
2. Build custom mods into `Custom Mods/dist/**`.
3. Run `publish-packwiz.bat`.
4. Confirm GitHub Pages has updated.
5. Test with a clean Prism instance.

## Hosted pack URL

`https://toxicbananaparty.github.io/deer-diary/packwiz/pack.toml`
```

---

## Bootstrap Prism Instance

Create a small Prism instance zip that contains:

```text
Deer Diary/
├── instance.cfg
├── mmc-pack.json
└── .minecraft/
    └── packwiz-installer-bootstrap.jar
```

The instance should **not** include all mods. It only needs the correct Minecraft / NeoForge metadata, memory settings, icon if desired, and the Packwiz pre-launch command.

Set Prism pre-launch command to:

```bash
"$INST_JAVA" -jar "$INST_MC_DIR/packwiz-installer-bootstrap.jar" https://toxicbananaparty.github.io/deer-diary/packwiz/pack.toml
```

If the jar is placed directly in `.minecraft`, this should work because `$INST_MC_DIR` points at the instance Minecraft folder.

If Windows quoting causes trouble, use:

```bash
"$INST_JAVA" -jar "$INST_MC_DIR\packwiz-installer-bootstrap.jar" https://toxicbananaparty.github.io/deer-diary/packwiz/pack.toml
```

Prefer the forward-slash version first.

---

## GitHub Pages Setup

Recommended:

1. Use `docs/` on `main` as the GitHub Pages source.
2. Generated Packwiz files live under:

```text
docs/packwiz/
```

3. Final URL should be:

```text
https://toxicbananaparty.github.io/deer-diary/packwiz/pack.toml
```

4. Add a validation command that checks this URL after push.

Possible simple validation:

```powershell
Invoke-WebRequest "https://toxicbananaparty.github.io/deer-diary/packwiz/pack.toml" -UseBasicParsing
```

Do not make the local build depend on GitHub Pages being live.

---

## Implementation Phases

## Phase 0 — Inventory and Safety

1. Read existing `prism_sync` modules:
   - `cli.py`
   - `config.py`
   - `customs.py`
   - `instance.py`
   - `mrpack.py`
   - `diff.py`
   - `publish.py`
   - `remote.py`
   - `state.py`

2. Identify reusable pieces:
   - config loading
   - instance path resolution
   - include path walking
   - `.packignore` parsing
   - `extra_ignore`
   - `optional_files`
   - custom mod sync
   - hash utilities
   - changelog/diff machinery
   - git commit/push machinery

3. Add tests or at least fixture-style dry runs before modifying publishing behavior.

4. Confirm no forbidden FTB files are present in generated Packwiz output.

---

## Phase 1 — Generate Packwiz Tree Locally

Add a new module:

```text
prism-to-modrinth-sync/src/prism_sync/packwiz.py
```

Suggested classes/functions:

```python
@dataclass
class PackwizBuildResult:
    output_dir: Path
    pack_toml: Path
    index_toml: Path
    files_indexed: int
    metafiles_indexed: int
    direct_files_indexed: int
    skipped_files: list[str]
    warnings: list[str]

def build_packwiz(config: Config, *, dry_run: bool = False) -> PackwizBuildResult:
    ...
```

Core behavior:

1. Clean temporary output directory.
2. Copy/generate files.
3. Emit metafiles.
4. Emit `index.toml`.
5. Emit `pack.toml`.
6. Validate no forbidden files.
7. Move temp output to final `docs/packwiz`.

Use atomic output replacement:

```text
docs/packwiz.tmp/
docs/packwiz/
```

Only replace final output after a successful build.

---

## Phase 2 — File Classification

Implement a classifier for each included Prism file.

Return one of:

```python
class FileKind(Enum):
    MODRINTH_META = "modrinth_meta"
    CURSEFORGE_META = "curseforge_meta"
    SELF_HOST_ALLOWED = "self_host_allowed"
    DIRECT_PACK_FILE = "direct_pack_file"
    OPTIONAL_FILE = "optional_file"
    SKIP = "skip"
    ERROR = "error"
```

Rules:

1. `mods/.index/**` -> skip.
2. `mods/.connector/**` -> skip.
3. Matches `extra_ignore` and not `optional_files` -> skip.
4. Matches FTB deny patterns -> skip or fail loudly if unexpected.
5. Mod jar with usable Modrinth metadata -> `MODRINTH_META`.
6. Mod jar matching allowed self-host glob -> `SELF_HOST_ALLOWED`.
7. Non-mod included path -> `DIRECT_PACK_FILE`.
8. Optional file -> `OPTIONAL_FILE`.
9. Unresolved jar -> `ERROR`.

---

## Phase 3 — Modrinth Metadata Conversion

Reuse existing Prism `.pw.toml` parsing where possible.

For every Modrinth-backed jar:

1. Locate matching metadata file in `mods/.index/`.
2. Extract:
   - display name
   - filename
   - URL
   - hash
   - hash format
   - Modrinth project ID
   - Modrinth version ID
3. Emit `docs/packwiz/mods/<safe-name>.pw.toml`.
4. Add index entry with `metafile = true`.

If the Prism metadata does not include Modrinth project/version IDs but the URL/hash are valid, still emit `[download]` but omit `[update.modrinth]`.

Do not do network lookups in the MVP unless existing code already does them safely.

---

## Phase 4 — Direct Hosted Files

For configs, resource packs, shader packs, default configs, global datapacks, and allowed custom mods:

1. Copy file to same relative path under `docs/packwiz/`.
2. Hash file.
3. Add direct `[[files]]` entry to `index.toml`.

Example:

```toml
[[files]]
file = "config/toms_storage-common.toml"
hash = "..."
```

Consider `preserve = true` only for files users are expected to modify locally. For pack-controlled configs, omit `preserve`.

Do **not** include:

- logs
- crash reports
- downloads
- JEI per-world state
- runtime caches
- `.mixin.out`
- FTB leftover configs
- shader unpacked EuphoriaPatcher directories

Reuse current ignore list.

---

## Phase 5 — Installer Bootstrap Instance

Add a command:

```bash
prism_sync packwiz-bootstrap
```

Optional for the MVP, but useful.

Responsibilities:

1. Create/export a minimal Prism instance.
2. Ensure `packwiz-installer-bootstrap.jar` is in `.minecraft/`.
3. Ensure pre-launch command is set.
4. Export as zip:

```text
dist/Deer Diary Packwiz Bootstrap.zip
```

Alternative MVP:

- Document manual Prism setup first.
- Automate bootstrap export later.

---

## Phase 6 — Publishing

Add `packwiz-publish`:

1. Run `packwiz-check`.
2. Run `packwiz-build`.
3. Update changelog using existing diff logic.
4. Commit generated files.
5. Push through existing WSL git helper.
6. Print final Packwiz URL.

Commit format:

```text
Packwiz release YYYY.MM.DD: +N ~M -K
```

Do not call Modrinth.

---

## Phase 7 — Clean Test

Create a clean Prism test instance.

Steps:

1. Import the bootstrap zip.
2. Launch once.
3. Confirm Packwiz downloads all files.
4. Confirm game starts.
5. Close game.
6. Delete one mod jar locally.
7. Relaunch.
8. Confirm Packwiz restores the deleted jar.
9. Change one config in the source Prism instance.
10. Run `publish-packwiz.bat`.
11. Relaunch test instance.
12. Confirm changed config updates.
13. Confirm FTB jars/configs do not appear.
14. Confirm custom mods appear.
15. Confirm optional disabled renderer behavior is acceptable.

---

## Acceptance Criteria

The migration is successful when:

- `prism_sync packwiz-check` succeeds.
- `prism_sync packwiz-build` produces valid `docs/packwiz/pack.toml`.
- GitHub Pages serves `pack.toml`.
- A fresh Prism bootstrap instance can auto-install the pack.
- Relaunching the instance updates changed files.
- The pack launches on client.
- No FTB Essentials / FTB Library jars are present.
- No `mods/.index` or `mods/.connector` files are shipped.
- Runtime caches/logs/world-local state are not shipped.
- Custom mods from `Custom Mods/dist/**` are included.
- The old Modrinth pipeline still works unless intentionally disabled.

---

## Later Improvements

### Make Packwiz the source of truth

After the Prism-derived Packwiz generation is stable, consider flipping the workflow:

```text
pack.toml / index.toml / mods/*.pw.toml
      ↓
Packwiz Installer
      ↓
Prism instance
```

This would make version updates cleaner, but it would remove Prism as the comfy authoring UI.

Do not do this in the MVP.

---

### Server Auto-Update

Packwiz can also update server files with:

```bash
java -jar packwiz-installer-bootstrap.jar -g -s server https://toxicbananaparty.github.io/deer-diary/packwiz/pack.toml
```

This should be a separate project because Bloom Host / Pterodactyl startup constraints may affect how cleanly it can be integrated.

---

### Native optional mods

Replace `.disabled` optional files with Packwiz `[option]` metadata.

---

### Packwiz-native Modrinth export

Packwiz can export `.mrpack`s too. Once Packwiz generation works, consider replacing the custom `.mrpack` builder with:

```bash
packwiz modrinth export -o dist/Deer Diary-YYYY.MM.DD.mrpack
```

Only do this if it preserves all required ignore rules and does not reintroduce Modrinth AutoMod problems.

---

## Open Questions for Ryan

1. Should GitHub Pages be the hosting target, or would you rather use GitHub Releases / another static host?
2. Should Packwiz updates also be used for the Bloom Host server eventually, or client-only for now?
3. Should the optional Sodium Dynamic Lights file become a real Packwiz optional prompt, or stay as `.disabled` for the first pass?
4. Are there any local config files players are expected to customize that should be marked `preserve = true`?
5. Should the old `publish.bat` continue publishing to Modrinth, or should `publish-packwiz.bat` become the main release button once proven?

---

## Main Recommendation

Implement **Prism-derived Packwiz output** first, not a total Packwiz-native rewrite.

The existing pipeline already solves the annoying parts:

- walking the instance
- syncing custom mods
- applying ignore rules
- preserving optional-file behavior
- avoiding Modrinth-banned FTB files
- generating release diffs/changelogs
- publishing through the existing repo workflow

Packwiz should initially become the **distribution/update layer**, not necessarily the authoring layer.
