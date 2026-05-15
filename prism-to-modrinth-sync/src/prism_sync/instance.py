from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


LOADER_UID_TO_MRPACK_KEY = {
    "net.neoforged": "neoforge",
    "net.minecraftforge": "forge",
    "net.fabricmc.fabric-loader": "fabric-loader",
    "org.quiltmc.quilt-loader": "quilt-loader",
}

LOADER_UID_TO_MODRINTH_TAG = {
    "net.neoforged": "neoforge",
    "net.minecraftforge": "forge",
    "net.fabricmc.fabric-loader": "fabric",
    "org.quiltmc.quilt-loader": "quilt",
}


@dataclass
class LoaderInfo:
    uid: str
    version: str

    @property
    def mrpack_key(self) -> str:
        try:
            return LOADER_UID_TO_MRPACK_KEY[self.uid]
        except KeyError:
            raise InstanceError(f"Unknown loader uid: {self.uid}")

    @property
    def modrinth_tag(self) -> str:
        try:
            return LOADER_UID_TO_MODRINTH_TAG[self.uid]
        except KeyError:
            raise InstanceError(f"Unknown loader uid: {self.uid}")


@dataclass
class InstanceInfo:
    instance_path: Path
    minecraft_dir: Path
    minecraft_version: str
    loader: LoaderInfo


class InstanceError(Exception):
    pass


def read_instance(instance_path: Path) -> InstanceInfo:
    if not instance_path.is_dir():
        raise InstanceError(f"Instance path is not a directory: {instance_path}")

    mmc_pack_path = instance_path / "mmc-pack.json"
    if not mmc_pack_path.is_file():
        raise InstanceError(f"Missing mmc-pack.json at {mmc_pack_path}")

    minecraft_dir = instance_path / "minecraft"
    if not minecraft_dir.is_dir():
        minecraft_dir = instance_path / ".minecraft"
    if not minecraft_dir.is_dir():
        raise InstanceError(
            f"Could not find minecraft/ or .minecraft/ under {instance_path}"
        )

    with mmc_pack_path.open("r", encoding="utf-8") as fh:
        data = json.load(fh)

    mc_version: str | None = None
    loader: LoaderInfo | None = None

    for component in data.get("components", []):
        uid = component.get("uid")
        version = component.get("version")
        if not uid or not version:
            continue
        if uid == "net.minecraft":
            mc_version = version
        elif uid in LOADER_UID_TO_MRPACK_KEY:
            loader = LoaderInfo(uid=uid, version=version)

    if not mc_version:
        raise InstanceError("Could not determine Minecraft version from mmc-pack.json")
    if not loader:
        raise InstanceError("Could not determine mod loader from mmc-pack.json")

    return InstanceInfo(
        instance_path=instance_path,
        minecraft_dir=minecraft_dir,
        minecraft_version=mc_version,
        loader=loader,
    )


def read_packignore(instance_path: Path) -> list[str]:
    """Read the instance's .packignore.

    .packignore entries are anchored at the instance root (typically starting with
    `minecraft/`). The rest of this module works in minecraft-rooted paths, so the
    leading `minecraft/` prefix is stripped here. Entries that point outside
    `minecraft/` are kept verbatim but won't match anything we walk.
    """
    path = instance_path / ".packignore"
    if not path.is_file():
        return []
    lines: list[str] = []
    with path.open("r", encoding="utf-8") as fh:
        for raw in fh:
            line = raw.strip().replace("\\", "/")
            if not line or line.startswith("#"):
                continue
            if line.startswith("minecraft/"):
                line = line[len("minecraft/") :]
            lines.append(line)
    return lines


def _glob_to_regex(pattern: str) -> re.Pattern[str]:
    pattern = pattern.replace("\\", "/")
    pattern = pattern.lstrip("/")
    out: list[str] = []
    i = 0
    while i < len(pattern):
        c = pattern[i]
        if c == "*":
            if i + 1 < len(pattern) and pattern[i + 1] == "*":
                if i + 2 < len(pattern) and pattern[i + 2] == "/":
                    out.append("(?:.*/)?")
                    i += 3
                    continue
                out.append(".*")
                i += 2
                continue
            out.append("[^/]*")
            i += 1
            continue
        if c == "?":
            out.append("[^/]")
            i += 1
            continue
        if c in ".+()|^$[]{}":
            out.append("\\" + c)
            i += 1
            continue
        out.append(c)
        i += 1
    return re.compile("^" + "".join(out) + "(?:/.*)?$")


class IgnoreMatcher:
    """Matches paths (posix-style, relative to a base) against gitignore-ish patterns.

    Each pattern matches the path itself OR any descendant if the pattern names a
    directory. Patterns may contain `*`, `?`, and `**`.
    """

    def __init__(self, patterns: Iterable[str]) -> None:
        self._patterns = [p.replace("\\", "/").lstrip("/") for p in patterns if p]
        self._regexes = [_glob_to_regex(p) for p in self._patterns]

    def matches(self, posix_path: str) -> bool:
        posix_path = posix_path.replace("\\", "/").lstrip("/")
        for regex in self._regexes:
            if regex.match(posix_path):
                return True
        return False


@dataclass
class FileEntry:
    """A file discovered on disk that should be considered for the pack."""

    absolute_path: Path
    relative_to_minecraft: str  # posix-style, e.g. "mods/Foo.jar"
    # True if this file was matched by an ignore pattern but force-included by
    # the optional_files allowlist (e.g. `.disabled` mods to ship for toggling).
    optional: bool = False


def _is_skipped_by_default(rel_to_mc: str) -> bool:
    """Skip paths that are never part of the pack regardless of config.

    - `<include_path>/.index/` is Prism's per-asset metadata (read separately).
    - `mods/.connector/` is Sinytra Connector's runtime cache of Fabric→NeoForge
      translated jars; regenerated by the mod at runtime, must not be bundled.
    """
    parts = rel_to_mc.split("/")
    if ".index" in parts:
        return True
    if rel_to_mc.startswith("mods/.connector/"):
        return True
    return False


def walk_pack_files(
    minecraft_dir: Path,
    include_paths: Iterable[str],
    ignore: IgnoreMatcher,
    optional: IgnoreMatcher | None = None,
) -> list[FileEntry]:
    """Walk include_paths under minecraft_dir, applying ignore patterns.

    `ignore` patterns are matched against paths relative to `minecraft/`. Callers
    must pre-strip any `minecraft/` prefix from .packignore-style entries (see
    `read_packignore`).

    `optional` is an allowlist matcher: paths it matches are included even when
    `ignore` would otherwise reject them, and the returned `FileEntry.optional`
    flag is set so the builder can ship them in `overrides/` for user toggling.
    The hardcoded `_is_skipped_by_default` paths are never overrideable.
    """
    results: list[FileEntry] = []
    for sub in include_paths:
        root = minecraft_dir / sub
        if not root.exists():
            continue
        for path in sorted(root.rglob("*")):
            if not path.is_file():
                continue
            rel_to_mc = path.relative_to(minecraft_dir).as_posix()
            if _is_skipped_by_default(rel_to_mc):
                continue
            ignored = ignore.matches(rel_to_mc)
            is_optional = optional is not None and optional.matches(rel_to_mc)
            if ignored and not is_optional:
                continue
            results.append(
                FileEntry(
                    absolute_path=path,
                    relative_to_minecraft=rel_to_mc,
                    optional=is_optional,
                )
            )
    return results
