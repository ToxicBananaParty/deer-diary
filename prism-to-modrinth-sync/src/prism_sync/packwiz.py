"""Generate a Packwiz-compatible distribution tree from the Prism instance.

Output layout written under ``<output_dir>``::

    pack.toml                 ← pack metadata + index hash
    index.toml                ← file manifest (direct files + metafile pointers)
    mods/
      <slug>.pw.toml          ← metafile for each Modrinth- or CF-backed mod
      <name>.jar              ← self-hosted custom mod
    config/...                ← direct files
    resourcepacks/...
    shaderpacks/...
    defaultconfigs/...
    moonlight-global-datapacks/...

Design notes
------------

* Prism's own ``.pw.toml`` index files are valid Packwiz metafiles. We copy
  them through verbatim instead of regenerating, after sanity-checking the
  on-disk jar's SHA-512 against the recorded hash. See ``pwtoml.py``.

* For mod jars without an ``.index/`` metafile, we require an explicit
  self-host allowlist entry (e.g. ``mods/trmt-*.jar``). Anything else aborts
  the build with an actionable error — we never silently self-host a jar
  whose redistribution license is unknown.

* Optional files (the ``optional_files`` allowlist; in practice the one
  ``.disabled`` alternate renderer) ship as direct files preserving the
  ``.disabled`` suffix. Players toggle by renaming, same UX as the mrpack
  path.

* Index hashes are SHA-256; metafiles keep their original SHA-512 download
  hashes. Both are valid Packwiz per-file hash-format choices.
"""

from __future__ import annotations

import fnmatch
import logging
import shutil
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable

from .config import Config
from .customs import sync_custom_mods, print_summary as print_custom_summary
from .hashing import FileHashes, hash_bytes, hash_file
from .instance import (
    FileEntry,
    IgnoreMatcher,
    InstanceInfo,
    read_instance,
    read_packignore,
    walk_pack_files,
)
from .packwiz_settings import PackwizSettings
from .pwtoml import (
    PwTomlEntry,
    is_curseforge_only,
    read_pw_toml_indexes,
)


log = logging.getLogger(__name__)


@dataclass
class IndexFile:
    """One row in ``index.toml``'s ``[[files]]`` table."""

    file: str       # POSIX, relative to pack root
    hash: str       # sha256 hex
    metafile: bool = False
    preserve: bool = False
    alias: str = ""


@dataclass
class PackwizBuildResult:
    """Outcome of a single ``build_packwiz`` invocation."""

    output_dir: Path
    pack_toml_path: Path
    index_toml_path: Path
    instance: InstanceInfo
    direct_entries: list[IndexFile]
    metafile_entries: list[IndexFile]
    optional_paths: list[str]
    self_hosted_paths: list[str]
    cf_only_paths: list[str]
    pw_toml_mismatches: list[str]
    errors: list[str]
    warnings: list[str]
    fingerprint: dict[str, str] = field(default_factory=dict)

    @property
    def total_entries(self) -> int:
        return len(self.direct_entries) + len(self.metafile_entries)


class PackwizError(RuntimeError):
    pass


# ---------------------------------------------------------------------------
# TOML rendering
# ---------------------------------------------------------------------------
#
# We hand-write the two pack-root TOML files because Python's stdlib doesn't
# bundle a writer, and our schemas are simple enough that pulling in tomli-w
# isn't worth the dependency churn. All string values we serialize here are
# generated from filenames, hashes, and config values — they never contain
# quotes, backslashes, or non-ASCII, so the basic-string form is safe.


def _toml_str(s: str) -> str:
    """Render a string as a basic TOML string literal (with double quotes).

    Escapes the few characters that ever realistically appear in our inputs.
    For arbitrary user input you'd want a full TOML serializer, but our inputs
    are pack metadata: paths (forward-slash POSIX), hashes (hex), version
    numbers, and config values we control.
    """
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


def _render_pack_toml(
    *,
    name: str,
    author: str,
    version: str,
    pack_format: str,
    index_hash: str,
    minecraft_version: str,
    loader_key: str,
    loader_version: str,
) -> str:
    lines = [
        f"name = {_toml_str(name)}",
    ]
    if author:
        lines.append(f"author = {_toml_str(author)}")
    lines.append(f"version = {_toml_str(version)}")
    lines.append(f"pack-format = {_toml_str(pack_format)}")
    lines.append("")
    lines.append("[index]")
    lines.append('file = "index.toml"')
    lines.append('hash-format = "sha256"')
    lines.append(f"hash = {_toml_str(index_hash)}")
    lines.append("")
    lines.append("[versions]")
    lines.append(f"minecraft = {_toml_str(minecraft_version)}")
    lines.append(f"{loader_key} = {_toml_str(loader_version)}")
    lines.append("")
    return "\n".join(lines)


