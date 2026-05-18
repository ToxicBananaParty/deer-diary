"""Build the server-side Packwiz tree from Custom Mods/server/ + client passthrough.

The server pack ships at ``docs/packwiz-server/`` and is consumed by
BloomHost via ``packwiz-installer-bootstrap -s server``. Unlike the client
build (which walks the live Prism instance and resolves files inline), this
one is mostly a *projection* of two pre-existing trees:

* ``Custom Mods/server/`` — the authoritative server-side roster (you
  populate via ``server-bootstrap-from-sftp`` and maintain by hand).
* ``docs/packwiz/`` — the client pack we already published. Used as a
  resolution lookup: server jars whose mod ID matches a client metafile
  ride along on the client's Modrinth CDN reference (auto-update);
  server jars matching client-self-hosted jars get the client's jar bytes
  copied through.

Per-server-entry resolution priority (top wins):

  1. ``Custom Mods/server/mods/<slug>.pw.toml`` alongside a jar → explicit
     pin; copy verbatim (with side normalized).
  2. Standalone ``Custom Mods/server/mods/<slug>.pw.toml`` (no jar) → ship
     as metafile.
  3. Server jar's mod ID matches client → use the client's resolution
     (metafile copy OR self-hosted jar copy).
  4. Server jar with no client match → self-host (ship the bytes from the
     server folder as a direct file). If you've attached a ``.pw.toml`` via
     ``server-attach-metafiles``, that takes precedence (case 1 catches it).

Configs from the client's Prism instance ship to the server tree as a
``shared_config_paths`` overlay; ``Custom Mods/server/{config,defaultconfigs}``
wins on path collision.
"""

from __future__ import annotations

import logging
import shutil
import tomllib
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable

from .config import Config
from .customs import sync_custom_mods, print_summary as print_custom_summary
from .hashing import hash_bytes, hash_file
from .instance import (
    IgnoreMatcher,
    InstanceInfo,
    read_instance,
    read_packignore,
    walk_pack_files,
)
from .jar_meta import collect_mod_ids, read_jar_meta
from .packwiz_emit import (
    IndexFile,
    copy_direct,
    normalize_metafile_bytes,
    render_index_toml,
    render_pack_toml,
)
from .packwiz_settings import PackwizServerSettings, PackwizSettings


log = logging.getLogger(__name__)


class ServerBuildError(RuntimeError):
    """Raised for user-facing errors during server pack build."""


# ---------------------------------------------------------------------------
# Data classes
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class ClientEmittedMod:
    """One mod from the published client packwiz tree, indexed by mod ID.

    Either ``metafile_path`` (relative POSIX) is set (client ships as a
    Modrinth/CF metafile) OR ``jar_path`` is set (client self-hosts).
    Never both.
    """

    mod_id: str
    pack_rel: str               # e.g. "mods/create.pw.toml" or "mods/foo.jar"
    is_metafile: bool
    source_jar: Path | None     # the actual bytes (for self-hosted) or None


@dataclass
class ServerEntry:
    """One entry in Custom Mods/server/mods/ — a jar, a metafile, or both."""

    jar_path: Path | None       # the .jar file in Custom Mods/server/mods/
    pwtoml_path: Path | None    # an adjacent .pw.toml override (Modrinth/CF)
    mod_id: str | None          # from jar's neoforge.mods.toml; None for non-jars


@dataclass
class Resolution:
    """How a single server entry resolves into the published tree."""

    server_entry: ServerEntry
    source: str                 # one of: "explicit_pin", "client_metafile",
                                # "client_self_host", "server_only_metafile",
                                # "server_only_self_host"
    output_rel: str             # pack-relative POSIX path
    bytes_source: Path          # local file whose bytes we ship (or pwtoml src)
    is_metafile: bool           # True → emit as .pw.toml entry, else direct
    classification_label: str   # for the report (e.g. "client-matched (CDN)")


