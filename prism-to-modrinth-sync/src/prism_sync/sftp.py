"""Thin paramiko wrapper for the BloomHost (or any SFTP-accessible) server.

Used by two CLI commands:

* ``server-bootstrap-from-sftp`` — recursively download the remote
  ``mods/``, ``config/``, ``defaultconfigs/`` into ``Custom Mods/server/``.
* ``server-deploy-to-bloomhost`` — resolve the published server pack
  locally, then SFTP-sync the resolved ``mods/`` into BloomHost's
  ``/mods/``. See :mod:`deploy` for the orchestrator.

Authentication: prefer key-file when ``SftpDeploy.key_path`` is set,
otherwise read the password from the env var named by ``password_env``.
"""

from __future__ import annotations

import fnmatch
import logging
import os
import posixpath
import stat
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import Iterator

import paramiko

from .packwiz_settings import SftpDeploy


log = logging.getLogger(__name__)


class SftpError(RuntimeError):
    """Raised for user-facing SFTP misconfiguration or transfer errors."""


@dataclass
class TransferStats:
    files: int = 0
    bytes: int = 0
    skipped: int = 0  # remote paths that didn't exist (logged, not fatal)
    denied: list[str] = None  # type: ignore[assignment]  # filenames blocked by deny-list

    def __post_init__(self) -> None:
        if self.denied is None:
            self.denied = []

    def add(self, byte_count: int) -> None:
        self.files += 1
        self.bytes += byte_count

    @property
    def megabytes(self) -> float:
        return self.bytes / (1024 * 1024)


class DenyMatcher:
    """fnmatch-based blocklist for SFTP file paths.

    A path is denied if any pattern matches either:
      - the file's POSIX path relative to the current download root, or
      - the file's basename (so `Discord-Integration.toml` catches the
        file no matter how deep it's nested).

    Patterns support shell glob syntax via :mod:`fnmatch` (``*``, ``?``,
    ``[seq]``). Empty pattern list = no denies.
    """

    def __init__(self, patterns: list[str]) -> None:
        self._patterns = [p.replace("\\", "/").lstrip("/") for p in patterns if p]

    @property
    def has_patterns(self) -> bool:
        return bool(self._patterns)

    def matches(self, posix_rel_path: str) -> bool:
        if not self._patterns:
            return False
        rel = posix_rel_path.replace("\\", "/").lstrip("/")
        basename = posixpath.basename(rel)
        for pattern in self._patterns:
            if fnmatch.fnmatchcase(rel, pattern):
                return True
            if fnmatch.fnmatchcase(basename, pattern):
                return True
        return False


def _resolve_remote(deploy: SftpDeploy, rel: str) -> str:
    """Join ``rel`` onto ``deploy.remote_dir`` using POSIX semantics.

    SFTP paths are always forward-slash, so we use ``posixpath`` rather than
    ``os.path`` to avoid Windows-flavored joins sneaking in.
    """
    rel = rel.replace("\\", "/").lstrip("/")
    return posixpath.join(deploy.remote_dir, rel)


@contextmanager
def open_sftp(deploy: SftpDeploy) -> Iterator[paramiko.SFTPClient]:
    """Connect to the configured SFTP target, yield an SFTPClient.

    Auto-disconnects on exit. Raises :class:`SftpError` for missing
    configuration or auth failures so the CLI prints a user-friendly message
    instead of a paramiko traceback.
    """
    if not deploy.configured:
        raise SftpError(
            "SFTP not configured. Set host + user in "
            "[packwiz_server.deploy] (and password_env or key_path in "
            "config.local.toml)."
        )

    password = deploy.resolve_password()
    if deploy.key_path is None and password is None:
        raise SftpError(
            "No SFTP credentials available. Either set "
            f"[packwiz_server.deploy].key_path or export the env var named by "
            f"password_env (currently {deploy.password_env!r})."
        )

    transport = paramiko.Transport((deploy.host, deploy.port))
    try:
        if deploy.key_path is not None:
            pkey = paramiko.RSAKey.from_private_key_file(str(deploy.key_path))
            transport.connect(username=deploy.user, pkey=pkey)
        else:
            transport.connect(username=deploy.user, password=password)
        sftp = paramiko.SFTPClient.from_transport(transport)
        if sftp is None:
            raise SftpError("Failed to open SFTP channel after auth succeeded.")
        try:
            yield sftp
        finally:
            sftp.close()
    except paramiko.AuthenticationException as exc:
        raise SftpError(f"SFTP authentication failed for {deploy.user}@{deploy.host}: {exc}")
    except paramiko.SSHException as exc:
        raise SftpError(f"SSH error connecting to {deploy.host}: {exc}")
    finally:
        transport.close()


