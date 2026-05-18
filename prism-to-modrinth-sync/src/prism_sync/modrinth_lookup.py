"""Modrinth API helpers reusable across pipelines.

The client mrpack pipeline already does batch hash-lookup
(`mrpack._modrinth_hash_lookup`) for jars without `.pw.toml` metadata. The
server pipeline needs the same primitive PLUS:

* Given a list of server-only jars, batch-look-them-up and synthesize
  `.pw.toml` metafiles for the matches (``server-attach-metafiles``).
* Given an existing `.pw.toml`, ask "is there a newer compatible version
  available?" and rewrite the metafile if so (``server-refresh-metafiles``).

This module exposes a small, typed surface for both. It does NOT replace
the inline functions in `mrpack.py`; rewriting that code now would risk
regressing the working client pipeline for no benefit. If a future cleanup
wants to share more, the public API here is a stable target to migrate to.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Iterable

import requests


log = logging.getLogger(__name__)


MODRINTH_API_BASE = "https://api.modrinth.com/v2"
HASH_LOOKUP_BATCH_SIZE = 100

# Hosts whose URLs we trust as canonical CDN links. Imported by callers
# that want to gate "is this URL safe to publish".
ALLOWED_DOWNLOAD_HOSTS = ("cdn.modrinth.com",)


@dataclass(frozen=True)
class ModrinthFile:
    """One ``files[]`` entry on a Modrinth version response."""

    url: str
    filename: str
    sha1: str
    sha512: str
    size: int
    primary: bool

    @classmethod
    def from_api(cls, raw: dict) -> "ModrinthFile":
        hashes = raw.get("hashes") or {}
        return cls(
            url=str(raw.get("url") or ""),
            filename=str(raw.get("filename") or ""),
            sha1=str(hashes.get("sha1") or ""),
            sha512=str(hashes.get("sha512") or ""),
            size=int(raw.get("size") or 0),
            primary=bool(raw.get("primary", False)),
        )

    @property
    def is_cdn_safe(self) -> bool:
        return any(host in self.url for host in ALLOWED_DOWNLOAD_HOSTS)


@dataclass(frozen=True)
class ModrinthVersion:
    """A subset of Modrinth's ``/v2/version/{id}`` shape."""

    id: str
    project_id: str
    version_number: str
    name: str
    game_versions: list[str]
    loaders: list[str]
    files: list[ModrinthFile]
    date_published: str

    @classmethod
    def from_api(cls, raw: dict) -> "ModrinthVersion":
        return cls(
            id=str(raw.get("id") or ""),
            project_id=str(raw.get("project_id") or ""),
            version_number=str(raw.get("version_number") or ""),
            name=str(raw.get("name") or ""),
            game_versions=list(raw.get("game_versions") or []),
            loaders=list(raw.get("loaders") or []),
            files=[ModrinthFile.from_api(f) for f in raw.get("files") or []],
            date_published=str(raw.get("date_published") or ""),
        )

    def primary_file(self) -> ModrinthFile | None:
        """Return the version's primary file, or the first CDN-safe one."""
        for f in self.files:
            if f.primary and f.is_cdn_safe:
                return f
        # Some older Modrinth projects don't flag a primary; fall back to the
        # first CDN-safe file (typically the one .jar in the version).
        for f in self.files:
            if f.is_cdn_safe:
                return f
        return None

    def file_by_sha1(self, sha1: str) -> ModrinthFile | None:
        for f in self.files:
            if f.sha1.lower() == sha1.lower():
                return f
        return None


# ---------------------------------------------------------------------------
# HTTP helpers
# ---------------------------------------------------------------------------


def _ua_headers(user_agent: str) -> dict[str, str]:
    return {
        "User-Agent": user_agent or "prism-to-modrinth-sync/0.1.0",
        "Content-Type": "application/json",
    }


