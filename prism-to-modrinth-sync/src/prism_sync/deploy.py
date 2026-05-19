"""Materialize the published server pack locally, then SFTP-push to BloomHost.

The BloomHost startup command can't be customized, so we can't run
``packwiz-installer-bootstrap`` on the server itself. Instead we do the
resolution locally:

1. Walk ``docs/packwiz-server/index.toml``.
2. For each ``metafile=true`` entry, read the referenced ``.pw.toml``, pull
   its ``[download].url`` (Modrinth CDN), and download the jar into
   ``.cache/server-resolved/mods/<filename>``.
3. For each non-metafile entry (self-hosted jars), copy from
   ``docs/packwiz-server/mods/<filename>`` straight through.
4. SFTP-walk BloomHost's ``/mods/`` and compute a diff:
   adds, replaces, removes.
5. Print the plan. If the caller passed ``--yes`` (or it's interactively
   confirmed), apply: upload adds + replaces, delete removes.

Config dirs are NEVER touched. That's the user's domain; we only manage mods.
"""

from __future__ import annotations

import logging
import shutil
import tomllib
from dataclasses import dataclass, field
from pathlib import Path

import requests

from .hashing import hash_file
from .packwiz_settings import PackwizServerSettings, SftpDeploy
from .sftp import open_sftp, sftp_walk, upload_file


log = logging.getLogger(__name__)


class DeployError(RuntimeError):
    """Raised for user-facing errors during deploy."""


@dataclass
class ResolvedFile:
    """A jar ready to ship: a real file on disk with known sha512."""

    pack_rel: str         # e.g. "mods/create.jar" (POSIX, relative to pack root)
    local_path: Path      # absolute path under .cache/server-resolved/
    sha512: str
    size: int


@dataclass
class DeployPlan:
    """The set of SFTP operations needed to make remote match local."""

    adds: list[ResolvedFile] = field(default_factory=list)        # new on remote
    replaces: list[ResolvedFile] = field(default_factory=list)    # exists, bytes differ
    removes: list[str] = field(default_factory=list)              # remote-only paths
    unchanged: int = 0

    @property
    def is_noop(self) -> bool:
        return not (self.adds or self.replaces or self.removes)

    def summary_line(self) -> str:
        return (
            f"+{len(self.adds)} upload, "
            f"~{len(self.replaces)} replace, "
            f"-{len(self.removes)} delete "
            f"({self.unchanged} unchanged)"
        )


# ---------------------------------------------------------------------------
# Resolve: materialize the published tree into actual jars on disk
# ---------------------------------------------------------------------------


def resolve_pack(
    pack_dir: Path,
    cache_dir: Path,
    *,
    user_agent: str,
) -> dict[str, ResolvedFile]:
    """Materialize ``docs/packwiz-server/mods/`` into ``cache_dir/mods/``.

    Returns ``{pack_rel: ResolvedFile}`` ready for upload. Pack-relative
    paths use POSIX separators. The cache dir gets wiped at the start so
    stale resolutions never linger.
    """
    index_path = pack_dir / "index.toml"
    if not index_path.is_file():
        raise DeployError(
            f"Pack index not found at {index_path}. Run "
            "`prism_sync server-build` (or server-publish) first."
        )
    index = tomllib.loads(index_path.read_text(encoding="utf-8"))
    entries = index.get("files") or []

    out_mods_dir = cache_dir / "mods"
    if out_mods_dir.exists():
        shutil.rmtree(out_mods_dir)
    out_mods_dir.mkdir(parents=True, exist_ok=True)

    resolved: dict[str, ResolvedFile] = {}
    session = requests.Session()
    session.headers["User-Agent"] = user_agent or "prism-to-modrinth-sync/0.1.0"

    for entry in entries:
        rel = str(entry.get("file") or "")
        if not rel.startswith("mods/"):
            # Only mods get shipped via SFTP; configs are user's domain.
            continue
        if entry.get("metafile"):
            resolved_file = _resolve_metafile(
                pack_dir / rel, out_mods_dir, session=session
            )
        else:
            resolved_file = _resolve_direct(pack_dir / rel, out_mods_dir)
        if resolved_file is not None:
            resolved[resolved_file.pack_rel] = resolved_file

    return resolved


def _resolve_direct(src: Path, out_mods_dir: Path) -> ResolvedFile | None:
    """Copy a self-hosted jar from the published tree into the cache."""
    if not src.is_file():
        log.warning("Direct file in index but missing on disk: %s", src)
        return None
    dest = out_mods_dir / src.name
    shutil.copy2(src, dest)
    hashes = hash_file(dest)
    return ResolvedFile(
        pack_rel=f"mods/{src.name}",
        local_path=dest,
        sha512=hashes.sha512,
        size=dest.stat().st_size,
    )


def _resolve_metafile(
    metafile_path: Path,
    out_mods_dir: Path,
    *,
    session: requests.Session,
) -> ResolvedFile | None:
    """Read a .pw.toml's download URL, fetch the jar, write to cache."""
    try:
        data = tomllib.loads(metafile_path.read_text(encoding="utf-8"))
    except (tomllib.TOMLDecodeError, OSError) as exc:
        log.warning("Could not parse %s: %s", metafile_path, exc)
        return None
    filename = str(data.get("filename") or "")
    download = data.get("download") or {}
    url = str(download.get("url") or "")
    expected_sha512 = str(download.get("hash") or "").lower()
    hash_format = str(download.get("hash-format") or "").lower()
    if not (filename and url):
        log.warning(
            "%s missing filename or download.url; can't materialize",
            metafile_path.name,
        )
        return None

    dest = out_mods_dir / filename
    log.info("Fetching %s ...", filename)
    resp = session.get(url, stream=True, timeout=60)
    resp.raise_for_status()
    with dest.open("wb") as fh:
        for chunk in resp.iter_content(chunk_size=65536):
            if chunk:
                fh.write(chunk)
    hashes = hash_file(dest)

    if hash_format == "sha512" and expected_sha512 and hashes.sha512.lower() != expected_sha512:
        log.warning(
            "%s: downloaded sha512 doesn't match metafile (%s vs %s)",
            filename, hashes.sha512[:12], expected_sha512[:12],
        )
    return ResolvedFile(
        pack_rel=f"mods/{filename}",
        local_path=dest,
        sha512=hashes.sha512,
        size=dest.stat().st_size,
    )


