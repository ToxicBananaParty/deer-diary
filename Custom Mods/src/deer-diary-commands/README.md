# Deer Diary Commands

NeoForge 1.21.1 server-command toolkit for the Deer Diary modpack. Provides
the subset of admin/teleport/utility commands the pack needs, without bundling
FTB Essentials.

## Commands

Admin: `/extinguish`, `/god`, `/invsee`, `/mute`, `/tp_offline`
Misc: `/kickme`, `/leaderboard`, `/near`, `/rec`
Teleport: `/jump`, `/rtp`, `/tpl`, `/tpx`

## Build

From this directory:

```
./gradlew build
```

The built jar lands at `build/libs/deer-diary-commands-X.Y.Z.jar` and is
auto-mirrored to `../../dist/deer-diary-commands/` for the publish pipeline.

## License + attribution

MIT. See `LICENSE`. See `NOTICE` for attribution to FTB Essentials, whose
feature set inspired this mod.
