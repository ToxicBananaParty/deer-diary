# TRMT — The Roads More Travelled

A NeoForge 1.21.1 mod that adds foot-traffic erosion to Minecraft blocks. Walking on grass, dirt, sand, or leaves accumulates per-block trampling progress; once thresholds are crossed the block visibly erodes through stages and eventually becomes a dirt path. Paths slowly heal over time when nobody walks them.

- **Mod ID:** `trmt`
- **Minecraft:** 1.21.1
- **Loader:** NeoForge 21.1.172 (or newer)
- **License:** CC BY-NC 4.0

## What it does

- **Erosion chains:** vanilla `grass_block` → `eroded_grass_block` (5 stages) → `eroded_dirt_path`. Vanilla `dirt` → `eroded_dirt` (4 stages) → `eroded_coarse_dirt`. Vanilla `sand` → `eroded_sand` (5 stages). Leaves get destroyed at threshold (with configurable drop chance).
- **De-erosion:** every eroded block slowly reverts toward its source over configurable in-game days. Walking re-resets the timer. Paused entirely while no players are online (server-empty gate).
- **Mob trampling:** villagers and horses (and any entity in `#trmt:tramples`) cause erosion too.
- **Plant protection:** ground blocks with a flower / sapling / crop / berry / bamboo / sugar cane / cactus on top don't accumulate erosion — your gardens are safe.
- **Lightness potion:** brewable from Awkward + Feather; player (or mob) under this effect causes no erosion.

## Block tags (extension points)

All erosion participation is driven by data tags. Modpack authors can add modded blocks/mobs to these tags via datapack without touching TRMT code.

| Tag | Default members | Behavior |
|---|---|---|
| `#trmt:erodes_as_grass` | `minecraft:grass_block`, `trmt:eroded_grass_block` | Walking accumulates; fresh sources convert to eroded_grass_block stage 0. |
| `#trmt:erodes_as_dirt` | `minecraft:dirt`, `trmt:eroded_dirt`, `trmt:eroded_dirt_path`, *(optional)* `farmersdelight:rich_soil` | Walking accumulates; fresh sources convert to eroded_dirt stage 1. |
| `#trmt:erodes_as_sand` | `minecraft:sand`, `trmt:eroded_sand` | Walking accumulates; fresh sources convert to eroded_sand stage 0. |
| `#trmt:erodes_as_leaves` | *(empty; falls back to `LeavesBlock` instanceof)* | Walking accumulates; threshold = destroy block (+ optional drops). |
| `#trmt:protects_below_from_erosion` | `#minecraft:saplings`, `#minecraft:crops`, `#minecraft:flowers`, sweet_berry_bush, bamboo, bamboo_sapling, sugar_cane, cactus | Block above ground that disables erosion of the ground tile. |
| `#trmt:tramples` (entity-type) | villager, horse, donkey, mule, llama, trader_llama; optional Naturalist mobs | Entities whose ground steps cause erosion. |

## Public API (events)

All events live in `milkucha.trmt.api.*` and fire on `NeoForge.EVENT_BUS`. 1.x signatures are a stability contract — additions safe, removals/renames require 2.x.

| Event | Cancellable | When |
|---|---|---|
| `CanErodeEvent` | yes | Before each erosion transform. `getPlayer()` returns the triggering player (or null for mob/automatic). |
| `ErodedEvent` | no | After a successful erosion transform. |
| `CanDeErodeEvent` | yes | Before each de-erosion (random tick on eroded blocks). |
| `DeErodedEvent` | no | After a successful de-erosion. |

Example claim integration:
```java
@SubscribeEvent
public static void onTrmtCanErode(CanErodeEvent event) {
    ServerPlayer p = event.getPlayer();
    if (p != null && MyClaims.isProtected(event.getLevel(), event.getPos(), p)) {
        event.setCanceled(true);
    }
}
```

## Configuration

JSON config at `<game-dir>/config/trmt.json`, loaded on `ServerStartedEvent`. Reload in-game with `/trmt reloadconfig` (op level 2+). New fields auto-migrate on first save after upgrade.

Key sections:

- `erosion.*Enabled` — per-block-type toggles (grass / dirt / sand / leaves / vegetation / mobTrampling).
- `erosion.dirtPathEndpoint` — `true` (default) makes the grass chain end at `eroded_dirt_path`; `false` falls through to the legacy `eroded_dirt → eroded_coarse_dirt` chain.
- `erosion.pauseDeErosionWhenEmpty` — pause de-erosion while no players are online.
- `erosion.allowInForcedChunks` — when `false`, skip erosion/de-erosion in force-loaded chunks.
- `erosionMultipliers.player` / `.mounted` / `.tramples` (map keyed by entity-type id) / `.defaultTrample` — per-source step multipliers.
- `erosionThresholds.*` — min/max step thresholds per block class.
- `deErosionTimeoutDays.*` — in-game days before each stage de-erodes.
- `dimensions` — `mode` (`ALLOWLIST` or `BLOCKLIST`) + `list` of dimension IDs.
- `seasonsMultipliers` — per-season step multipliers (only used if SereneSeasons is installed).

## Soft-compat with other mods

TRMT auto-detects and integrates with these mods if they're installed. None are required.

| Mod | What it does | Notes |
|---|---|---|
| **Jade** | Shows erosion stage / walked-on count in the probe panel. | Already shipped. |
| **Open Parties and Claims** | Cancels player-driven erosion in claims the player isn't authorized to modify. | Mob trampling / random ticks aren't claim-gated — disable `mobTramplingEnabled` if you want claims fully erosion-free. |
| **LuckPerms** | Players with `trmt.bypass.erosion = true` cause no erosion. Useful for admins / build users. | Set with `/lp user <name> permission set trmt.bypass.erosion true`. |
| **SereneSeasons** | Modulates erosion rate by season (winter: ×0.5, autumn: ×1.2, spring/summer: baseline). | All multipliers tunable in `seasonsMultipliers`. |
| **Naturalist** | Naturalist's large mobs (deer/boar/bear/wolf/etc.) are pre-included in `#trmt:tramples` via optional entries. | Edit `erosionMultipliers.tramples` to tune per-mob multipliers. |
| **FarmersDelight** | `farmersdelight:rich_soil` is pre-included in `#trmt:erodes_as_dirt` via an optional entry. | |

## Commands

- `/trmt reloadconfig` (op level 2+) — reload `trmt.json` without restarting the world.

## Versioning

- **Save format version** is tracked in NBT (`milkucha.trmt.migration.SaveMigrator`). Forward-only migrations run automatically on load. A 2.x client cannot read a 1.x save unless the migrator covers it.
- **API version** is the mod version. 1.x guarantees that tags, event class signatures, and config field names remain compatible; new tags/events/fields may be added.
- **Network version** is part of a login-time handshake. Client must match server's TRMT version (no mixed-version play within a major).

## Known limitations / 1.0 follow-ups

- No automated test suite. Manual smoke-test verification only. Adding GameTest infrastructure is planned for 1.1.
- No in-game config GUI. Edit JSON + `/trmt reloadconfig`.
- No datagen. New tags are hand-authored JSON; if the tag list grows, this may be worth setting up.
- Single-MC-version build (1.21.1 only). Backports to 1.21.4 etc. would be separate branches.
- Localization is English-only. Mod-display strings are all translation-keyed; community translations welcome.