def _render_index_toml(entries: Iterable[IndexFile]) -> str:
    lines = ['hash-format = "sha256"', ""]
    for entry in sorted(entries, key=lambda e: e.file):
        lines.append("[[files]]")
        lines.append(f"file = {_toml_str(entry.file)}")
        lines.append(f"hash = {_toml_str(entry.hash)}")
        if entry.metafile:
            lines.append("metafile = true")
        if entry.preserve:
            lines.append("preserve = true")
        if entry.alias:
            lines.append(f"alias = {_toml_str(entry.alias)}")
        lines.append("")
    return "\n".join(lines)


# ---------------------------------------------------------------------------
# File classification
# ---------------------------------------------------------------------------


class _SelfHostMatcher:
    """Glob matcher for the ``[packwiz.self_host].allowed_globs`` allowlist.

    Patterns are matched against the minecraft-rooted POSIX path
    (e.g. ``mods/trmt-1.1.0-1.21+1.21.1.jar``). ``*`` is a single-segment
    wildcard; this is intentional — multi-segment ``**`` is overkill for
    "which jars may we self-host".
    """

    def __init__(self, globs: Iterable[str]) -> None:
        self._globs = [g.replace("\\", "/").lstrip("/") for g in globs if g]

    def matches(self, posix_path: str) -> bool:
        posix_path = posix_path.replace("\\", "/").lstrip("/")
        return any(fnmatch.fnmatchcase(posix_path, g) for g in self._globs)


def _is_mod_jar(path: str) -> bool:
    return path.startswith("mods/") and path.lower().endswith(".jar")


# ---------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------