# ---------------------------------------------------------------------------
# Diff: compare resolved local set to remote BloomHost set
# ---------------------------------------------------------------------------


def plan_deploy(
    resolved: dict[str, ResolvedFile],
    remote_listing: dict[str, int],
) -> DeployPlan:
    """Build a DeployPlan from local-resolved vs remote-listing.

    ``remote_listing`` is ``{rel_path: size_bytes}`` — size is a cheap
    "did anything change" check that avoids downloading every remote file
    just to hash it. Filename changes also flip size in practice (mod
    versions baked into filenames). For paranoia we still upload on size
    mismatch even if the filename matches.
    """
    plan = DeployPlan()
    remote_paths = set(remote_listing.keys())
    for pack_rel, rf in resolved.items():
        if pack_rel not in remote_paths:
            plan.adds.append(rf)
        elif remote_listing[pack_rel] != rf.size:
            plan.replaces.append(rf)
        else:
            plan.unchanged += 1
    for remote_path in remote_paths:
        if remote_path not in resolved:
            plan.removes.append(remote_path)
    plan.adds.sort(key=lambda r: r.pack_rel)
    plan.replaces.sort(key=lambda r: r.pack_rel)
    plan.removes.sort()
    return plan


def print_plan(plan: DeployPlan) -> None:
    print(f"Deploy plan: {plan.summary_line()}")
    if plan.is_noop:
        print("  (no changes)")
        return
    for rf in plan.adds:
        print(f"  + {rf.pack_rel}  ({rf.size} bytes)")
    for rf in plan.replaces:
        print(f"  ~ {rf.pack_rel}  ({rf.size} bytes)")
    for path in plan.removes:
        print(f"  - {path}")


# ---------------------------------------------------------------------------
# Execute: SFTP the plan
# ---------------------------------------------------------------------------


def execute_plan(
    plan: DeployPlan,
    deploy: SftpDeploy,
    *,
    remote_subdir: str = "mods",
) -> None:
    """Upload adds + replaces, delete removes. Uses one SFTP session."""
    if plan.is_noop:
        return

    with open_sftp(deploy) as sftp:
        for rf in plan.adds + plan.replaces:
            # rf.pack_rel is "mods/foo.jar"; remote target lives at /mods/foo.jar
            # under the chroot. The deploy.remote_dir is the chroot root ("/").
            remote_path = _remote_path(deploy, rf.pack_rel)
            log.info("upload: %s -> %s", rf.local_path.name, remote_path)
            upload_file(sftp, rf.local_path, remote_path)
        for rel in plan.removes:
            remote_path = _remote_path(deploy, rel)
            log.info("delete: %s", remote_path)
            try:
                sftp.remove(remote_path)
            except FileNotFoundError:
                log.warning("Already gone, skipping delete: %s", remote_path)


def _remote_path(deploy: SftpDeploy, pack_rel: str) -> str:
    """Resolve a pack-relative path against the SFTP remote_dir."""
    import posixpath  # local import to avoid module-level clutter

    rel = pack_rel.replace("\\", "/").lstrip("/")
    return posixpath.join(deploy.remote_dir, rel)


# ---------------------------------------------------------------------------
# Top-level orchestrator
# ---------------------------------------------------------------------------


def run_deploy(
    *,
    server_settings: PackwizServerSettings,
    cache_dir: Path,
    user_agent: str,
    confirm: bool = True,
) -> DeployPlan:
    """End-to-end: resolve, walk remote, plan, prompt (if confirm), execute.

    Returns the executed (or skipped) plan so the caller can print a
    summary. ``confirm=False`` skips the y/N prompt (for batch/bat use).
    """
    deploy = server_settings.deploy
    if not deploy.configured:
        raise DeployError(
            "SFTP not configured. Set host + user in [packwiz_server.deploy] "
            "and password_env (or key_path) in config.local.toml."
        )

    print(f"Resolving {server_settings.output_dir} into {cache_dir}...")
    resolved = resolve_pack(
        server_settings.output_dir, cache_dir, user_agent=user_agent
    )
    print(f"  resolved {len(resolved)} mod jar(s)")

    print(f"Listing remote /mods/ on {deploy.host}:{deploy.port}...")
    with open_sftp(deploy) as sftp:
        remote_listing = sftp_walk(
            sftp, _remote_path(deploy, "mods"), "mods"
        )
    print(f"  {len(remote_listing)} file(s) currently on BloomHost mods/")
    print()

    plan = plan_deploy(resolved, remote_listing)
    print_plan(plan)

    if plan.is_noop:
        return plan

    if confirm:
        print()
        answer = input("Apply this plan? [y/N] ").strip().lower()
        if answer != "y":
            print("Aborted; no SFTP changes made.")
            return plan

    print()
    print("Executing...")
    execute_plan(plan, deploy)
    print(f"Done. Restart the server via the BloomHost panel to apply.")
    return plan