@dataclass
class ServerBuildResult:
    """Outcome of a single ``build_server_pack`` invocation."""

    output_dir: Path
    pack_toml_path: Path
    index_toml_path: Path
    instance: InstanceInfo
    direct_entries: list[IndexFile] = field(default_factory=list)
    metafile_entries: list[IndexFile] = field(default_factory=list)
    resolutions: list[Resolution] = field(default_factory=list)
    config_overrides: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    fingerprint: dict[str, str] = field(default_factory=dict)

    @property
    def total_entries(self) -> int:
        return len(self.direct_entries) + len(self.metafile_entries)


# ---------------------------------------------------------------------------
# Index helpers — read the published client tree
# ---------------------------------------------------------------------------


def _load_client_index(
    client_packwiz_dir: Path,
) -> dict[str, ClientEmittedMod]:
    """Build ``{mod_id: ClientEmittedMod}`` from the published client pack.

    Walks ``client_packwiz_dir/mods/`` (the emitted client tree, NOT the
    Prism instance). For each ``.pw.toml`` it reads the ``filename`` field
    and uses that as the jar pointer; for each ``.jar`` (self-hosted) it
    reads the mod ID directly. Either way, the result is keyed by mod ID
    so the server build can cross-reference quickly.

    Raises :class:`ServerBuildError` if the directory doesn't exist (the
    user must run ``prism_sync packwiz-build`` first).
    """
    mods_dir = client_packwiz_dir / "mods"
    if not mods_dir.is_dir():
        raise ServerBuildError(
            f"Client pack tree not found at {client_packwiz_dir}. "
            "Run `prism_sync packwiz-build` first; the server build "
            "needs the client's emitted tree to resolve mod IDs."
        )

    out: dict[str, ClientEmittedMod] = {}

    # First pass: metafiles. Read each .pw.toml to learn the jar filename it
    # points at; that filename's stem (modulo version) we use to derive the
    # mod ID via the SOURCE jar in the Prism instance? No — we want a stable
    # cross-ref. Better: read the mod ID from the on-disk jar that the
    # metafile would download to. But that jar may not be downloaded yet.
    #
    # Simplest cross-ref: scan the Prism instance's mods/ for the matching
    # filename and read its mod ID. Falls back to slug if not found.
    pwtoml_paths = sorted(mods_dir.glob("*.pw.toml"))
    for pwtoml in pwtoml_paths:
        meta = _mod_id_for_client_pwtoml(pwtoml)
        if meta is None:
            log.debug("Couldn't determine mod ID for %s; skipping", pwtoml.name)
            continue
        pack_rel = f"mods/{pwtoml.name}"
        out[meta] = ClientEmittedMod(
            mod_id=meta,
            pack_rel=pack_rel,
            is_metafile=True,
            source_jar=None,
        )

    # Second pass: self-hosted jars (direct files in mods/).
    for jar in sorted(mods_dir.glob("*.jar")):
        meta = read_jar_meta(jar)
        if meta is None:
            log.debug("Self-hosted client jar %s has no mod ID; skipping", jar.name)
            continue
        # If a metafile and a jar declare the same mod ID, the metafile is
        # the canonical client resolution (CF self-host case where both
        # exist in the published tree). Self-host wins only if there's no
        # metafile entry.
        if meta.mod_id in out:
            continue
        out[meta.mod_id] = ClientEmittedMod(
            mod_id=meta.mod_id,
            pack_rel=f"mods/{jar.name}",
            is_metafile=False,
            source_jar=jar,
        )
    return out


