from __future__ import annotations

import hashlib
import io
import json
import logging
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable
from urllib.parse import quote, unquote, urlsplit, urlunsplit

import requests

from .config import Config
from .hashing import FileHashes, hash_file
from .instance import (
    FileEntry,
    IgnoreMatcher,
    InstanceInfo,
    read_instance,
    read_packignore,
    walk_pack_files,
)
from .pwtoml import (
    ALLOWED_DOWNLOAD_HOSTS,
    is_curseforge_only as _pw_toml_is_curseforge_only,
    modrinth_cdn_entry as _pw_toml_modrinth_entry,
    read_pw_toml_indexes as _read_pw_toml_indexes,
)


log = logging.getLogger(__name__)

MODRINTH_API_BASE = "https://api.modrinth.com/v2"
HASH_LOOKUP_BATCH_SIZE = 100


@dataclass
class MrpackFileEntry:
    """One entry in modrinth.index.json `files[]`."""

    path: str  # posix, e.g. "mods/Foo.jar"
    sha1: str
    sha512: str
    file_size: int
    downloads: list[str]

    def to_json(self) -> dict:
        return {
            "path": self.path,
            "hashes": {"sha1": self.sha1, "sha512": self.sha512},
            "downloads": list(self.downloads),
            "fileSize": self.file_size,
        }


@dataclass
class OverrideEntry:
    """A file to be embedded under overrides/ in the mrpack."""

    arcname: str  # posix, including "overrides/" prefix
    source_path: Path
    hashes: FileHashes


@dataclass
class BuildResult:
    output_path: Path
    instance: InstanceInfo
    file_entries: list[MrpackFileEntry]
    overrides: list[OverrideEntry]
    unresolved_mods: list[str] = field(default_factory=list)
    pw_toml_mismatches: list[str] = field(default_factory=list)
    optional_files: list[str] = field(default_factory=list)

    @property
    def fingerprint(self) -> dict[str, str]:
        """Map of pack-internal path → sha512, used by the differ."""
        fp: dict[str, str] = {}
        for entry in self.file_entries:
            fp[entry.path] = entry.sha512
        for override in self.overrides:
            assert override.arcname.startswith("overrides/")
            fp[override.arcname[len("overrides/") :]] = override.hashes.sha512
        return dict(sorted(fp.items()))


def _normalize_cdn_url(url: str) -> str:
    """Fully percent-encode the path of a CDN URL.

    Prism's `.pw.toml` files percent-encode some special characters (`(`, `)`,
    `'`) but leave spaces literal, which Modrinth's URL validator rejects when
    publishing an mrpack. Normalize by decoding and re-encoding the path so the
    final URL passes strict RFC 3986 validation.
    """
    parts = urlsplit(url)
    normalized_path = quote(unquote(parts.path), safe="/")
    return urlunsplit(
        (parts.scheme, parts.netloc, normalized_path, parts.query, parts.fragment)
    )


def _modrinth_hash_lookup(
    sha1_hashes: list[str], user_agent: str
) -> dict[str, dict]:
    """POST /v2/version_files with sha1 hashes. Returns {sha1: version_obj}."""
    if not sha1_hashes:
        return {}
    headers = {
        "User-Agent": user_agent or "prism-to-modrinth-sync/0.1.0",
        "Content-Type": "application/json",
    }
    out: dict[str, dict] = {}
    for i in range(0, len(sha1_hashes), HASH_LOOKUP_BATCH_SIZE):
        batch = sha1_hashes[i : i + HASH_LOOKUP_BATCH_SIZE]
        resp = requests.post(
            f"{MODRINTH_API_BASE}/version_files",
            headers=headers,
            json={"hashes": batch, "algorithm": "sha1"},
            timeout=30,
        )
        resp.raise_for_status()
        out.update(resp.json())
    return out


