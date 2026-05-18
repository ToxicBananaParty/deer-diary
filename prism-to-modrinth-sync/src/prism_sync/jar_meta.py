"""Read mod IDs and versions from NeoForge / Forge mod jars.

Used by the server pipeline as the cross-reference key between client jars
(walked from the published ``docs/packwiz/`` tree) and server jars (walked
from ``Custom Mods/server/mods/``). Mod ID is stable across filename
renames and version bumps, which a filename-prefix heuristic isn't.

For NeoForge 1.21.1 (this pack's loader) the metadata lives at
``META-INF/neoforge.mods.toml``. Older Forge mods used ``mods.toml`` in
the same dir; we fall back to that. Modrinth tooling tools that target
``[[mods]]`` from either file work for both. Datapacks (.zip) are not
mods and return None silently.
"""

from __future__ import annotations

import logging
import tomllib
import zipfile
from dataclasses import dataclass
from pathlib import Path


log = logging.getLogger(__name__)


@dataclass(frozen=True)
class JarMeta:
    """Subset of ``[[mods]]`` from a jar's mods.toml that we actually use.

    The mod ID is the only thing that survives author renames + version
    bumps cleanly, so it's our cross-reference key. Version is kept for
    informational display (e.g. classification report) and for the
    explicit-pin warning when a server jar lags behind the client.
    """

    mod_id: str
    version: str
    display_name: str = ""

    @property
    def filename_slug(self) -> str:
        """Heuristic slug from the mod ID (matches packwiz convention).

        Lowercase, underscores → hyphens. Most Modrinth slugs match this
        transformation of the mod ID; the few that don't get caught at
        match time, not here.
        """
        return self.mod_id.lower().replace("_", "-")


# Order matters: NeoForge first since the project targets NeoForge 21.1.228.
_CANDIDATE_PATHS = (
    "META-INF/neoforge.mods.toml",
    "META-INF/mods.toml",
)


def read_jar_meta(jar_path: Path) -> JarMeta | None:
    """Return the first ``[[mods]]`` entry's identity from ``jar_path``.

    Returns ``None`` if the file isn't a jar, has no recognized mods.toml,
    or the file is missing a ``modId``. The caller decides how to handle
    None — for the server pipeline that's a warning + filename-prefix
    fallback, not a hard failure.

    Datapack zips (no mods.toml) return None silently; the server build
    treats them as non-mods (always shipped as direct files) so there's
    no need for a mod-ID lookup.
    """
    if not jar_path.is_file():
        return None
    suffix = jar_path.suffix.lower()
    if suffix not in (".jar", ".zip"):
        return None

    try:
        with zipfile.ZipFile(jar_path) as zf:
            data = _read_first_match(zf, _CANDIDATE_PATHS)
    except (zipfile.BadZipFile, OSError) as exc:
        log.warning("Could not read %s as a zip: %s", jar_path, exc)
        return None

    if data is None:
        return None

    try:
        parsed = tomllib.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, tomllib.TOMLDecodeError) as exc:
        log.warning("mods.toml in %s is not valid TOML: %s", jar_path, exc)
        return None

    mods = parsed.get("mods") or []
    if not isinstance(mods, list) or not mods:
        return None
    first = mods[0]
    if not isinstance(first, dict):
        return None

    mod_id = str(first.get("modId") or "").strip()
    if not mod_id:
        return None

    version = str(first.get("version") or "").strip()
    # Mod authors often use ${version} as a Gradle-substitution placeholder.
    # When that hasn't been substituted (dev / IDE builds), we surface the
    # literal but flag it for the caller via the empty-display fallback.
    display = str(first.get("displayName") or "").strip()

    return JarMeta(mod_id=mod_id, version=version, display_name=display)


def _read_first_match(
    zf: zipfile.ZipFile, candidates: tuple[str, ...]
) -> bytes | None:
    """Return the first candidate path that exists in the zip, else None."""
    # zipfile.namelist() is O(n) per check; cache once.
    names = set(zf.namelist())
    for candidate in candidates:
        if candidate in names:
            with zf.open(candidate) as fh:
                return fh.read()
    return None


def collect_mod_ids(mods_dir: Path) -> dict[str, JarMeta]:
    """Build ``{mod_id: JarMeta}`` for every jar directly in ``mods_dir``.

    Used by the server build to cross-reference client + server mod sets
    by mod ID. Subdirectories are NOT recursed — only top-level jars
    (mirrors packwiz-installer's `mods/` semantics where nested dirs are
    rare and treated as separate concerns).

    Mods with no readable mods.toml are silently skipped (logged at DEBUG).
    Duplicate mod IDs (two jars with the same modId) win-by-mtime — the
    newer file wins, and a warning is logged.
    """
    if not mods_dir.is_dir():
        return {}

    out: dict[str, JarMeta] = {}
    duplicate_paths: dict[str, Path] = {}
    for entry in sorted(mods_dir.iterdir()):
        if not entry.is_file():
            continue
        if entry.suffix.lower() not in (".jar", ".zip"):
            continue
        meta = read_jar_meta(entry)
        if meta is None:
            log.debug("No mod ID in %s; skipping", entry.name)
            continue
        existing = out.get(meta.mod_id)
        if existing is not None:
            # Two jars with the same modId — keep the newer one, warn about it.
            prior = duplicate_paths.get(meta.mod_id)
            if prior is not None and prior.stat().st_mtime > entry.stat().st_mtime:
                log.warning(
                    "Duplicate mod ID %r in %s and %s; keeping %s (newer)",
                    meta.mod_id, prior.name, entry.name, prior.name,
                )
                continue
            log.warning(
                "Duplicate mod ID %r in %s and %s; overwriting with %s",
                meta.mod_id, (prior or entry).name, entry.name, entry.name,
            )
        out[meta.mod_id] = meta
        duplicate_paths[meta.mod_id] = entry
    return out
