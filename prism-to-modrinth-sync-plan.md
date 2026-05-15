# Plan: `prism-to-modrinth-sync` — Local CI/CD for a Prism Launcher modpack → Modrinth

> **How to use this file:** Feed this to a Claude Code session as the starting brief. It contains the
> architecture decision, the API/format reference (so you don't need to re-research), a phased
> implementation plan, and a list of open questions to resolve with me *before* writing code.
> Treat the "Open Questions" section as a gate — confirm those answers first.

---

## Objective

Build a local command-line tool that treats a **Prism Launcher instance as the source of truth** for a
Minecraft modpack, detects when its contents (`mods/`, `config/`, datapacks, etc.) have diverged from
the version currently published on **Modrinth**, and publishes the new state as a Modrinth version
update — with an auto-generated changelog. Optionally wire a GitHub Action as the publish step.

The user works *inside Prism* (adds/removes/updates mods there). The tool syncs outward. It must not
require the user to change how they edit the pack.

## Context & Constraints

- The pack lives in a Prism Launcher instance on the user's machine.
- The deliverable Modrinth expects for a modpack is a single `.mrpack` file (a zip — see Reference).
- **Prism can only export `.mrpack` via its GUI** (right-click instance → Export → Modrinth). Its CLI
  (`prismlauncher`) supports `--import`, `--launch`, `--show` only — there is **no export flag**. So
  the tool must construct the `.mrpack` itself rather than shelling out to Prism.
- Credentials: the Modrinth personal access token (PAT) must come from an environment variable or a
  gitignored local config file. **Never commit it; never hardcode it.**
- Modrinth API rate limit is 300 requests/minute — relevant only if hash-querying a very large
  `mods/` folder; batch the hash lookups.

## Architecture Decision

**Chosen: local Python tool that builds the `.mrpack` itself**, using Modrinth's hash-lookup endpoint
to turn known mods into CDN references and dumping everything else into `overrides/`.

Why build the mrpack ourselves instead of consuming a manual Prism export:
- Fully scriptable — no GUI step in the loop.
- Avoids a known Prism bug where its own exporter has produced `.mrpack` files Modrinth rejects with
  `invalid URL` validation errors. Building entries straight from CDN URLs returned by the API
  sidesteps this.

**Considered and rejected: full packwiz migration.** `packwiz` is the established git-based modpack
CLI and would give "real" CI/CD, but it requires moving the source of truth *out of Prism* — the user
would edit the pack through packwiz and Prism becomes a consumer. That contradicts the core
constraint. Keep packwiz noted as a future option, do not build on it now.

GitHub is **optional and additive** (Phase 4): the local tool can either publish directly, or commit
the built `.mrpack` to a repo and let a GitHub Action publish it.

---

## Technical Reference (verified — don't re-research, but confirm exact request shapes against docs)

Docs root: `https://docs.modrinth.com/api/` — API base URL: `https://api.modrinth.com/v2`

### Publishing a version — `POST /v2/version`
- Multipart request. One form field named `data` carries a JSON blob of version metadata; one or more
  additional form fields carry the file(s).
- `data` JSON includes: `name`, `version_number` (semver-ish), `changelog`, `dependencies`,
  `game_versions`, `version_type` (`release`/`beta`/`alpha`), `loaders`, `featured`, `status`,
  `project_id` (required), `file_parts` (required — array of the multipart field names of the files),
  `primary_file` (the field name of the primary file).
- Auth: header `Authorization: <pat>`. The token needs the `VERSION_CREATE` scope.
- A required, uniquely-identifying `User-Agent` header must be sent (e.g.
  `ryan/prism-to-modrinth-sync (contact-or-repo-url)`).
- Accepts `.mrpack`, `.jar`, `.zip`, `.litemod`. For testing, a version can be created with
  `status: "draft"` so it isn't immediately live.
- Errors: `400` = invalid input (body has `error` + `description`), `401` = bad/missing token scope.

### Resolving local jars to Modrinth versions — "Get versions from hashes"
- Endpoint `POST /v2/version_files` (confirm exact path + body schema against docs). Send a batch of
  file hashes plus the algorithm (`sha1` or `sha512`); response maps each hash → the matching version
  object, which includes the file's `downloads` URL on `cdn.modrinth.com`, `hashes`, and size.
- This is the key to a clean mrpack: hash every jar once, batch-query, and any hash that resolves
  becomes a CDN-referenced file instead of a bundled override.

### Reading current published state — "List project's versions"
- `GET /v2/project/{id|slug}/version` → versions newest-first. Note: a version's `files[]` lists the
  *uploaded artifact* (the `.mrpack` itself), **not** the files inside it. To diff *contents* we keep
  a local state file (see Phase 2) and/or download + unzip the published `.mrpack`.

### `.mrpack` format
- A ZIP with the `.mrpack` extension. Root contains `modrinth.index.json` (UTF-8).
- `modrinth.index.json` fields: `formatVersion` (`1`), `game` (`"minecraft"`), `versionId`, `name`,
  `summary` (optional), `files[]`, `dependencies` (object — e.g. `minecraft` version and the loader
  version such as `fabric-loader`/`forge`/`neoforge`/`quilt-loader`).
- Each entry in `files[]`: `path` (destination relative to the instance dir, e.g. `mods/Foo.jar`;
  must not contain `..` or a drive root), `hashes` (must include **both** `sha1` and `sha512`), `env`
  (optional `{client, server}`), `downloads[]` (direct URLs — **only whitelisted hosts allowed**;
  `cdn.modrinth.com` qualifies), `fileSize` (bytes).
- `overrides/` folder: contents are copied into the instance directory on install. Anything not
  resolvable to a whitelisted CDN URL (configs, datapacks, hand-dropped or CurseForge jars) goes here.
  `client-overrides/` and `server-overrides/` exist too but are out of scope unless needed.

### Prism instance layout (confirm on the user's actual install)
- Instance folder contains `instance.cfg`, `mmc-pack.json`, and a Minecraft dir (`minecraft/` or
  `.minecraft/`).
- `mmc-pack.json` is the source for the **Minecraft version and loader version** → use it to populate
  `dependencies` in `modrinth.index.json`. Do not hardcode these.
- Mod files are under `<mcdir>/mods/`. Prism also keeps per-mod metadata (a `.index/` folder of TOML)
  — *may* be usable to get Modrinth URLs directly, but the hash-lookup approach is more robust and
  launcher-agnostic, so prefer that and treat Prism metadata as a possible optimization only.

---

## Implementation Plan

Language: **Python 3.11+**. Suggested deps: `requests` (HTTP), stdlib `hashlib`/`zipfile`/`json`.
Keep it a small package, not one giant script.

### Phase 1 — Config + instance reader + mrpack builder
- `config`: load instance path, Modrinth `project_id`/slug, PAT (env var), User-Agent string,
  include paths (`mods/`, `config/`, datapacks path — see Open Questions), and ignore globs. Support a
  gitignored `config.local.toml` or similar.
- `instance.py`: locate the Minecraft dir under the instance, parse `mmc-pack.json` for MC + loader
  versions, enumerate files under the include paths.
- `mrpack.py`:
  - Compute SHA1 + SHA512 for every `.jar` in `mods/`.
  - Batch-query `POST /v2/version_files` with the SHA1s.
  - Resolved jars → `files[]` entries (CDN `downloads`, both hashes, `fileSize`).
  - Unresolved jars + all configs/datapacks → `overrides/`.
  - Emit `modrinth.index.json` and zip to `<name>-<version>.mrpack`.
- **Verification gate:** the built `.mrpack` must import cleanly back into Prism (round-trip test)
  before moving on.

### Phase 2 — Differ + state file + changelog generation
- After building, compute a fingerprint: a sorted map of `path → sha512` over the full pack contents.
- Persist the last-published fingerprint to a local `.last-published-state.json`.
- `diff.py`: compare current fingerprint vs stored → produce structured `{added, updated, removed}`.
- Support a `--from-remote` mode that downloads the latest published `.mrpack` from the CDN and
  unzips it to rebuild the fingerprint (first run, or recovering a lost state file).
- Render the diff into a human-readable changelog string for the Modrinth `changelog` field.

### Phase 3 — Publisher
- `publish.py`: determine the next `version_number` (auto-increment semver, or date-based — see Open
  Questions), assemble the multipart `POST /v2/version` request (`data` JSON + `.mrpack` file part,
  correct `file_parts`/`primary_file`), send with auth + User-Agent headers.
- Handle `400`/`401` with clear messages. On success, update `.last-published-state.json`.
- Test path: publish first with `status: "draft"` against the real project (or a throwaway test
  project) before doing a live `release`.

### Phase 4 — GitHub integration (optional)
- Option A (publisher stays local): the tool commits the built `.mrpack` + state file to a repo; a
  GitHub Action on push publishes via `Kir-Antipov/mc-publish` or a direct `curl` to the API.
- Option B (everything in CI): the Action runs the whole tool. Note this needs the Prism instance
  contents reachable from the runner — likely means committing the pack contents to the repo, which
  starts to resemble the packwiz model. Prefer Option A unless the user wants otherwise.
- PAT lives in GitHub Actions secrets either way.

## CLI Surface (target)

- `sync check` — build + diff, print what changed, exit non-zero if there are changes (CI-friendly).
- `sync build [--out PATH]` — build the `.mrpack` only, no network publish.
- `sync publish [--version X.Y.Z] [--type release|beta] [--draft] [--dry-run]` — build + diff +
  publish if changed. `--dry-run` builds and prints the exact API payload without POSTing.

---

## Open Questions — confirm with me before building

1. **Mod sourcing ratio.** What fraction of `mods/` was installed *through* Prism/Modrinth (so it'll
   resolve to a CDN URL via hash lookup) vs hand-dropped or CurseForge jars (which must go in
   `overrides/`)? This decides how much the hash-lookup step actually buys us.
2. **Datapacks location.** Are these global datapacks in a top-level `datapacks/` folder, or per-world
   under `saves/<world>/datapacks/`? Affects what the instance reader globs.
3. **Versioning scheme.** Semver auto-increment, date-based (`2026.05.14`), or always pass `--version`
   explicitly?
4. **Which include paths exactly?** Confirm the full set beyond `mods/` and `config/` (e.g.
   `resourcepacks/`, `shaderpacks/`, `kubejs/`, `options.txt`).
5. **Target project.** Does the Modrinth project already exist and is it approved? (The tool updates
   an existing project; it does not create or submit a new project for review.)
6. **GitHub in scope now or later?** Build Phase 4, or stop after Phase 3 and revisit?

## Out of Scope (for this build)

- Creating a brand-new Modrinth project / handling first-time project review.
- packwiz migration.
- `client-overrides/` / `server-overrides/` split.
- CurseForge publishing.

## Done Criteria

- `sync build` produces a `.mrpack` that round-trips into Prism without errors.
- `sync check` correctly reports no-change vs changed against the last published state.
- `sync publish --dry-run` shows a correct multipart payload and an accurate auto-generated changelog.
- A real `--draft` publish succeeds against the live project and appears on Modrinth as a draft.
- PAT is never written to the repo or to any committed file.