def _mod_id_for_client_pwtoml(pwtoml_path: Path) -> str | None:
    """Resolve a client .pw.toml's mod ID via its referenced jar.

    The .pw.toml has a ``filename`` field naming the jar it downloads.
    We look that jar up in the live Prism instance's mods/ to read its
    mods.toml. Returns None if the jar can't be found (the metafile may
    point at a version not currently installed, e.g. for a packwiz-only
    optional mod).
    """
    try:
        data = tomllib.loads(pwtoml_path.read_text(encoding="utf-8"))
    except (UnicodeDecodeError, tomllib.TOMLDecodeError):
        return None
    filename = data.get("filename")
    if not isinstance(filename, str) or not filename:
        return None

    # Find the actual jar in the Prism instance's mods directory.
    prism_jar = _find_prism_jar(pwtoml_path, filename)
    if prism_jar is None or not prism_jar.is_file():
        # Fall back to the slug (basename minus .pw.toml). Better than nothing.
        return _slug_from_pwtoml_name(pwtoml_path.name)
    meta = read_jar_meta(prism_jar)
    if meta is None:
        return _slug_from_pwtoml_name(pwtoml_path.name)
    return meta.mod_id


def _find_prism_jar(client_pwtoml: Path, filename: str) -> Path | None:
    """The client packwiz tree mirrors the Prism instance's mods/ layout.

    Given the client-tree path of a .pw.toml, walk up to the workspace
    root and look in the Prism instance's mods/ for the matching jar.
    The instance path is read from the config, but we don't have config
    here — so we use a relative-path heuristic: client_pwtoml lives at
    ``<repo>/docs/packwiz/mods/<slug>.pw.toml``; the Prism instance jar
    lives at ``<repo>/Deer Diary/minecraft/mods/<filename>``.

    This couples the heuristic to the project layout, but the alternative
    is plumbing the InstanceInfo through, which complicates a frequently-
    called helper. Acceptable since this is an internal module.
    """
    # docs/packwiz/mods/foo.pw.toml -> docs/packwiz/mods -> docs/packwiz -> docs -> repo_root
    repo_root = client_pwtoml.parents[3]
    candidate = repo_root / "Deer Diary" / "minecraft" / "mods" / filename
    return candidate


def _slug_from_pwtoml_name(name: str) -> str:
    if name.endswith(".pw.toml"):
        return name[: -len(".pw.toml")]
    return Path(name).stem


# ---------------------------------------------------------------------------
# Server-side walk
# ---------------------------------------------------------------------------


def _walk_server_mods(server_dir: Path) -> list[ServerEntry]:
    """Build the server entries from Custom Mods/server/mods/.

    Pairs each `.jar` with an adjacent same-stem `.pw.toml` if present.
    Standalone `.pw.toml`s (no matching jar) become metafile-only entries.
    """
    mods_dir = server_dir / "mods"
    if not mods_dir.is_dir():
        return []

    # First pass: jars (+ adjacent pwtomls). Index pwtomls by stem so the
    # second pass can spot standalones.
    pwtomls_by_stem: dict[str, Path] = {}
    for pw in mods_dir.glob("*.pw.toml"):
        stem = pw.name[: -len(".pw.toml")]
        pwtomls_by_stem[stem] = pw

    entries: list[ServerEntry] = []
    paired_stems: set[str] = set()
    for jar in sorted(mods_dir.glob("*.jar")):
        stem = jar.stem
        # Adjacent pwtoml: same stem as the jar OR same as the mod's slug
        # (which we determine from neoforge.mods.toml).
        meta = read_jar_meta(jar)
        mod_id = meta.mod_id if meta else None

        adj_pw: Path | None = None
        if stem in pwtomls_by_stem:
            adj_pw = pwtomls_by_stem[stem]
            paired_stems.add(stem)
        elif mod_id and mod_id in pwtomls_by_stem:
            adj_pw = pwtomls_by_stem[mod_id]
            paired_stems.add(mod_id)
        elif meta and meta.filename_slug in pwtomls_by_stem:
            adj_pw = pwtomls_by_stem[meta.filename_slug]
            paired_stems.add(meta.filename_slug)

        entries.append(
            ServerEntry(jar_path=jar, pwtoml_path=adj_pw, mod_id=mod_id)
        )

    # Second pass: standalone pwtomls (no jar partner).
    for stem, pw in sorted(pwtomls_by_stem.items()):
        if stem in paired_stems:
            continue
        # mod_id for a standalone pwtoml: take from filename slug. It won't
        # match anything in the client (client metafiles ARE in the client
        # tree, so a standalone pwtoml on server side is a server-only mod
        # by definition). The mod_id is just for the classification report.
        entries.append(
            ServerEntry(jar_path=None, pwtoml_path=pw, mod_id=stem)
        )

    return entries