def _resolve_version_obj_for_sha1(
    version_obj: dict, sha1: str
) -> tuple[str, str, int] | None:
    """From a version response, return (cdn_url, sha512, size) for the file
    matching `sha1`, or None if not found / not on the Modrinth CDN."""
    for file_obj in version_obj.get("files", []):
        file_hashes = file_obj.get("hashes") or {}
        if file_hashes.get("sha1") != sha1:
            continue
        url = file_obj.get("url") or ""
        if not any(host in url for host in ALLOWED_DOWNLOAD_HOSTS):
            return None
        sha512 = file_hashes.get("sha512")
        size = file_obj.get("size")
        if not sha512 or size is None:
            return None
        return url, sha512, int(size)
    return None


def _format_version_id(version_id: str | None) -> str:
    return version_id or "0.0.0"


def build_mrpack(
    config: Config,
    output_path: Path,
    version_id: str | None = None,
) -> BuildResult:
    instance = read_instance(config.instance_path)

    ignore_patterns = list(read_packignore(instance.instance_path)) + list(
        config.extra_ignore
    )
    ignore = IgnoreMatcher(ignore_patterns)
    optional = IgnoreMatcher(config.optional_files) if config.optional_files else None

    files = walk_pack_files(
        instance.minecraft_dir,
        config.include_paths,
        ignore,
        optional=optional,
        include_files=config.include_files,
    )
    pw_index = _read_pw_toml_indexes(instance.minecraft_dir, config.include_paths)

    mrpack_entries: list[MrpackFileEntry] = []
    overrides: list[OverrideEntry] = []
    unresolved_mods: list[str] = []
    pw_mismatches: list[str] = []
    optional_paths: list[str] = []

    pending_hash_lookup: list[tuple[FileEntry, FileHashes]] = []

    def _add_override(entry: FileEntry, hashes: FileHashes) -> None:
        overrides.append(
            OverrideEntry(
                arcname=f"overrides/{entry.relative_to_minecraft}",
                source_path=entry.absolute_path,
                hashes=hashes,
            )
        )

    for entry in files:
        is_mod_jar = (
            entry.relative_to_minecraft.startswith("mods/")
            and entry.absolute_path.suffix.lower() == ".jar"
        )

        hashes = hash_file(entry.absolute_path)

        # Optional files (e.g. .disabled mods kept for user toggling) always
        # ship verbatim as overrides — CDN lookup would map to a different
        # filename, breaking the on-disk name the consumer sees.
        if entry.optional:
            optional_paths.append(entry.relative_to_minecraft)
            _add_override(entry, hashes)
            continue

        pw_entry = pw_index.get(entry.relative_to_minecraft)
        pw_data = pw_entry.data if pw_entry else None

        if pw_data:
            if _pw_toml_is_curseforge_only(pw_data):
                if is_mod_jar:
                    unresolved_mods.append(entry.relative_to_minecraft)
                _add_override(entry, hashes)
                continue
            meta = _pw_toml_modrinth_entry(pw_data)
            if meta is not None:
                cdn_url, pw_sha512 = meta
                if pw_sha512.lower() == hashes.sha512.lower():
                    mrpack_entries.append(
                        MrpackFileEntry(
                            path=entry.relative_to_minecraft,
                            sha1=hashes.sha1,
                            sha512=hashes.sha512,
                            file_size=hashes.size,
                            downloads=[_normalize_cdn_url(cdn_url)],
                        )
                    )
                    continue
                pw_mismatches.append(entry.relative_to_minecraft)
                log.warning(
                    "SHA512 mismatch between %s on disk and its .pw.toml "
                    "metadata; falling back to Modrinth hash lookup.",
                    entry.relative_to_minecraft,
                )

        # No usable .pw.toml. Mods get a hash-lookup fallback; everything else
        # (configs, resourcepacks/shaders without metadata, etc.) goes to overrides.
        if is_mod_jar:
            pending_hash_lookup.append((entry, hashes))
        else:
            _add_override(entry, hashes)

    if pending_hash_lookup:
        sha1_to_pending = {h.sha1: (entry, h) for entry, h in pending_hash_lookup}
        log.info(
            "Querying Modrinth for %d unresolved mod(s)...", len(sha1_to_pending)
        )
        lookup = _modrinth_hash_lookup(
            list(sha1_to_pending.keys()), config.user_agent
        )
        for sha1, (entry, hashes) in sha1_to_pending.items():
            version_obj = lookup.get(sha1)
            resolved = (
                _resolve_version_obj_for_sha1(version_obj, sha1)
                if version_obj
                else None
            )
            if resolved is not None:
                cdn_url, sha512, size = resolved
                if sha512.lower() != hashes.sha512.lower() or size != hashes.size:
                    log.warning(
                        "Modrinth-reported file for %s does not match local "
                        "hash/size; using local values.",
                        entry.relative_to_minecraft,
                    )
                mrpack_entries.append(
                    MrpackFileEntry(
                        path=entry.relative_to_minecraft,
                        sha1=hashes.sha1,
                        sha512=hashes.sha512,
                        file_size=hashes.size,
                        downloads=[_normalize_cdn_url(cdn_url)],
                    )
                )
            else:
                unresolved_mods.append(entry.relative_to_minecraft)
                _add_override(entry, hashes)

    _write_mrpack(
        output_path=output_path,
        instance=instance,
        config=config,
        version_id=_format_version_id(version_id),
        mrpack_entries=mrpack_entries,
        overrides=overrides,
    )

    return BuildResult(
        output_path=output_path,
        instance=instance,
        file_entries=mrpack_entries,
        overrides=overrides,
        unresolved_mods=unresolved_mods,
        pw_toml_mismatches=pw_mismatches,
        optional_files=optional_paths,
    )


