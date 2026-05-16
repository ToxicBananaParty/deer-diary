"""Reading and classifying Prism / Packwiz ``.pw.toml`` metafiles.

Prism Launcher writes a ``.pw.toml`` per managed mod into
``<minecraft>/<include_path>/.index/``. The format is the same one Packwiz
itself uses for its distribution metafiles — so for the Packwiz publish
pipeline we can copy these through almost verbatim instead of synthesizing
fresh metadata.

The Modrinth (.mrpack) pipeline uses the same helpers to decide whether a
given mod can be CDN-referenced or has to be bundled into ``overrides/``.
"""

from __future__ import annotations

import logging
import tomllib
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


log = logging.getLogger(__name__)


@dataclass(frozen=True)
class PwTomlEntry:
    """A parsed ``.pw.toml`` paired with the on-disk path it came from.

    The source path is needed by the Packwiz pipeline (which copies the
    metafile bytes through to the published tree) but ignored by the mrpack
    pipeline (which only inspects the parsed data).
    """

    source: Path
    data: dict

# Hosts whose URLs we trust as canonical CDN links. Currently just Modrinth.
ALLOWED_DOWNLOAD_HOSTS = ("cdn.modrinth.com",)

# Top-level keys Prism writes into its .pw.toml files that Packwiz doesn't
# need. Harmless to ship, but stripping them keeps diffs cleaner and the
# published metafiles closer to what `packwiz refresh` would emit.
PRISM_EXTRA_KEYS = (
    "x-prismlauncher-dependencies",
    "x-prismlauncher-loaders",
    "x-prismlauncher-mc-versions",
    "x-prismlauncher-release-type",
    "x-prismlauncher-version-number",
)


def read_pw_toml_indexes(
    minecraft_dir: Path, include_paths: Iterable[str]
) -> dict[str, PwTomlEntry]:
    """Read ``.index/*.pw.toml`` from every include path that has one.

    Returns ``{minecraft-rooted-path: PwTomlEntry}``, e.g.
    ``{"mods/Foo.jar": PwTomlEntry(source=..., data={...})}``. Files that
    can't be parsed are skipped with a warning rather than failing the whole
    walk — a single corrupt index entry shouldn't break a build.
    """
    out: dict[str, PwTomlEntry] = {}
    for sub in include_paths:
        index_dir = minecraft_dir / sub / ".index"
        if not index_dir.is_dir():
            continue
        for toml_path in index_dir.glob("*.pw.toml"):
            try:
                with toml_path.open("rb") as fh:
                    data = tomllib.load(fh)
            except Exception as exc:
                log.warning("Failed to parse %s: %s", toml_path, exc)
                continue
            filename = data.get("filename")
            if not filename:
                continue
            out[f"{sub}/{filename}"] = PwTomlEntry(source=toml_path, data=data)
    return out


def is_curseforge_only(data: dict) -> bool:
    """True if the metafile resolves via CurseForge (no direct download URL).

    Prism marks these with ``[download].mode = "metadata:curseforge"``.
    Packwiz-installer can still fetch them, but only with a CurseForge API
    key configured on the consumer side.
    """
    download = data.get("download") or {}
    mode = download.get("mode") or ""
    return mode.startswith("metadata:curseforge")


def modrinth_cdn_entry(data: dict) -> tuple[str, str] | None:
    """Return ``(cdn_url, sha512)`` for a Modrinth-CDN-backed metafile.

    Returns ``None`` when the metafile is not a Modrinth-CDN URL+sha512 entry
    (i.e. CurseForge-resolved, missing fields, or wrong hash algorithm). The
    Modrinth pipeline uses this to decide between CDN-referencing and
    bundling into overrides.
    """
    download = data.get("download") or {}
    mode = download.get("mode")
    url = download.get("url")
    hash_value = download.get("hash")
    hash_format = download.get("hash-format")
    if mode != "url" or not url or not hash_value or hash_format != "sha512":
        return None
    if not any(host in url for host in ALLOWED_DOWNLOAD_HOSTS):
        return None
    return url, hash_value


def strip_prism_extras(data: dict) -> dict:
    """Return a copy of ``data`` with Prism-specific ``x-*`` keys removed."""
    return {k: v for k, v in data.items() if k not in PRISM_EXTRA_KEYS}