# ---------------------------------------------------------------------------
# Per-entry resolution
# ---------------------------------------------------------------------------


def _resolve_entry(
    entry: ServerEntry,
    client_index: dict[str, ClientEmittedMod],
    self_host_globs: list[str],
) -> Resolution | None:
    """Decide how this server entry ships in the published tree."""

    # Case 1: explicit pin (adjacent .pw.toml on a server jar).
    if entry.pwtoml_path is not None and entry.jar_path is not None:
        return Resolution(
            server_entry=entry,
            source="explicit_pin",
            output_rel=f"mods/{entry.pwtoml_path.name}",
            bytes_source=entry.pwtoml_path,
            is_metafile=True,
            classification_label="explicit pin (server-side .pw.toml)",
        )

    # Case 2: standalone server-only metafile (no jar).
    if entry.pwtoml_path is not None and entry.jar_path is None:
        return Resolution(
            server_entry=entry,
            source="server_only_metafile",
            output_rel=f"mods/{entry.pwtoml_path.name}",
            bytes_source=entry.pwtoml_path,
            is_metafile=True,
            classification_label="server-only (attached metafile)",
        )

    # Cases 3-5: jar-only, look up by mod ID.
    if entry.jar_path is None:
        return None

    assert entry.jar_path is not None
    if entry.mod_id is None:
        # Jar without a readable mods.toml. Self-host as a last resort.
        return Resolution(
            server_entry=entry,
            source="server_only_self_host",
            output_rel=f"mods/{entry.jar_path.name}",
            bytes_source=entry.jar_path,
            is_metafile=False,
            classification_label="server-only (no mod ID; self-host)",
        )

    client = client_index.get(entry.mod_id)
    if client is not None:
        if client.is_metafile:
            # Client ships via Modrinth metafile → re-use it for server.
            return Resolution(
                server_entry=entry,
                source="client_metafile",
                output_rel=client.pack_rel,
                bytes_source=_client_pwtoml_path_for(client),
                is_metafile=True,
                classification_label="client-matched (Modrinth CDN)",
            )
        # Client self-hosts → reuse the jar bytes from the client tree.
        assert client.source_jar is not None
        return Resolution(
            server_entry=entry,
            source="client_self_host",
            output_rel=client.pack_rel,
            bytes_source=client.source_jar,
            is_metafile=False,
            classification_label="client-matched (self-host)",
        )

    # Server-only jar. Self-host via inherited / additional globs.
    # We don't enforce the allowlist here (unlike client build) because the
    # user explicitly placed the jar in the server folder — presence = consent.
    # The allowlist is still informational for the classification report.
    return Resolution(
        server_entry=entry,
        source="server_only_self_host",
        output_rel=f"mods/{entry.jar_path.name}",
        bytes_source=entry.jar_path,
        is_metafile=False,
        classification_label="server-only (self-host)",
    )


def _client_pwtoml_path_for(client: ClientEmittedMod) -> Path:
    """Resolve the absolute path to a client metafile from its pack-rel path.

    Internal helper: ``ClientEmittedMod.pack_rel`` is pack-relative; we need
    the actual file. The ``_load_client_index`` caller passes the
    ``client_packwiz_dir`` through to ``_emit_resolution`` which has both
    pieces, so we don't actually need this — but keeping the helper makes
    the resolution dataclass self-contained for testing.
    """
    # Not used in the current flow (see _emit_resolution which has the dir).
    # Kept as a placeholder for refactor cleanliness.
    return Path(client.pack_rel)