# ---------------------------------------------------------------------------
# Recursive download (bootstrap)
# ---------------------------------------------------------------------------


def download_tree(
    sftp: paramiko.SFTPClient,
    remote_root: str,
    local_root: Path,
    *,
    skip_names: frozenset[str] = frozenset(),
    deny_matcher: DenyMatcher | None = None,
    _rel_prefix: str = "",
) -> TransferStats:
    """Mirror a remote directory tree into ``local_root``.

    Creates ``local_root`` if absent. Re-downloads files unconditionally
    (the bootstrap is a one-time-ish operation; we don't bother with
    timestamp/hash comparisons). ``skip_names`` is matched against the
    *basename* of each entry — useful for ignoring `logs/`, `crash-reports/`
    etc. while crawling a parent. ``deny_matcher`` blocks specific files
    by glob pattern (used for known-sensitive paths like Discord-Integration.toml).

    Returns transfer stats. Raises :class:`SftpError` if the remote root
    doesn't exist or isn't a directory. ``_rel_prefix`` is internal; the
    public caller passes "" so paths in deny-match are relative to the
    initial root.
    """
    stats = TransferStats()
    try:
        st = sftp.stat(remote_root)
    except FileNotFoundError:
        log.warning("Remote path does not exist, skipping: %s", remote_root)
        stats.skipped += 1
        return stats

    if not stat.S_ISDIR(st.st_mode or 0):
        # Single-file case — copy directly into local_root as the same name.
        basename = posixpath.basename(remote_root)
        rel = posixpath.join(_rel_prefix, basename) if _rel_prefix else basename
        if deny_matcher is not None and deny_matcher.matches(rel):
            log.info("Denied (matches deny-list): %s", rel)
            stats.denied.append(rel)
            return stats
        local_root.mkdir(parents=True, exist_ok=True)
        dest = local_root / basename
        sftp.get(remote_root, str(dest))
        stats.add(dest.stat().st_size)
        return stats

    local_root.mkdir(parents=True, exist_ok=True)
    for attr in sftp.listdir_attr(remote_root):
        name = attr.filename
        if name in skip_names or name.startswith("."):
            continue
        remote_path = posixpath.join(remote_root, name)
        local_path = local_root / name
        rel = posixpath.join(_rel_prefix, name) if _rel_prefix else name
        if stat.S_ISDIR(attr.st_mode or 0):
            sub_stats = download_tree(
                sftp,
                remote_path,
                local_path,
                skip_names=skip_names,
                deny_matcher=deny_matcher,
                _rel_prefix=rel,
            )
            stats.files += sub_stats.files
            stats.bytes += sub_stats.bytes
            stats.skipped += sub_stats.skipped
            stats.denied.extend(sub_stats.denied)
        else:
            if deny_matcher is not None and deny_matcher.matches(rel):
                log.info("Denied (matches deny-list): %s", rel)
                stats.denied.append(rel)
                continue
            local_path.parent.mkdir(parents=True, exist_ok=True)
            sftp.get(remote_path, str(local_path))
            stats.add(attr.st_size or 0)
    return stats


# ---------------------------------------------------------------------------
# Single-file upload (deploy-wrapper, approve)
# ---------------------------------------------------------------------------


def upload_file(
    sftp: paramiko.SFTPClient,
    local_path: Path,
    remote_path: str,
) -> int:
    """Upload one local file to a remote path. Creates parent dirs as needed.

    Returns the byte count uploaded. Raises :class:`SftpError` on I/O error.
    """
    if not local_path.is_file():
        raise SftpError(f"Local file does not exist: {local_path}")

    _ensure_remote_dir(sftp, posixpath.dirname(remote_path))
    try:
        sftp.put(str(local_path), remote_path)
    except OSError as exc:
        raise SftpError(f"Failed to upload {local_path} -> {remote_path}: {exc}")
    return local_path.stat().st_size