def lookup_by_sha1(
    sha1_hashes: Iterable[str],
    user_agent: str,
) -> dict[str, ModrinthVersion]:
    """Batch hash-lookup. Returns ``{sha1_lowercase: ModrinthVersion}``.

    Hashes that didn't match any Modrinth file are absent from the result.
    Batches at :data:`HASH_LOOKUP_BATCH_SIZE` to stay under Modrinth's
    per-request limit.
    """
    hashes = [h.lower() for h in sha1_hashes if h]
    if not hashes:
        return {}

    out: dict[str, ModrinthVersion] = {}
    for i in range(0, len(hashes), HASH_LOOKUP_BATCH_SIZE):
        batch = hashes[i : i + HASH_LOOKUP_BATCH_SIZE]
        resp = requests.post(
            f"{MODRINTH_API_BASE}/version_files",
            headers=_ua_headers(user_agent),
            json={"hashes": batch, "algorithm": "sha1"},
            timeout=30,
        )
        resp.raise_for_status()
        raw_map: dict = resp.json()
        for sha1, version_raw in raw_map.items():
            if not isinstance(version_raw, dict):
                continue
            out[sha1.lower()] = ModrinthVersion.from_api(version_raw)
    return out


def list_project_versions(
    project_id: str,
    *,
    user_agent: str,
    game_versions: Iterable[str] | None = None,
    loaders: Iterable[str] | None = None,
) -> list[ModrinthVersion]:
    """List a project's versions, optionally filtered by MC + loader.

    Modrinth supports filtering via query params; we pass them through so
    the server doesn't transfer 200 versions of LuckPerms just to find the
    one that targets NeoForge 1.21.1.
    """
    params: list[tuple[str, str]] = []
    if game_versions:
        params.append(("game_versions", _json_list(game_versions)))
    if loaders:
        params.append(("loaders", _json_list(loaders)))
    resp = requests.get(
        f"{MODRINTH_API_BASE}/project/{project_id}/version",
        headers=_ua_headers(user_agent),
        params=params,
        timeout=30,
    )
    resp.raise_for_status()
    return [ModrinthVersion.from_api(v) for v in resp.json()]


def latest_compatible_version(
    project_id: str,
    *,
    user_agent: str,
    minecraft_version: str,
    loader: str,
) -> ModrinthVersion | None:
    """Return the newest version compatible with the given MC + loader.

    Modrinth's `/version` endpoint returns the versions in date-descending
    order with the filters applied. So the first item that's actually
    compatible (paranoia check; the API is normally trustworthy here) is
    the answer.
    """
    versions = list_project_versions(
        project_id,
        user_agent=user_agent,
        game_versions=[minecraft_version],
        loaders=[loader],
    )
    for v in versions:
        if minecraft_version in v.game_versions and loader in v.loaders:
            return v
    return None


def _json_list(items: Iterable[str]) -> str:
    """Modrinth wants list-valued query params as a JSON array string."""
    quoted = ",".join(f'"{x}"' for x in items)
    return f"[{quoted}]"


# ---------------------------------------------------------------------------
# .pw.toml synthesis
# ---------------------------------------------------------------------------


def synthesize_pwtoml(
    *,
    project_slug_or_id: str,
    version: ModrinthVersion,
    file: ModrinthFile,
    side: str = "both",
) -> str:
    """Render a Packwiz-format ``.pw.toml`` pointing at a Modrinth CDN file.

    Mirrors what Prism writes for managed Modrinth mods (and what
    ``packwiz refresh`` would emit), minus Prism's ``x-prismlauncher-*``
    extras. Used by ``server-attach-metafiles`` to bolt metadata onto a
    server-only jar whose bytes match a Modrinth release.
    """
    lines = [
        f"filename = {_pw_str(file.filename)}",
        f"name = {_pw_str(version.name or project_slug_or_id)}",
        f"side = {_pw_str(side)}",
        "",
        "[download]",
        f"hash = {_pw_str(file.sha512)}",
        'hash-format = "sha512"',
        f"url = {_pw_str(file.url)}",
        "",
        "[update.modrinth]",
        f"mod-id = {_pw_str(version.project_id)}",
        f"version = {_pw_str(version.id)}",
        "",
    ]
    return "\n".join(lines)


def _pw_str(s: str) -> str:
    """Render a TOML basic string. .pw.toml values never contain quotes/backslashes."""
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'
