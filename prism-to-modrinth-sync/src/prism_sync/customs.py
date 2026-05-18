"""Sync locally-built mod jars into the Prism instance before packing.

For mods you build yourself (e.g. a Gradle project producing
`build/libs/foo-X.Y.Z.jar`), this module checks whether the version in the
instance is current and replaces it if the source dir has a newer build.

Configured via `[[custom_mods]]` entries in config.toml.
"""

from __future__ import annotations

import logging
import re
import shutil
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable, Literal

from .hashing import hash_file
from .instance import InstanceInfo


log = logging.getLogger(__name__)


# Gradle-emitted sidecars we never want to ship.
_EXCLUDED_JAR_SUFFIXES = ("-sources.jar", "-javadoc.jar", "-dev.jar", "-all.jar")


Side = Literal["client", "server", "both"]
VALID_SIDES: tuple[Side, ...] = ("client", "server", "both")


@dataclass
class CustomModConfig:
    """One locally-built mod or datapack to keep in sync with the instance.

    `source_pattern` is the glob applied in both the source dir and the target
    dir to find the artifact. When left empty, it defaults to `{name}-*.jar`,
    which fits Gradle-emitted mod jars. Override with an explicit pattern
    (e.g. `"key-to-necklace.zip"` or `"*.zip"`) for datapacks, resourcepacks,
    or any artifact whose filename doesn't follow the `<name>-<version>.jar`
    convention.

    `side` controls which packs the artifact ends up in:
      - "client": shipped to client pack only; skipped by server sync.
      - "server": shipped to server pack only; sync drops the latest jar
        into <server_root>/<target_dir>/ for the server build to walk.
      - "both":   both packs; sync drops into the Prism instance AND the
        server folder.
    """

    name: str
    source_dir: Path
    target_dir: str = "mods"  # relative to <instance>/minecraft/
    source_pattern: str = ""
    side: Side = "both"

    @property
    def pattern(self) -> str:
        return self.source_pattern or f"{self.name}-*.jar"


@dataclass
class CustomModResult:
    name: str
    action: str  # "swapped" | "installed" | "up_to_date" | "warning"
    detail: str


@dataclass
class CustomModSyncResult:
    results: list[CustomModResult] = field(default_factory=list)

    @property
    def swapped(self) -> list[CustomModResult]:
        return [r for r in self.results if r.action == "swapped"]

    @property
    def installed(self) -> list[CustomModResult]:
        return [r for r in self.results if r.action == "installed"]

    @property
    def warnings(self) -> list[CustomModResult]:
        return [r for r in self.results if r.action == "warning"]

    @property
    def changed_anything(self) -> bool:
        return bool(self.swapped or self.installed)


def _version_tuple(name: str, filename: str) -> tuple[int, ...]:
    """Pull a comparable version tuple out of a jar filename.

    `<name>-1.2.3-4.5+6.7.8.jar` → (1, 2, 3, 4, 5, 6, 7, 8). Strips the mod
    name prefix so spurious digits in the name don't leak into the version,
    then takes every digit run from what's left.
    """
    stem = filename
    if stem.lower().endswith(".jar"):
        stem = stem[: -len(".jar")]
    prefix = f"{name}-"
    if stem.startswith(prefix):
        stem = stem[len(prefix) :]
    return tuple(int(n) for n in re.findall(r"\d+", stem))


def _candidate_jars(mod: CustomModConfig, search_dir: Path) -> list[Path]:
    if not search_dir.is_dir():
        return []
    return [
        p
        for p in sorted(search_dir.glob(mod.pattern))
        if p.is_file()
        and not any(p.name.endswith(suf) for suf in _EXCLUDED_JAR_SUFFIXES)
    ]


def _pick_latest(name: str, jars: list[Path]) -> Path:
    return max(jars, key=lambda p: _version_tuple(name, p.name))


def _resolve_source_dir(config_dir: Path, raw: Path | str) -> Path:
    source = Path(raw)
    if not source.is_absolute():
        source = (config_dir / source).resolve()
    return source