# ---------------------------------------------------------------------------
# Emit
# ---------------------------------------------------------------------------


def _emit_resolution(
    res: Resolution,
    *,
    output_dir: Path,
    client_packwiz_dir: Path,
    preserve_matcher: IgnoreMatcher | None,
) -> tuple[IndexFile, str]:
    """Write one resolved entry into the output tree. Returns (IndexFile, sha512)."""

    dest = output_dir / res.output_rel
    dest.parent.mkdir(parents=True, exist_ok=True)

    if res.is_metafile:
        # Source bytes: either the explicit-pin .pw.toml on the server side,
        # or the client-tree .pw.toml we're passing through.
        if res.source in ("client_metafile",):
            src_path = client_packwiz_dir / res.output_rel
        else:
            src_path = res.bytes_source

        src_bytes = src_path.read_bytes()
        # Force side='both' on the way out — server is the authoritative
        # presence signal, upstream side metadata is just a hint.
        out_bytes = normalize_metafile_bytes(src_bytes, force_side="both")
        dest.write_bytes(out_bytes)
        meta_hashes = hash_file(dest)
        idx = IndexFile(file=res.output_rel, hash=meta_hashes.sha256, metafile=True)
        # Fingerprint shape mirrors the client's: key on the deliverable's
        # POSIX path with the underlying file's sha512. For metafiles, the
        # "deliverable" is the .pw.toml itself.
        return idx, meta_hashes.sha512

    # Direct file: copy bytes.
    copy_direct(res.bytes_source, dest)
    hashes = hash_file(dest)
    preserve = preserve_matcher is not None and preserve_matcher.matches(res.output_rel)
    return (
        IndexFile(file=res.output_rel, hash=hashes.sha256, preserve=preserve),
        hashes.sha512,
    )


# ---------------------------------------------------------------------------
# Shared-config overlay
# ---------------------------------------------------------------------------


def _emit_shared_configs(
    *,
    output_dir: Path,
    instance: InstanceInfo,
    server_dir: Path,
    shared_paths: list[str],
    server_include_paths: list[str],
    extra_ignore: list[str],
    preserve_matcher: IgnoreMatcher | None,
) -> tuple[list[IndexFile], list[str], dict[str, str]]:
    """Emit shared configs from the client instance, with server overrides.

    Returns (direct_entries, list_of_overridden_paths, fingerprint_subset).

    Order: walk client first, then walk server. Server-side files written
    second naturally clobber the client-side file at the same dest path —
    we just record which paths got overridden for the build summary.
    """
    direct: list[IndexFile] = []
    overrides: list[str] = []
    fingerprint: dict[str, str] = {}

    ignore_patterns = list(read_packignore(instance.instance_path)) + list(extra_ignore)
    ignore = IgnoreMatcher(ignore_patterns)

    # Pass 1: client instance.
    client_files = walk_pack_files(
        instance.minecraft_dir,
        shared_paths,
        ignore,
    )
    client_paths: dict[str, Path] = {}
    for entry in client_files:
        rel = entry.relative_to_minecraft
        dest = output_dir / rel
        copy_direct(entry.absolute_path, dest)
        client_paths[rel] = dest

    # Pass 2: server-side overlay. Re-emit anything in shared_config_paths
    # that exists in the server folder; if it coincides with a client path,
    # record an override.
    server_files = _walk_server_overlay(server_dir, server_include_paths)
    for rel, src in server_files.items():
        if rel.startswith("mods/"):
            continue  # mods handled by the resolution path, not the overlay
        dest = output_dir / rel
        copy_direct(src, dest)
        if rel in client_paths:
            overrides.append(rel)

    # Hash every emitted shared/server file ONCE, after both passes (so we
    # only hash the final version on disk, not the intermediate).
    all_paths = set(client_paths.keys()) | {
        r for r in server_files.keys() if not r.startswith("mods/")
    }
    for rel in sorted(all_paths):
        dest = output_dir / rel
        if not dest.is_file():
            continue
        hashes = hash_file(dest)
        preserve = preserve_matcher is not None and preserve_matcher.matches(rel)
        direct.append(IndexFile(file=rel, hash=hashes.sha256, preserve=preserve))
        fingerprint[rel] = hashes.sha512

    return direct, overrides, fingerprint


