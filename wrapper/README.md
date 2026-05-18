# Deer Diary server wrapper

Files that run on the **BloomHost server**, deployed once via
`prism_sync server-deploy-wrapper`. After deploying:

1. **Take a manual backup** of `/home/container/mods/` via SFTP or the
   BloomHost panel — call it something like `mods.bak-YYYY-MM-DD/`.
   The first wrapper-managed boot will re-fetch every jar from the
   published pack; if our published pack is missing anything currently
   on the server, packwiz-installer will *delete* it. Having a backup
   means a one-command rollback if something's off.

2. **Edit the Pterodactyl panel startup command** from the NeoForge
   default to:

   ```
   bash /home/container/wrapper/start.sh
   ```

   Save and restart. Console should log
   `[wrapper] applying pack update:  -> <today's version>`, then
   packwiz-installer's download progress, then NeoForge boots normally.

## What each file does

- **`start.sh`** — runs at every server start. Hits the published
  `pack.toml`, compares its version against `applied-version.txt`. If
  the published version is newer AND `approved-version.txt` matches it,
  invokes `packwiz-installer-bootstrap.jar -s server` to apply, then
  `exec ./run.sh`. Otherwise just `exec ./run.sh` with the existing
  install (so unapproved updates don't auto-apply on restart).
- **`approve-update.sh`** — convenience for the BloomHost panel
  console: writes `approved-version.txt` so the next restart applies
  that version. Equivalent to running `prism_sync server-approve` from
  your local shell.
- **`packwiz-installer-bootstrap.jar`** — pinned version (v0.0.3), same
  as the client bootstrap. Bump deliberately if Packwiz upstream
  changes; don't auto-update it.

## How approval flows

```
prism_sync server-publish        publishes new pack.toml to GH Pages
            │
            └── (optional --notify) ─► Discord webhook with changelog

You read the changelog, decide it's good:

prism_sync server-approve <version>     SFTPs approved-version.txt

Restart the server via panel:

wrapper/start.sh: sees new version + matching approval → applies → boots
```

If you don't run `server-approve`, the next restart just boots with the
current applied version and logs that an update is pending.

## Operations

- **Force re-apply**: delete `/home/container/applied-version.txt`, then
  approve + restart. The wrapper will treat the published version as new.
- **Rollback**: restore `mods/` from your backup, write the old version
  string into `applied-version.txt`, and DON'T approve the new one.
- **Logs**: wrapper prints to stdout, which Pterodactyl captures into
  the server console + log file alongside Minecraft's own output.
