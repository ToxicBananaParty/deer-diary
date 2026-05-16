# TRMT changelog

All notable changes to TRMT are documented here. Versions follow semver:
**major** = save format / public API breaking, **minor** = new features /
non-breaking additions, **patch** = bug fixes.

The mod's on-disk save format is versioned separately via
`milkucha.trmt.migration.SaveMigrator`; bumping the save format requires a
matching migrator step.

---

## [1.0.0] — feature-complete release

The polish-and-extensibility release. Mechanics are unchanged from 0.6.0;
this version makes the mod modpack-friendly and ready for third-party
integration.

### Added

- **Block tags** for data-driven extensibility:
  - `#trmt:erodes_as_grass` — vanilla grass + TRMT eroded_grass_block
  - `#trmt:erodes_as_dirt` — vanilla dirt + TRMT eroded_dirt + eroded_dirt_path
    (+ optional `farmersdelight:rich_soil`)
  - `#trmt:erodes_as_sand` — vanilla sand + TRMT eroded_sand
  - `#trmt:erodes_as_leaves` — empty default, fallback to `LeavesBlock` instanceof
  - `#trmt:protects_below_from_erosion` — single tag replaces the previously
    hardcoded saplings/crops/flowers/berry/bamboo/sugar-cane/cactus list

  Modpack authors can extend any of these via datapack — modded
  grass/dirt/sand variants now slot into the erosion pipeline without code
  changes.

- **Entity-type tag `#trmt:tramples`** for trampling entities. Default includes
  villagers + all horse variants + llamas; pre-populated with optional entries
  for Naturalist mobs (deer, boar, bear, wolf, etc.). Plus `erosionMultipliers.tramples`
  map (entity-id → multiplier) and `defaultTrample` fallback.

- **Three new public API events** in `milkucha.trmt.api.*`:
  - `ErodedEvent` (post-erosion, not cancellable)
  - `CanDeErodeEvent` (pre-de-erosion, cancellable)
  - `DeErodedEvent` (post-de-erosion, not cancellable)
  - `CanErodeEvent` now exposes the triggering `ServerPlayer` (via new
    constructor overload + `getPlayer()`).

- **Save format versioning framework** (`SaveMigrator`). Pre-1.0 NBT loads as
  version 0; current is 1. No data format change for this release —
  framework only.

- **Per-dimension allow/block list** (`dimensions.mode` + `dimensions.list`).
  Default is unrestricted blocklist. Use to disable erosion in the Nether/End
  or to restrict to specific dimensions.

- **Forced-chunks toggle** (`erosion.allowInForcedChunks`). When false, skips
  erosion/de-erosion in chunks force-loaded via Chunky pre-gen, chunkloaders
  mods, or `/forceload`.

- **Soft-compat modules** (auto-loaded if target mod is present):
  - **Open Parties and Claims** — player-driven erosion respects claim
    protection via `IChunkProtectionAPI.onBlockInteraction`.
  - **LuckPerms** — `trmt.bypass.erosion` permission node skips erosion for
    that player.
  - **SereneSeasons** — modulates erosion rate per season; tunable via
    `seasonsMultipliers`.
  - **Naturalist** — large mobs pre-included in `#trmt:tramples` via
    optional tag entries.
  - **FarmersDelight** — `rich_soil` pre-included in `#trmt:erodes_as_dirt`.

- **README.md** documenting features, tags, events, config, and compat.

- **`eroded_dirt_path` mineable/shovel** + drops dirt on break (0.5.1 fix
  carried over).

### Changed

- **`CanErodeEvent` semantics** — was previously fired before de-erosion of
  `eroded_dirt_path` as well. Now de-erosion fires `CanDeErodeEvent`
  instead. Subscribers that want to cancel BOTH erosion and de-erosion
  must subscribe to both events.
- **`EntityStepHandler.hasProtectedPlantAbove`** is now a one-line tag check
  (`above.is(TRMTTags.PROTECTS_BELOW_FROM_EROSION)`). Behavior identical for
  the default tag contents.
- **`MobTramplingMixin`** entity check changed from `instanceof Villager ||
  instanceof AbstractHorse` to `getType().is(TRMTTags.TRAMPLES)`. Multipliers
  now look up `erosionMultipliers.tramples` map first, then fall back to the
  legacy `villager`/`horse` fields for vanilla parity.
- **Mob trampling** now respects the Lightness potion (parity with player
  trampling — both already skipped erosion under Lightness, only the player
  was being checked).
- Description in `neoforge.mods.toml` rewritten — no longer says "in beta".

### Deprecated

- `TRMTConfig.Multipliers.villager` and `.horse` fields — use
  `erosionMultipliers.tramples` map instead. Kept as fallback for one
  release; will be removed in 1.1.

### Migration notes for upgraders from 0.6.0

- **Worlds:** load as version 0, get auto-migrated to version 1 on first
  save. No data loss.
- **Config:** existing `trmt.json` keeps working. New fields (`dimensions`,
  `seasonsMultipliers`, `allowInForcedChunks`, `tramples` map,
  `defaultTrample`) appear in the file on first save after upgrade.
- **Custom `villager`/`horse` multiplier overrides** in the config are still
  honored — they apply when the corresponding `tramples` map entry is
  missing. To migrate cleanly, copy your values into `erosionMultipliers.tramples`
  under keys `minecraft:villager` and `minecraft:horse`.
- **Client/server version mismatch:** the login handshake will refuse 0.x
  clients connecting to a 1.0 server (and vice versa). All players must
  update.

---

## [0.6.0] — 2026-05-16

### Added

- `erosion.pauseDeErosionWhenEmpty` config flag (default true) — pauses
  de-erosion while no players are online, so chunk-loaded paths don't
  disappear overnight on dedicated servers.

### Changed

- `EntityStepHandler.hasProtectedPlantAbove` now protects under
  `#minecraft:flowers` (small + tall flowers). Flower patches and bee farms
  survive foot traffic.

---

## [0.5.1] — 2026-05-15

### Fixed

- `eroded_dirt_path` now actually gets the shovel mining-speed bonus
  (added to `#minecraft:mineable/shovel`).
- `eroded_dirt_path` now drops dirt on break (was dropping nothing —
  `Properties.ofFullCopy` doesn't propagate the loot table reference when
  the source block uses default registry-id lookup; pinned with explicit
  `.lootFrom(() -> Blocks.DIRT_PATH)`).
- `copyJarToDist` Gradle path corrected from `../dist/` to `../../dist/`
  so the built jar actually lands in the directory the prism sync
  pipeline reads from.

---

## [0.5.0] — earlier

Initial pre-1.0 release with foot-traffic erosion mechanics, eroded blocks,
Lightness potion, mob trampling, Jade compat, and `CanErodeEvent` API.