def _walk_server_overlay(
    server_dir: Path, include_paths: list[str]
) -> dict[str, Path]:
    """Walk Custom Mods/server/<sub>/ for each sub in include_paths.

    Returns ``{pack_relative_posix: absolute_source_path}``. Skips dotfiles
    and the special .index/ that Prism uses (servers shouldn't have it but
    safety).
    """
    out: dict[str, Path] = {}
    for sub in include_paths:
        root = server_dir / sub
        if not root.is_dir():
            continue
        for path in root.rglob("*"):
            if not path.is_file():
                continue
            rel_parts = path.relative_to(server_dir).parts
            if any(p.startswith(".") for p in rel_parts):
                continue
            if ".index" in rel_parts:
                continue
            rel = "/".join(rel_parts)
            out[rel] = path
    return out


# ---------------------------------------------------------------------------
# Top-level build
# ---------------------------------------------------------------------------


def build_server_pack(
    config: Config,
    *,
    version_id: str,
) -> ServerBuildResult:
    """Generate a fresh server Packwiz tree under ``packwiz_server.output_dir``.

    Requires:
      - ``[packwiz_server].enabled = true``
      - ``Custom Mods/server/mods/`` to exist (populate via
        ``server-bootstrap-from-sftp``)
      - ``docs/packwiz/`` to exist (run ``packwiz-build`` first)
    """
    server_settings = config.packwiz_server
    client_settings = config.packwiz

    if not server_settings.enabled:
        raise ServerBuildError(
            "Server pipeline is not enabled. Set [packwiz_server].enabled = true."
        )

    instance = read_instance(config.instance_path)

    # Run custom-mod sync — refreshes side='server'/'both' builds into both
    # the Prism instance AND Custom Mods/server/mods/.
    if config.custom_mods:
        sync_result = sync_custom_mods(
            config.custom_mods,
            instance,
            config.config_dir,
            server_root=server_settings.server_dir,
        )
        print_custom_summary(sync_result)

    # Load the client resolution map.
    client_index = _load_client_index(client_settings.output_dir)
    log.info("Loaded %d client mod IDs from %s", len(client_index), client_settings.output_dir)

    # Walk server-side mods.
    server_entries = _walk_server_mods(server_settings.server_dir)
    if not server_entries:
        raise ServerBuildError(
            f"No mods found in {server_settings.server_dir}/mods/. "
            "Run `prism_sync server-bootstrap-from-sftp` to populate from "
            "the live BloomHost server."
        )

    # Build into a temp sibling dir so a partial tree never becomes published.
    tmp_dir = server_settings.output_dir.with_suffix(
        server_settings.output_dir.suffix + ".tmp"
    )
    if tmp_dir.exists():
        shutil.rmtree(tmp_dir)
    tmp_dir.mkdir(parents=True, exist_ok=True)

    self_host_globs = server_settings.effective_self_host_globs(
        client_settings.self_host_allowed_globs
    )
    preserve_matcher = (
        IgnoreMatcher(server_settings.preserve_globs)
        if server_settings.preserve_globs
        else None
    )

    # Resolve + emit each server entry.
    direct_entries: list[IndexFile] = []
    metafile_entries: list[IndexFile] = []
    resolutions: list[Resolution] = []
    warnings: list[str] = []
    fingerprint: dict[str, str] = {}

    for entry in server_entries:
        res = _resolve_entry(entry, client_index, self_host_globs)
        if res is None:
            continue
        resolutions.append(res)
        idx, sha512 = _emit_resolution(
            res,
            output_dir=tmp_dir,
            client_packwiz_dir=client_settings.output_dir,
            preserve_matcher=preserve_matcher,
        )
        if idx.metafile:
            metafile_entries.append(idx)
        else:
            direct_entries.append(idx)
        fingerprint[res.output_rel] = sha512

    # Shared configs + server overlay.
    config_direct, config_overrides, config_fp = _emit_shared_configs(
        output_dir=tmp_dir,
        instance=instance,
        server_dir=server_settings.server_dir,
        shared_paths=server_settings.shared_config_paths,
        server_include_paths=server_settings.include_paths,
        extra_ignore=config.extra_ignore,
        preserve_matcher=preserve_matcher,
    )
    direct_entries.extend(config_direct)
    fingerprint.update(config_fp)

    # Render index.toml + pack.toml.
    index_bytes = render_index_toml(direct_entries + metafile_entries).encode("utf-8")
    index_path = tmp_dir / "index.toml"
    index_path.write_bytes(index_bytes)
    index_hashes = hash_bytes(index_bytes)

    pack_bytes = render_pack_toml(
        name=config.name,
        author=server_settings.author,
        version=version_id,
        pack_format=server_settings.pack_format,
        index_hash=index_hashes.sha256,
        minecraft_version=instance.minecraft_version,
        loader_key=instance.loader.mrpack_key,
        loader_version=instance.loader.version,
    ).encode("utf-8")
    pack_path = tmp_dir / "pack.toml"
    pack_path.write_bytes(pack_bytes)

    # Atomic-ish swap into final location.
    if server_settings.output_dir.exists():
        shutil.rmtree(server_settings.output_dir)
    tmp_dir.rename(server_settings.output_dir)

    final_pack = server_settings.output_dir / "pack.toml"
    final_index = server_settings.output_dir / "index.toml"

    return ServerBuildResult(
        output_dir=server_settings.output_dir,
        pack_toml_path=final_pack,
        index_toml_path=final_index,
        instance=instance,
        direct_entries=direct_entries,
        metafile_entries=metafile_entries,
        resolutions=resolutions,
        config_overrides=config_overrides,
        warnings=warnings,
        fingerprint=dict(sorted(fingerprint.items())),
    )


