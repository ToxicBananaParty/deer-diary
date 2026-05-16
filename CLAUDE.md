# Deer Diary — Modpack workspace

A NeoForge 1.21.1 Minecraft modpack published as
[`deer-diary`](https://modrinth.com/modpack/deer-diary) on Modrinth, plus the
custom mods that ship with it and the local CLI that builds and publishes the
pack.

- **Project ID (Modrinth):** `i1cW3SKW` (slug: `deer-diary`, status: unlisted)
- **GitHub:** `github.com:ToxicBananaParty/deer-diary`
- **Repo root:** `C:\Users\Ryan-PC\Desktop\MC Stuff\` (this directory)
- **Loader / MC:** NeoForge 21.1.228 / Minecraft 1.21.1

## Layout

```
MC Stuff/                          # git repo root
├── .gitignore                     # workspace-level — see "Gitignore rules" below
├── .gitattributes                 # LF in repo, CRLF on Windows checkout
├── CLAUDE.md                      # this file
├── publish.bat                    # double-click to run `prism_sync publish --push`
│
├── Deer Diary  (symlink, gitignored)
│       → C:\Users\Ryan-PC\AppData\Roaming\PrismLauncher\instances\Deer Diary
│       The live Prism Launcher instance — source of truth for the modpack.
│       Edited via Prism's GUI like normal. The publish pipeline reads this.
│
├── prism-to-modrinth-sync/        # publish CLI (Python 3.11+)
│   ├── src/prism_sync/            # package
│   ├── config.toml                # pipeline config (tracked)
│   ├── config.local.toml          # secrets (gitignored)
│   ├── .last-published-state.json # fingerprint baseline for diffs (tracked)
│   ├── CHANGELOG.md               # auto-appended per release (tracked)
│   ├── .venv/                     # gitignored
│   └── dist/*.mrpack              # gitignored build output
│
└── Custom Mods/
    ├── src/                       # mod source repos (Gradle projects, tracked)
    │   ├── trmt-neoforge-1.21.1/  # erosion mod (own CLAUDE.md in there)
    │   ├── deer-diary-commands/   # server-utility commands mod
    │   ├── key-to-necklace/       # Curios datapack source (no build; zipped manually)
    │   ├── FTB-Essentials-*/      # reference-only, GITIGNORED (see "FTB note" below)
    │   └── FTB-Library-*/         # reference-only, GITIGNORED
    └── dist/                      # built artifacts the pipeline consumes (tracked)
        ├── trmt-neoforge-1.21.1/trmt-X.Y.Z-1.21+1.21.1.jar
        ├── deer-diary-commands/deer-diary-commands-X.Y.Z-1.21+1.21.1.jar
        └── key-to-necklace/key-to-necklace.zip
```

## The one-command release loop

**Easiest:** double-click `publish.bat` in the workspace root. It cd's
into `prism-to-modrinth-sync/`, runs `prism_sync publish --push`, and
pauses at the end so the console window stays open whether the run
succeeds or fails.

From a shell:

```
cd "C:\Users\Ryan-PC\Desktop\MC Stuff\prism-to-modrinth-sync"

# PowerShell:
.\prism_sync publish --push

# CMD:
prism_sync publish --push
```

(`prism_sync.cmd` is a thin shim that invokes `.\.venv\Scripts\python.exe -m
prism_sync` — no venv activation needed. The old long form still works if
you'd rather type it.)

What it does, in order:

1. **Custom-mod sync** — for each `[[custom_mods]]` entry in `config.toml`,
   checks `Custom Mods/dist/{name}/` for a newer build of the mod and copies
   it into the Prism instance's `mods/` folder (or wherever `target_dir` says).
   Falls back to byte-compare for versionless filenames (like the datapack zip).
2. **Build mrpack** — reads `mmc-pack.json` for MC/loader versions, walks the
   include paths in `config.toml`, resolves jars to Modrinth CDN URLs via
   Prism's `.pw.toml` metadata (with hash-lookup fallback), and bundles
   unresolvable files (configs, custom mods, CurseForge-only jars) into
   `overrides/`. Output lands in `dist/Deer Diary-<version>.mrpack`.
3. **Diff** against `.last-published-state.json` and render a markdown changelog
   grouped by Mods / Config / Shaders / etc.
4. **Publish to Modrinth** — auto-picks today's date as version (`YYYY.MM.DD`),
   auto-bumps to `.1`, `.2`, etc. on same-day reissues. Default status is
   `listed`; add `--draft` to keep it private. The slug-to-canonical-id resolve
   uses the PAT so unlisted projects work.
5. **Append changelog** to `CHANGELOG.md`.
6. **Git commit + push** via WSL (`Release X.Y.Z: +N ~M -K`) and push to
   `origin main`. Only runs if `--push` is passed.

Other useful invocations (substitute `.\prism_sync` in PowerShell or
`prism_sync` in CMD for the shim; long form is `.\.venv\Scripts\python.exe -m
prism_sync`):

| Goal | Command |
|---|---|
| Preview without publishing | `prism_sync check --changelog` |
| See the API payload, don't POST | `prism_sync publish --dry-run` |
| Build the .mrpack only | `prism_sync build` |
| Override version | `prism_sync publish --version 2026.06.01 --push` |
| Bootstrap state from live Modrinth | `prism_sync check --from-remote` |

## Env vars

- **`MODRINTH_PAT`** — Modrinth personal access token with `VERSION_CREATE`
  scope. Can live in `config.local.toml` instead (gitignored). Required for
  any publish or remote-bootstrap action.
- **`PRISM_SYNC_GIT_CMD=wsl git`** — routes the tool's git invocations through
  WSL because SSH and the git identity are set up there, not in Windows git.
  Set once with `setx PRISM_SYNC_GIT_CMD "wsl git"` and open a fresh shell.

## Custom mods

### `trmt-neoforge-1.21.1`
Foot-traffic erosion mod. Has its own `CLAUDE.md` and `.gitignore` —
defer to those when working inside it. Gradle wired to mirror the built
jar into `../../dist/trmt-neoforge-1.21.1/` so the pipeline sees it.

### `deer-diary-commands`
14-command essentials replacement (we shipped it instead of FTB Essentials
because Modrinth's AutoMod rejects FTB content). Mod ID
`deer_diary_commands`, Maven group `milkucha.ddc`. Commands:

- **Admin:** `/extinguish`, `/god`, `/invsee`, `/mute` + `/unmute` + `/muted`,
  `/tp_offline` (+ `/tpo` alias).
- **Misc:** `/kickme`, `/leaderboard <stat>`, `/near [player] [radius]`,
  `/recording`, `/streaming`.
- **Teleport:** `/jump`, `/rtp`, `/teleport_last` (+ `/tpl` alias), `/tpx`.

Permission model: every command is a root-level Brigadier literal, which means
the server's Brigadier-permission interceptor (Paradigm or similar) auto-registers
`command.{name}` for each. LuckPerms grants/denies via those names — no
PermissionAPI integration in the mod itself.

State files:
- `<world>/data/ddc_mutes.json` — mute state, persisted across restarts
- `config/deer_diary_commands.json` — RTP cooldown + distance + dim
  whitelist/blacklist, Gson-loaded on server start
- Vanilla `<world>/playerdata/<UUID>.dat` — written via atomic temp-replace
  by `/tp_offline`
- Vanilla `<world>/stats/<UUID>.json` — read by `/leaderboard`

Data tags shipped (empty by default — populate per pack):
- `data/deer_diary_commands/tags/block/ignore_rtp.json`
- `data/deer_diary_commands/tags/worldgen/biome/ignore_rtp.json`

### `key-to-necklace`
Curios datapack — adds Supplementaries keys to the necklace slot tag.
No Gradle; the source is just `pack.mcmeta` + `data/`. The `dist/` zip is
hand-built (`Compress-Archive` from PowerShell, or zip from any tool).
In `config.toml`'s `[[custom_mods]]` block it uses `source_pattern =
"key-to-necklace.zip"` so the version-tuple compare degrades to byte
compare.

## Pipeline config (`prism-to-modrinth-sync/config.toml`)

Key fields, in addition to `instance_path` / `project_id` / `user_agent`:

- **`include_paths`** — subdirs under `<instance>/minecraft/` to walk. Currently
  `mods/`, `config/`, `resourcepacks/`, `shaderpacks/`, `defaultconfigs/`,
  `moonlight-global-datapacks/`.
- **`extra_ignore`** — glob patterns layered on top of the instance's
  `.packignore`. Notable entries: `**/*.disabled` (except via `optional_files`),
  `shaderpacks/*/**` (unpacked-by-EuphoriaPatcher dirs), `config/ftb*` and
  `defaultconfigs/ftb*` and `mods/ftb-*-*.jar` (Modrinth AutoMod rejects FTB).
- **`optional_files`** — allowlist that overrides `extra_ignore`. Used for
  `.disabled` files we explicitly want to ship (currently:
  `mods/sodiumdynamiclights-neoforge-1.0.10-1.21.1.jar.disabled` — low-GPU
  alternative renderer; consumer enables by stripping `.disabled`).
- **`[[custom_mods]]`** — locally-built mods. Each entry: `name`, `source_dir`,
  optional `source_pattern` (defaults to `{name}-*.jar`) and `target_dir`
  (defaults to `mods`).

## How files get into the published pack

For each file walked from the instance:

1. **Auto-skipped** (always, no config needed):
   `<include_path>/.index/**`, `mods/.connector/**`.
2. **Match `extra_ignore`?** → skipped (unless also in `optional_files`).
3. **Mod jar with a `.pw.toml` in `mods/.index/`?** → resolved to a Modrinth
   CDN URL if its SHA512 matches the metadata. CurseForge-only `.pw.toml`s
   short-circuit to `overrides/`.
4. **Unresolved mod jar** → batch SHA1 hash-lookup against Modrinth's
   `/v2/version_files`. Hit → CDN reference. Miss → `overrides/`.
5. **Everything else** (configs, resource packs, datapacks) → `overrides/`.

The `mrpack` ends up with `modrinth.index.json` listing CDN references and
the rest packed under `overrides/`. URLs are percent-encoded (Prism's
`.pw.toml`s leave spaces unencoded; the tool re-encodes the path component
of every URL so Modrinth's validator doesn't reject the upload).

## Gitignore rules

The workspace `.gitignore` keeps the repo lean. Highlights:

- `Deer Diary` (the symlink) — the live Prism instance can't be tracked.
- `prism-to-modrinth-sync/.venv/`, `dist/`, `*.mrpack` — transient.
- `prism-to-modrinth-sync/config.local.toml` — contains the Modrinth PAT.
- `Custom Mods/src/*/build/`, `.gradle/`, `bin/`, IDE files — Gradle/IDE noise.
- `Custom Mods/src/*/CLAUDE.md`, `AGENTS.md`, `LEARNINGS.md`, `.claude/` — dev
  files inside nested mod repos, kept local.
- `Custom Mods/src/FTB-Essentials-*/`, `Custom Mods/src/FTB-Library-*/` —
  reference-only, not republished (see FTB note).

Tracked (notable):
- `Custom Mods/dist/**` — yes, the built jars/zips themselves. Personal pack,
  binary churn is acceptable, and it makes the repo self-contained.
- `prism-to-modrinth-sync/.last-published-state.json` — yes, so release history
  is auditable from git alone.

## FTB Essentials / FTB Library note

FTB explicitly opted their mods out of Modrinth. Their public LICENSE files
in the FTB-Essentials and FTB-Library repos are an **unfilled MIT template**
(`Copyright (c) [year] [fullname]` — placeholders never replaced); the
intended license file in upstream FTB-Library is `LICENSE.md` ("All Rights
Reserved, Feed The Beast Ltd 2025"). The license situation is genuinely
ambiguous.

For this project:

- FTB source is kept locally in `Custom Mods/src/FTB-{Essentials,Library}-*/`
  for reference while implementing `deer-diary-commands`. Those folders are
  **gitignored**; we don't republish FTB's code.
- `deer-diary-commands` is an original implementation. Its `NOTICE` file
  credits FTB Essentials as the source of feature/architecture inspiration.
- FTB jars are excluded from the published mrpack via `extra_ignore` and
  replaced by `deer-diary-commands` in the customs sync pipeline.

If FTB ever clarifies their license, revisit. Until then, do **not** push
the FTB-* directories to the GitHub remote, and do **not** copy FTB source
verbatim into `deer-diary-commands`.

## Modrinth review gotchas

- Modpack version reviews take **3–4 days** typically; up to **2 weeks**
  during holiday queue surges. Don't poll obsessively.
- The first publish required:
  - Resolving the slug → canonical 8-char base62 ID for the POST body (slugs
    only work on GET path params).
  - Percent-encoding spaces in CDN URLs from `.pw.toml` files.
  - Promoting the initial draft to `listed` via PATCH (Modrinth's project
    setup checklist counts only non-draft versions toward "Upload a version",
    so a draft can never satisfy the requirement that gates it out of draft).
    A one-off `prism-to-modrinth-sync/promote.py` exists for this; otherwise
    just don't pass `--draft` for live releases.
- AutoMod rejected our v1 over FTB Essentials + FTB Library. Excluding them
  via `extra_ignore` and shipping `deer-diary-commands` resolves it.

## Environment quirks

- **Windows console can't print Unicode glyphs** (the cp1252 codec rejects ✓,
  →, ·, etc.). Stick to ASCII in `print()` from the publish pipeline.
- **WSL git, not Windows git** — git identity and SSH key live in WSL. The
  publish tool routes through `wsl git` via `PRISM_SYNC_GIT_CMD`. Don't run
  `git` from PowerShell expecting it to find the identity.
- **Java 21 must be on PATH** for Gradle builds. Microsoft JDK 21 lives at
  `C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot\`. Gradle wrapper picks
  it up automatically from PowerShell; WSL doesn't have a Linux Java.
- **The auto-mode safety classifier blocks renaming FTB jars to `.disabled`**
  on the assumption it's takedown-evasion. It isn't — we exclude them from
  the published pack via `extra_ignore`, the rename is just to silence local
  command conflicts with `deer-diary-commands`. Do the rename manually in
  PowerShell if needed.

## Where to look first

- A command isn't behaving right →
  `Custom Mods/src/deer-diary-commands/src/main/java/milkucha/ddc/command/`
- Pack omits or includes the wrong file →
  `prism-to-modrinth-sync/config.toml` (`extra_ignore`, `optional_files`,
  `include_paths`) and `prism-to-modrinth-sync/src/prism_sync/mrpack.py`
  (walker logic).
- Modrinth publish fails → look at the response body printed by the tool;
  past failures listed in this file's "Modrinth review gotchas".
- Custom mod didn't swap in → check `Custom Mods/dist/{name}/` exists with
  a matching jar and `config.toml` has a `[[custom_mods]]` entry pointing
  at it.
- Permissions question on the server → root-level `command.{name}`; see the
  `deer-diary-commands` section above.