def _build_index_json(
    instance: InstanceInfo,
    config: Config,
    version_id: str,
    mrpack_entries: Iterable[MrpackFileEntry],
) -> dict:
    dependencies: dict[str, str] = {
        "minecraft": instance.minecraft_version,
        instance.loader.mrpack_key: instance.loader.version,
    }
    files_sorted = sorted(mrpack_entries, key=lambda e: e.path)
    index: dict = {
        "formatVersion": 1,
        "game": "minecraft",
        "versionId": version_id,
        "name": config.name,
        "files": [e.to_json() for e in files_sorted],
        "dependencies": dependencies,
    }
    if config.summary:
        index["summary"] = config.summary
    return index


def _write_mrpack(
    output_path: Path,
    instance: InstanceInfo,
    config: Config,
    version_id: str,
    mrpack_entries: list[MrpackFileEntry],
    overrides: list[OverrideEntry],
) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    index = _build_index_json(instance, config, version_id, mrpack_entries)
    index_bytes = json.dumps(index, indent=2, ensure_ascii=False).encode("utf-8")

    with zipfile.ZipFile(
        output_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6
    ) as zf:
        # modrinth.index.json must be at the zip root.
        info = zipfile.ZipInfo("modrinth.index.json")
        info.compress_type = zipfile.ZIP_DEFLATED
        zf.writestr(info, index_bytes)
        for override in sorted(overrides, key=lambda o: o.arcname):
            zf.write(override.source_path, arcname=override.arcname)


def fingerprint_from_mrpack(mrpack_path: Path) -> dict[str, str]:
    """Build a {pack_path: sha512} fingerprint from an existing .mrpack file.

    For CDN-listed files, the sha512 comes straight from modrinth.index.json.
    For overrides/ entries, we read the bytes from the zip and hash them.
    """
    fp: dict[str, str] = {}
    with zipfile.ZipFile(mrpack_path) as zf:
        with zf.open("modrinth.index.json") as fh:
            index = json.load(io.TextIOWrapper(fh, encoding="utf-8"))
        for entry in index.get("files", []):
            path = entry.get("path")
            sha512 = (entry.get("hashes") or {}).get("sha512")
            if path and sha512:
                fp[path] = sha512
        for member in zf.namelist():
            if not member.startswith("overrides/") or member.endswith("/"):
                continue
            pack_path = member[len("overrides/") :]
            digest = hashlib.sha512()
            with zf.open(member) as mfh:
                while True:
                    chunk = mfh.read(1024 * 1024)
                    if not chunk:
                        break
                    digest.update(chunk)
            fp[pack_path] = digest.hexdigest()
    return dict(sorted(fp.items()))
