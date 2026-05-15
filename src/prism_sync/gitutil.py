from __future__ import annotations

import logging
import os
import shlex
import subprocess
from pathlib import Path
from typing import Iterable, Sequence


log = logging.getLogger(__name__)

GIT_CMD_ENV_VAR = "PRISM_SYNC_GIT_CMD"


class GitError(RuntimeError):
    pass


def _git_cmd() -> list[str]:
    """The base git invocation, overridable via `PRISM_SYNC_GIT_CMD`.

    Set to e.g. `wsl git` on Windows to route through WSL's git, which is
    handy when identity + SSH are configured there but not on Windows.
    """
    raw = os.environ.get(GIT_CMD_ENV_VAR, "git").strip()
    return shlex.split(raw) if raw else ["git"]


def _run(args: Sequence[str], cwd: Path, check: bool = True) -> subprocess.CompletedProcess:
    cmd = _git_cmd() + list(args)
    log.debug("running: %s (cwd=%s)", " ".join(cmd), cwd)
    result = subprocess.run(
        cmd,
        cwd=str(cwd),
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    if check and result.returncode != 0:
        raise GitError(
            f"{' '.join(cmd)} failed (exit {result.returncode}):\n"
            f"{result.stderr.strip() or result.stdout.strip()}"
        )
    return result


def is_git_repo(cwd: Path) -> bool:
    result = _run(["rev-parse", "--is-inside-work-tree"], cwd=cwd, check=False)
    return result.returncode == 0 and result.stdout.strip() == "true"


def has_remote(cwd: Path, name: str = "origin") -> bool:
    result = _run(["remote", "get-url", name], cwd=cwd, check=False)
    return result.returncode == 0


def has_staged_or_unstaged_changes_for(cwd: Path, paths: Iterable[str]) -> bool:
    """True if any of the given paths has staged or unstaged differences."""
    result = _run(["status", "--porcelain", "--", *paths], cwd=cwd)
    return bool(result.stdout.strip())


def stage(cwd: Path, paths: Iterable[str]) -> None:
    paths = [p for p in paths if (cwd / p).exists()]
    if not paths:
        return
    _run(["add", "--", *paths], cwd=cwd)


def commit(cwd: Path, message: str) -> None:
    _run(["commit", "-m", message], cwd=cwd)


def push(cwd: Path) -> None:
    _run(["push"], cwd=cwd)


def current_branch(cwd: Path) -> str:
    result = _run(["rev-parse", "--abbrev-ref", "HEAD"], cwd=cwd)
    return result.stdout.strip()