# ---------------------------------------------------------------------------
# Summary print
# ---------------------------------------------------------------------------


def print_build_summary(result: ServerBuildResult, *, verbose: bool = False) -> None:
    print(f"Built server Packwiz tree at {result.output_dir}")
    print(f"  Pack: {result.instance.instance_path}")
    print(
        f"  Minecraft {result.instance.minecraft_version}, "
        f"{result.instance.loader.mrpack_key} {result.instance.loader.version}"
    )
    print(
        f"  Files: {result.total_entries} "
        f"({len(result.metafile_entries)} metafiles, "
        f"{len(result.direct_entries)} direct)"
    )

    # Group resolutions by classification for the report.
    by_label: dict[str, list[Resolution]] = {}
    for res in result.resolutions:
        by_label.setdefault(res.classification_label, []).append(res)

    print()
    print("Mod resolution:")
    for label in sorted(by_label.keys()):
        entries = by_label[label]
        print(f"  {len(entries):>3}  {label}")
        if verbose:
            for res in entries:
                ident = res.server_entry.mod_id or "?"
                print(f"        - {ident} -> {res.output_rel}")

    if result.config_overrides:
        print()
        print(f"Config overrides (server-side wins): {len(result.config_overrides)}")
        for path in result.config_overrides[:10]:
            print(f"    ~ {path}")
        if len(result.config_overrides) > 10:
            print(f"    ... and {len(result.config_overrides) - 10} more")

    if result.warnings:
        print()
        for w in result.warnings:
            print(f"  ! {w}")