def upload_bytes(
    sftp: paramiko.SFTPClient,
    data: bytes,
    remote_path: str,
) -> int:
    """Write ``data`` directly to ``remote_path``. Used by `server-approve`."""
    _ensure_remote_dir(sftp, posixpath.dirname(remote_path))
    with sftp.file(remote_path, "wb") as fh:
        fh.write(data)
    return len(data)


def _ensure_remote_dir(sftp: paramiko.SFTPClient, remote_dir: str) -> None:
    """`mkdir -p` for SFTP. No-op if the dir already exists.

    Walks up to find the deepest existing ancestor, then creates each missing
    segment. Cheaper than naive stat-each-segment loops on a slow link.
    """
    if not remote_dir or remote_dir == "/":
        return
    try:
        st = sftp.stat(remote_dir)
        if stat.S_ISDIR(st.st_mode or 0):
            return
        raise SftpError(f"Remote path exists but is not a directory: {remote_dir}")
    except FileNotFoundError:
        pass

    parent = posixpath.dirname(remote_dir)
    if parent and parent != remote_dir:
        _ensure_remote_dir(sftp, parent)
    sftp.mkdir(remote_dir)


# ---------------------------------------------------------------------------
# Flat listing (deploy-side)
# ---------------------------------------------------------------------------


def sftp_walk(
    sftp: paramiko.SFTPClient,
    remote_root: str,
    rel_prefix: str = "",
) -> dict[str, int]:
    """Recursive walk; returns ``{posix_rel_path: size_bytes}``.

    Used by deploy to enumerate what's currently on BloomHost so we can
    diff against the locally-resolved pack. Tolerates a missing root
    (returns empty dict) so a brand-new server (no /mods/ yet) doesn't
    blow up the first deploy.
    """
    out: dict[str, int] = {}
    try:
        entries = sftp.listdir_attr(remote_root)
    except FileNotFoundError:
        return out
    for attr in entries:
        name = attr.filename
        if name.startswith("."):
            continue
        remote = posixpath.join(remote_root, name)
        rel = posixpath.join(rel_prefix, name) if rel_prefix else name
        if stat.S_ISDIR(attr.st_mode or 0):
            out.update(sftp_walk(sftp, remote, rel))
        else:
            out[rel] = attr.st_size or 0
    return out


# ---------------------------------------------------------------------------
# Convenience: top-level bootstrap orchestrator
# ---------------------------------------------------------------------------


# Ephemera that's never useful to mirror (logs, crashes, world state).
DEFAULT_SKIP_NAMES = frozenset(
    {
        "logs",
        "crash-reports",
        "world",
        "world_nether",
        "world_the_end",
        # debug-only directories
        "debug",
        ".cache",
        # Pterodactyl bookkeeping
        ".pterodactyl",
    }
)


def bootstrap_pull(
    deploy: SftpDeploy,
    local_server_dir: Path,
    *,
    skip_names: frozenset[str] | None = None,
    extra_deny_patterns: list[str] | None = None,
) -> dict[str, TransferStats]:
    """Pull every path in ``deploy.bootstrap_pull`` into ``local_server_dir``.

    Returns a dict mapping each relative path -> its per-tree stats so the
    CLI can print a useful summary. Files matching ``deploy.bootstrap_deny_paths``
    (plus any ``extra_deny_patterns``) are skipped — used to prevent secret-
    bearing configs (e.g. Discord-Integration.toml) from being pulled into
    the local mirror.
    """
    skip = skip_names if skip_names is not None else DEFAULT_SKIP_NAMES
    if not deploy.bootstrap_pull:
        raise SftpError(
            "[packwiz_server.deploy].bootstrap_pull is empty. Add at least one "
            "remote path (e.g. \"mods\")."
        )

    patterns = list(deploy.bootstrap_deny_paths)
    if extra_deny_patterns:
        patterns.extend(extra_deny_patterns)
    deny = DenyMatcher(patterns)

    out: dict[str, TransferStats] = {}
    with open_sftp(deploy) as sftp:
        for rel in deploy.bootstrap_pull:
            remote = _resolve_remote(deploy, rel)
            local = local_server_dir / rel
            log.info("Pulling %s -> %s", remote, local)
            out[rel] = download_tree(
                sftp, remote, local, skip_names=skip, deny_matcher=deny
            )
    return out