def sync_custom_mods(
    custom_mods: Iterable[CustomModConfig],
    instance: InstanceInfo,
    config_dir: Path,
    *,
    server_root: Path | None = None,
) -> CustomModSyncResult:
    """Replace each custom mod in the instance if the source dir has a newer build.

    If ``server_root`` is given, ALSO mirror the latest source jar into
    ``<server_root>/<target_dir>/`` for mods with ``side in ("server", "both")``.
    The server build walks ``server_root`` directly, so this keeps custom
    builds flowing into the server pack without manual copies. ``side="client"``
    mods are skipped from the server mirror; the instance sync is unchanged
    (everything still lands in the client instance regardless of side, since
    the existing client pipeline relies on it).
    """
    out = CustomModSyncResult()
    for mod in custom_mods:
        source_dir = _resolve_source_dir(config_dir, mod.source_dir)
        sources = _candidate_jars(mod, source_dir)
        if not sources:
            out.results.append(
                CustomModResult(
                    name=mod.name,
                    action="warning",
                    detail=(
                        f"no jars matching {mod.name}-*.jar in {source_dir} "
                        "(run the mod's Gradle build first)"
                    ),
                )
            )
            continue

        source_jar = _pick_latest(mod.name, sources)
        source_version = _version_tuple(mod.name, source_jar.name)

        target_root = instance.minecraft_dir / mod.target_dir
        target_root.mkdir(parents=True, exist_ok=True)
        targets = _candidate_jars(mod, target_root)

        # New install case — nothing to compare against.
        if not targets:
            dest = target_root / source_jar.name
            shutil.copy2(source_jar, dest)
            _mirror_to_server(mod, source_jar, server_root)
            out.results.append(
                CustomModResult(
                    name=mod.name,
                    action="installed",
                    detail=f"copied {source_jar.name} into {mod.target_dir}/",
                )
            )
            continue

        # If the instance has multiple, treat the newest as the incumbent and
        # plan to remove the rest.
        target_jar = _pick_latest(mod.name, targets)
        target_version = _version_tuple(mod.name, target_jar.name)

        if source_version < target_version:
            out.results.append(
                CustomModResult(
                    name=mod.name,
                    action="warning",
                    detail=(
                        f"source build {source_jar.name} is older than the "
                        f"instance's {target_jar.name}; refusing to downgrade. "
                        "Rebuild the mod or bump the source jar."
                    ),
                )
            )
            continue

        if source_version == target_version:
            # Same version — only replace if the bytes differ (e.g. dev rebuild).
            if hash_file(source_jar).sha512 == hash_file(target_jar).sha512:
                # Still re-mirror to server in case the server folder is stale
                # (e.g. first run after adding server_root parameter).
                _mirror_to_server(mod, source_jar, server_root)
                out.results.append(
                    CustomModResult(
                        name=mod.name,
                        action="up_to_date",
                        detail=f"{target_jar.name} matches source bytes",
                    )
                )
                continue
            detail_prefix = f"replaced same-version build {target_jar.name}"
        else:
            detail_prefix = f"upgraded {target_jar.name} -> {source_jar.name}"

        # Replace: copy source first (atomic-ish), then remove all old targets.
        dest = target_root / source_jar.name
        shutil.copy2(source_jar, dest)
        for old in targets:
            if old.resolve() == dest.resolve():
                continue
            old.unlink()

        # Clean up any stale .pw.toml metadata for this mod — custom mods are
        # never Modrinth-resolvable, so they should go through the
        # no-metadata → override path on the next build.
        index_dir = target_root / ".index"
        if index_dir.is_dir():
            for toml_path in index_dir.glob(f"{mod.name}*.pw.toml"):
                toml_path.unlink()

        _mirror_to_server(mod, source_jar, server_root)

        out.results.append(
            CustomModResult(name=mod.name, action="swapped", detail=detail_prefix)
        )

    return out


def _mirror_to_server(
    mod: CustomModConfig, source_jar: Path, server_root: Path | None
) -> None:
    """Drop the latest source jar into <server_root>/<target_dir>/ for server-
    side mods, and remove any older versions in the same dir.

    No-op when ``server_root`` is None or ``mod.side == "client"``.
    """
    if server_root is None or mod.side == "client":
        return

    server_dir = server_root / mod.target_dir
    server_dir.mkdir(parents=True, exist_ok=True)
    dest = server_dir / source_jar.name
    # Idempotent copy; shutil.copy2 overwrites.
    shutil.copy2(source_jar, dest)

    # Remove older versions of this same custom mod that may linger from a
    # prior sync (so the server folder always has just the latest jar).
    for old in _candidate_jars(mod, server_dir):
        if old.resolve() == dest.resolve():
            continue
        old.unlink()


def print_summary(result: CustomModSyncResult) -> None:
    if not result.results:
        return
    print("Custom mod sync:")
    for r in result.results:
        marker = {
            "swapped": "*",
            "installed": "+",
            "up_to_date": ".",
            "warning": "!",
        }.get(r.action, "?")
        print(f"  {marker} {r.name}: {r.detail}")
