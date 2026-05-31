# Deer Diary Patches

A container mod for small patches against third-party mods in the Deer Diary
modpack. Two flavors:

- **Mixin patches** live in a per-target subpackage
  (`toxicbananaparty.ddp.<targetmod>.mixin`) with their own Mixin config
  (`deer_diary_patches.<targetmod>.mixins.json`).
- **Integration patches** that only use public APIs / events live in a
  per-target subpackage without a Mixin config (e.g.
  `toxicbananaparty.ddp.aeronautics`), wired from the main mod class behind a
  `ModList` presence guard.

## Patches

### Create Aeronautics — Aviator's Goggles in the Curios head slot

Lets Aeronautics' Aviator's Goggles be worn in the Curios "head" slot *and*
keep working there. Two parts, both via public API (no mixin):

- **Placement.** Curios' tag-based slot assignment skips `ArmorItem`s, so the
  goggles never gained the `ICurio` capability. `AviatorsGogglesCurios`
  attaches `CuriosCapability.ITEM` explicitly via `RegisterCapabilitiesEvent`.
  The `ICurio` is deliberately bare (no attribute modifiers) — the curios slot
  grants **no armor**, so there's no double-dipping with a real helmet.
- **Function.** The goggles' only behavior is to switch on Create's goggle HUD
  overlay, which Aeronautics enables via
  `GogglesItem.addIsWearingPredicate(p -> goggles in vanilla HEAD slot)`. We
  register a *second* predicate (Create OR-combines them) that also fires when
  the goggles sit in a curios slot, so the overlay lights up either way.

Ships its own datapack data so the feature is self-contained:
`data/curios/tags/item/head.json` (satisfies the head slot's tag validator)
and `data/deer_diary_patches/curios/entities/player.json` (grants players the
head curios slot). The whole bridge is guarded by a `ModList` check for
curios + create + aeronautics.

> Not handled: rendering the goggles model on the player's head while in the
> curios slot (functional + armor-free placement only). Curios slot grants no
> armor by design.

### Sable — suppress UDP "invalid packet ID" log spam

[`dev.ryanhcode.sable`](https://modrinth.com/mod/sable) runs its own UDP
listener for sub-level physics traffic. Its
`SableUDPPacketDecoder.decode` throws `IOException("Received an invalid
packet ID: N")` whenever a datagram's first byte falls outside the known
packet-type range — which happens constantly in the wild (port scanners,
legacy MC 1.6 ping probes whose handshake byte is `0xFE`, stray DNS /
amplification probes, version-mismatched clients). The datagram is
discarded either way; only the netty ERROR is noise.

`SableUDPPacketDecoderMixin` cancels the decode method just before the
throw and logs at DEBUG instead. Stopgap — proper fix lives upstream in
Sable.

### Minecraft — suppress unencodable-disconnect log spam

Vanilla `Connection.exceptionCaught` always tries to send a
`ClientboundDisconnectPacket` on a clientbound channel exception. That
packet only exists in the `LOGIN` / `CONFIGURATION` / `PLAY` protocols, so
when the exception fires in `HANDSHAKING` or `STATUS` (port scanners,
cracked clients probing the port, half-dead server pings, etc.), the codec
throws `EncoderException` and `PacketEncoder` logs an `Error sending packet
clientbound/minecraft:disconnect` per occurrence. The connection is torn
down regardless — only the log line is noise.

`ConnectionMixin` `@WrapOperation`s the specific `send(...)` call inside
`exceptionCaught`: in HANDSHAKING/STATUS it skips the send and closes the
channel directly via `disconnect(...)`. LOGIN / CONFIGURATION / PLAY paths
are unchanged.

### Tom's Simple Storage — PolyFilter read/extract bypass

Fixes the bug in [tom5454/Toms-Storage#459](https://github.com/tom5454/Toms-Storage/issues/459)
and [#572](https://github.com/tom5454/Toms-Storage/issues/572): placing a
**Polymorphic Item Filter** on an **Inventory Cable Connector** hides the
target inventory's existing items from the terminal and blocks extraction.

The Polymorphic filter should gate **insertion only**. The mixin makes
`PlatformFilteredInventoryAccess.getStackInSlot` /
`extractItem` / `pullMatchingStack` bypass the filter check when
`filter.getItemPred()` is a `PolyFilter`, while leaving
`insertItem` / `pushStack` / `isItemValid` untouched (so the insert gate
behaves as designed).

## Build

```
./gradlew build
```

Produces `../../dist/deer-diary-patches/deer-diary-patches-<version>.jar`,
which the workspace's `prism-to-modrinth-sync` pipeline picks up
automatically on the next publish.