def build_packwiz(
    config: Config,
    packwiz: PackwizSettings,
    *,
    version_id: str,
) -> PackwizBuildResult:
    """Generate a fresh Packwiz tree under ``packwiz.output_dir``.

    Writes into ``<output_dir>.tmp`` first and only swaps the final directory
    in once everything succeeds — partial trees never become visible.
    """
    if not packwiz.enabled:
        raise PackwizError(
            "Packwiz is disabled in config (set [packwiz].enabled = true)."
        )

    instance = read_instance(config.instance_path)

    # Refresh any [[custom_mods]] in the instance first; the walker picks up
    # the freshly-copied jars on its very next pass.
    if config.custom_mods:
        sync_result = sync_custom_mods(
            config.custom_mods, instance, config.config_dir
        )
        print_custom_summary(sync_result)

    ignore_patterns = list(read_packignore(instance.instance_path)) + list(
        config.extra_ignore
    )
    ignore = IgnoreMatcher(ignore_patterns)
    optional = IgnoreMatcher(config.optional_files) if config.optional_files else None
    self_host = _SelfHostMatcher(packwiz.self_host_allowed_globs)
    preserve = (
        IgnoreMatcher(packwiz.preserve_globs) if packwiz.preserve_globs else None
    )

    files = walk_pack_files(
        instance.minecraft_dir,
        config.include_paths,
        ignore,
        optional=optional,
        include_files=config.include_files,
    )
    pw_index = read_pw_toml_indexes(instance.minecraft_dir, config.include_paths)

    direct_entries: list[IndexFile] = []
    metafile_entries: list[IndexFile] = []
    optional_paths: list[str] = []
    self_hosted_paths: list[str] = []
    cf_only_paths: list[str] = []
    pw_mismatches: list[str] = []
    errors: list[str] = []
    warnings: list[str] = []

    # Build into a temp sibling directory so a partial tree never becomes the
    # published one. The final swap is rmtree+rename; not strictly atomic on
    # Windows, but the gap is microseconds and we serialize against publishes.
    tmp_dir = packwiz.output_dir.with_suffix(packwiz.output_dir.suffix + ".tmp")
    if tmp_dir.exists():
        shutil.rmtree(tmp_dir)
    tmp_dir.mkdir(parents=True, exist_ok=True)

    fingerprint: dict[str, str] = {}

    for entry in files:
        rel = entry.relative_to_minecraft
        pw_entry = pw_index.get(rel)

        # 1. Optional file (e.g. .disabled alt-renderer): always direct.
        if entry.optional:
            optional_paths.append(rel)
            _copy_direct(entry.absolute_path, tmp_dir / rel)
            hashes = hash_file(tmp_dir / rel)
            direct_entries.append(IndexFile(file=rel, hash=hashes.sha256))
            fingerprint[rel] = hashes.sha512
            continue

        # 2. Explicit self-host allowlist wins over any metafile. Use case:
        #    a CurseForge-only mod whose author granted us redistribution
        #    permission — we'd rather ship the jar directly than make every
        #    player configure a CurseForge API key in packwiz-installer.
        if self_host.matches(rel):
            self_hosted_paths.append(rel)
            _copy_direct(entry.absolute_path, tmp_dir / rel)
            hashes = hash_file(tmp_dir / rel)
            direct_entries.append(IndexFile(file=rel, hash=hashes.sha256))
            fingerprint[rel] = hashes.sha512
            continue

        # 3. Has a .pw.toml metafile (Modrinth-backed; CF-only only if NOT
        #    allowlisted above, in which case the player needs a CF API key).
        if pw_entry is not None:
            written = _emit_metafile(entry, pw_entry, tmp_dir, pw_mismatches, warnings)
            if written is not None:
                meta_rel, meta_hashes, jar_hashes = written
                metafile_entries.append(
                    IndexFile(file=meta_rel, hash=meta_hashes.sha256, metafile=True)
                )
                # Fingerprint mirrors the mrpack shape: key on the *deliverable's*
                # path (e.g. mods/foo.jar), not the metafile path, with the
                # underlying file's sha512 as the value. This keeps the two
                # pipelines' state files diff-comparable so a Modrinth publish
                # immediately followed by a Packwiz publish doesn't show every
                # mod as changed.
                fingerprint[rel] = jar_hashes.sha512
                if is_curseforge_only(pw_entry.data):
                    cf_only_paths.append(rel)
                continue
            # Fell through (hash mismatch) — fall into the error path below.

        # 4. Mod jar without any of the above: hard error.
        if _is_mod_jar(rel):
            errors.append(rel)
            continue

        # 5. Non-mod direct file (config, resourcepack, shader zip, datapack,
        #    or a top-level user-data file like servers.dat). preserve=true
        #    leaves player-local edits intact across updates; we only set it
        #    here, not in the mod-jar / metafile branches, because mods must
        #    stay in lockstep with the pack.
        _copy_direct(entry.absolute_path, tmp_dir / rel)
        hashes = hash_file(tmp_dir / rel)
        direct_entries.append(
            IndexFile(
                file=rel,
                hash=hashes.sha256,
                preserve=preserve is not None and preserve.matches(rel),
            )
        )
        fingerprint[rel] = hashes.sha512

    if errors:
        # Roll back the temp tree so partial state doesn't linger.
        shutil.rmtree(tmp_dir, ignore_errors=True)
        raise PackwizError(_format_error_message(errors, packwiz))

    # Write index.toml.
    index_bytes = _render_index_toml(direct_entries + metafile_entries).encode("utf-8")
    index_path = tmp_dir / "index.toml"
    index_path.write_bytes(index_bytes)
    index_hashes = hash_bytes(index_bytes)

    # Write pack.toml.
    pack_bytes = _render_pack_toml(
        name=config.name,
        author=packwiz.author,
        version=version_id,
        pack_format=packwiz.pack_format,
        index_hash=index_hashes.sha256,
        minecraft_version=instance.minecraft_version,
        loader_key=instance.loader.mrpack_key,
        loader_version=instance.loader.version,
    ).encode("utf-8")
    pack_path = tmp_dir / "pack.toml"
    pack_path.write_bytes(pack_bytes)

    # Atomic-ish swap into the final location.
    if packwiz.output_dir.exists():
        shutil.rmtree(packwiz.output_dir)
    tmp_dir.rename(packwiz.output_dir)

    final_pack = packwiz.output_dir / "pack.toml"
    final_index = packwiz.output_dir / "index.toml"

    return PackwizBuildResult(
        output_dir=packwiz.output_dir,
        pack_toml_path=final_pack,
        index_toml_path=final_index,
        instance=instance,
        direct_entries=direct_entries,
        metafile_entries=metafile_entries,
        optional_paths=optional_paths,
        self_hosted_paths=self_hosted_paths,
        cf_only_paths=cf_only_paths,
        pw_toml_mismatches=pw_mismatches,
        errors=errors,
        warnings=warnings,
        fingerprint=dict(sorted(fingerprint.items())),
    )


def _copy_direct(src: Path, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, dest)


