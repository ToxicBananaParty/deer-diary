from __future__ import annotations

import logging
import re
import shutil
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Iterator

import requests


_CANONICAL_ID_RE = re.compile(r"^[A-Za-z0-9]{8}$")


log = logging.getLogger(__name__)

MODRINTH_API_BASE = "https://api.modrinth.com/v2"
USER_AGENT_FALLBACK = "prism-to-modrinth-sync/0.1.0"


@dataclass
class RemoteVersion:
    id: str
    version_number: str
    name: str
    primary_file_url: str
    primary_file_filename: str
    primary_file_sha512: str | None


def _headers(user_agent: str, pat: str | None = None) -> dict[str, str]:
    headers = {"User-Agent": user_agent or USER_AGENT_FALLBACK}
    if pat:
        headers["Authorization"] = pat
    return headers


def list_project_versions(project_id: str, user_agent: str) -> list[dict]:
    resp = requests.get(
        f"{MODRINTH_API_BASE}/project/{project_id}/version",
        headers=_headers(user_agent),
        timeout=30,
    )
    resp.raise_for_status()
    return resp.json()


def resolve_project_id(
    slug_or_id: str, user_agent: str, pat: str | None = None
) -> str:
    """Look up a project by slug or id and return its canonical base62 id.

    `POST /v2/version` requires the base62 id; slugs only work on GET paths.
    Inputs that already match the 8-char base62 shape are returned as-is, so
    callers can skip the API roundtrip by pasting the id directly into config.
    Unlisted projects require `pat` for the GET to succeed.
    """
    if _CANONICAL_ID_RE.match(slug_or_id):
        return slug_or_id
    resp = requests.get(
        f"{MODRINTH_API_BASE}/project/{slug_or_id}",
        headers=_headers(user_agent, pat),
        timeout=30,
    )
    resp.raise_for_status()
    data = resp.json()
    canonical = data.get("id")
    if not canonical:
        raise RuntimeError(
            f"Modrinth returned no id for project {slug_or_id!r}."
        )
    return canonical


def _select_primary_file(version_obj: dict) -> dict | None:
    files = version_obj.get("files") or []
    if not files:
        return None
    for f in files:
        if f.get("primary"):
            return f
    return files[0]


def latest_version(project_id: str, user_agent: str) -> RemoteVersion | None:
    versions = list_project_versions(project_id, user_agent)
    if not versions:
        return None
    top = versions[0]
    primary = _select_primary_file(top)
    if not primary:
        return None
    return RemoteVersion(
        id=top.get("id", ""),
        version_number=top.get("version_number", ""),
        name=top.get("name", ""),
        primary_file_url=primary.get("url", ""),
        primary_file_filename=primary.get("filename", ""),
        primary_file_sha512=(primary.get("hashes") or {}).get("sha512"),
    )


def download_mrpack(version: RemoteVersion, user_agent: str, dest: Path) -> Path:
    dest.parent.mkdir(parents=True, exist_ok=True)
    with requests.get(
        version.primary_file_url,
        headers=_headers(user_agent),
        stream=True,
        timeout=120,
    ) as resp:
        resp.raise_for_status()
        with dest.open("wb") as fh:
            for chunk in resp.iter_content(chunk_size=1024 * 1024):
                if chunk:
                    fh.write(chunk)
    return dest
