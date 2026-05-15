from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from pathlib import Path

import requests

from .config import Config
from .instance import InstanceInfo
from .remote import (
    MODRINTH_API_BASE,
    USER_AGENT_FALLBACK,
    list_project_versions,
    resolve_project_id,
)


log = logging.getLogger(__name__)

PRIMARY_FILE_PART = "mrpack-file"


@dataclass
class PublishPayload:
    """The serialized form of a publish request, for dry-run printing."""

    url: str
    headers: dict[str, str]
    data: dict
    file_path: Path
    file_size: int


def build_version_data(
    *,
    project_id: str,
    version_number: str,
    name: str,
    changelog: str,
    minecraft_version: str,
    loader_tag: str,
    version_type: str,
    draft: bool,
    featured: bool,
) -> dict:
    """Build the JSON blob sent as the multipart `data` field."""
    return {
        "name": name,
        "version_number": version_number,
        "changelog": changelog,
        "dependencies": [],
        "game_versions": [minecraft_version],
        "version_type": version_type,
        "loaders": [loader_tag],
        "featured": featured,
        "status": "draft" if draft else "listed",
        "project_id": project_id,
        "file_parts": [PRIMARY_FILE_PART],
        "primary_file": PRIMARY_FILE_PART,
    }


def next_available_version_number(
    project_id: str, base: str, user_agent: str
) -> str:
    """Return `base` if no published version uses it, else `base.N` for the
    lowest free N (starting at 1).
    """
    try:
        versions = list_project_versions(project_id, user_agent)
    except requests.HTTPError as exc:
        if exc.response is not None and exc.response.status_code == 404:
            return base
        raise
    used = {v.get("version_number") for v in versions}
    if base not in used:
        return base
    n = 1
    while f"{base}.{n}" in used:
        n += 1
    return f"{base}.{n}"


def _redact_pat(headers: dict[str, str]) -> dict[str, str]:
    out = dict(headers)
    if "Authorization" in out:
        out["Authorization"] = "<redacted>"
    return out


def make_payload(
    config: Config,
    instance: InstanceInfo,
    mrpack_path: Path,
    *,
    version_number: str,
    changelog: str,
    version_type: str,
    draft: bool,
    featured: bool,
    include_auth: bool,
) -> PublishPayload:
    slug_or_id = config.require_project_id()
    # POST /v2/version requires the canonical base62 id; resolve in case the
    # user put a slug in config. The PAT is also sent on the GET so unlisted
    # projects (which require auth even to read) resolve successfully.
    canonical_id = resolve_project_id(
        slug_or_id, config.user_agent, pat=config.modrinth_pat
    )
    headers: dict[str, str] = {
        "User-Agent": config.user_agent or USER_AGENT_FALLBACK,
    }
    if include_auth:
        headers["Authorization"] = config.require_pat()
    data = build_version_data(
        project_id=canonical_id,
        version_number=version_number,
        name=version_number,
        changelog=changelog,
        minecraft_version=instance.minecraft_version,
        loader_tag=instance.loader.modrinth_tag,
        version_type=version_type,
        draft=draft,
        featured=featured,
    )
    return PublishPayload(
        url=f"{MODRINTH_API_BASE}/version",
        headers=headers,
        data=data,
        file_path=mrpack_path,
        file_size=mrpack_path.stat().st_size,
    )


def post_version(payload: PublishPayload) -> dict:
    with payload.file_path.open("rb") as fh:
        files = {
            "data": (None, json.dumps(payload.data), "application/json"),
            PRIMARY_FILE_PART: (
                payload.file_path.name,
                fh,
                "application/octet-stream",
            ),
        }
        resp = requests.post(
            payload.url,
            headers=payload.headers,
            files=files,
            timeout=300,
        )
    resp.raise_for_status()
    return resp.json()


def format_dry_run(payload: PublishPayload) -> str:
    """Human-readable representation of the request that would be sent."""
    redacted_headers = _redact_pat(payload.headers)
    lines = [
        f"POST {payload.url}",
        "Headers:",
    ]
    for k, v in redacted_headers.items():
        lines.append(f"  {k}: {v}")
    lines.append("Multipart fields:")
    lines.append("  data (application/json):")
    pretty = json.dumps(payload.data, indent=2, ensure_ascii=False)
    for line in pretty.splitlines():
        lines.append(f"    {line}")
    lines.append(
        f"  {PRIMARY_FILE_PART} (application/octet-stream): "
        f"{payload.file_path.name} ({payload.file_size:,} bytes)"
    )
    return "\n".join(lines)