def _emit_metafile(
    entry: FileEntry,
    pw_entry: PwTomlEntry,
    output_root: Path,
    mismatches: list[str],
    warnings: list[str],
) -> tuple[str, FileHashes, FileHashes] | None:
    """Copy a Prism ``.pw.toml`` into the Packwiz output tree.

    The metafile lives next to where its target jar *would* live (e.g.
    ``mods/<slug>.pw.toml``), not under ``.index/``. We verify the source
    jar's SHA-512 against the metafile's recorded download hash; on mismatch
    we skip this metafile and let the caller fall back to self-host
    classification (or fail).

    Returns ``(pack_relative_path, metafile_hashes, jar_hashes)`` on success,
    or ``None`` if the SHA-512 didn't match and we can't trust the metafile.
    The caller uses ``jar_hashes`` to record the deliverable's sha512 in
    the state fingerprint (matching the mrpack pipeline's key shape).
    """
    download = pw_entry.data.get("download") or {}
    pw_hash = (download.get("hash") or "").lower()
    pw_hash_format = download.get("hash-format")

    on_disk_hashes = hash_file(entry.absolute_path)
    if pw_hash_format == "sha512" and pw_hash and pw_hash != on_disk_hashes.sha512.lower():
        mismatches.append(entry.relative_to_minecraft)
        warnings.append(
            f"SHA-512 mismatch between {entry.relative_to_minecraft} on disk and "
            f"its .pw.toml metadata; skipping metafile emit. The file will be "
            f"considered for self-host classification next."
        )
        return None

    # Place the metafile at <sub>/<source_basename>. The Prism slug
    # (e.g. "3dskinlayers.pw.toml") matches what packwiz refresh would
    # generate, so consumers and `packwiz refresh` are happy.
    sub = entry.relative_to_minecraft.split("/", 1)[0]
    pack_rel = f"{sub}/{pw_entry.source.name}"
    dest = output_root / pack_rel
    dest.parent.mkdir(parents=True, exist_ok=True)
    # Read-modify-write rather than a plain copy: Prism sometimes writes
    # `side = ''` when Modrinth doesn't expose a side for the project.
    # packwiz-installer rejects that as "Invalid side name", even though
    # the Packwiz spec treats `side` as optional and defaulting to "both".
    # Normalize the value so the published metafile parses cleanly.
    src_bytes = pw_entry.source.read_bytes()
    fixed_bytes = _normalize_metafile_bytes(src_bytes)
    dest.write_bytes(fixed_bytes)

    meta_hashes = hash_file(dest)
    return pack_rel, meta_hashes, on_disk_hashes


def _normalize_metafile_bytes(data: bytes) -> bytes:
    """Fix Prism quirks that trip up packwiz-installer's stricter parser.

    Currently: rewrites empty ``side = ''`` (and ``side = ""``) to
    ``side = 'both'``. Anything else passes through unchanged.
    """
    text = data.decode("utf-8")
    text = text.replace("side = ''", "side = 'both'")
    text = text.replace('side = ""', 'side = "both"')
    return text.encode("utf-8")


def _format_error_message(errors: list[str], packwiz: PackwizSettings) -> str:
    bullet = "\n  ".join(errors)
    return (
        "Refusing to self-host the following mod jar(s) with no .pw.toml "
        "metadata and no allowed self-host glob:\n  "
        f"{bullet}\n\n"
        "Fix one of:\n"
        "  - Add the mod through Prism so it gets a Modrinth/CurseForge .pw.toml.\n"
        "  - Add an entry to [packwiz.self_host].allowed_globs if the jar "
        "is something you built yourself or have explicit redistribution rights for.\n"
        "  - Remove the jar from the instance.\n\n"
        f"Currently allowed self-host globs: {packwiz.self_host_allowed_globs!r}"
    )


# ---------------------------------------------------------------------------
# Public summary helper (used by the CLI)
# ---------------------------------------------------------------------------


def print_build_summary(result: PackwizBuildResult) -> None:
    print(f"Built Packwiz tree at {result.output_dir}")
    print(
        f"  Pack: {result.instance.instance_path}\n"
        f"  Minecraft {result.instance.minecraft_version}, "
        f"{result.instance.loader.mrpack_key} {result.instance.loader.version}"
    )
    print(
        f"  Files: {result.total_entries} "
        f"({len(result.metafile_entries)} metafiles, "
        f"{len(result.direct_entries)} direct)"
    )
    if result.self_hosted_paths:
        print(f"  Self-hosted: {len(result.self_hosted_paths)}")
        for p in result.self_hosted_paths:
            print(f"    + {p}")
    if result.cf_only_paths:
        print(
            f"  CurseForge metafiles (player needs CF API key): "
            f"{len(result.cf_only_paths)}"
        )
        for p in result.cf_only_paths:
            print(f"    ~ {p}")
    if result.optional_paths:
        print(f"  Optional/.disabled direct files: {len(result.optional_paths)}")
        for p in result.optional_paths:
            print(f"    * {p}")
    if result.pw_toml_mismatches:
        print(
            f"  Warning: {len(result.pw_toml_mismatches)} jar(s) had a SHA-512 "
            "mismatch with their .pw.toml metadata:"
        )
        for p in result.pw_toml_mismatches:
            print(f"    - {p}")
    if result.warnings:
        for w in result.warnings:
            print(f"  ! {w}")
