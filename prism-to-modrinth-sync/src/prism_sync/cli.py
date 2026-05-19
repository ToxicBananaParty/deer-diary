from __future__ import annotations

import argparse
import datetime as dt
import logging
import sys
import tomllib
from pathlib import Path

import requests

from .changelog import (
    prepend_to_changelog_file,
    render_changelog,
    render_file_entry,
)
from .config import Config, ConfigError, load_config
from .customs import print_summary as print_custom_summary, sync_custom_mods
from .diff import PackDiff, diff_fingerprints
from . import gitutil
from .gitutil import GitError
from .hashing import hash_file
from .instance import InstanceError, read_instance
from .mrpack import BuildResult, build_mrpack, fingerprint_from_mrpack
from .packwiz import (
    PackwizBuildResult,
    PackwizError,
    build_packwiz,
    print_build_summary as print_packwiz_summary,
)
from .jar_meta import collect_mod_ids, read_jar_meta
from .modrinth_lookup import (
    latest_compatible_version,
    lookup_by_sha1,
    synthesize_pwtoml,
)
from .notify import NotifyError, post_discord_changelog
from .publish import format_dry_run, make_payload, next_available_version_number, post_version
from .remote import download_mrpack, latest_version
from .server import (
    ServerBuildError,
    build_server_pack,
    print_build_summary as print_server_summary,
)
from .deploy import DeployError, run_deploy
from .sftp import (
    SftpError,
    bootstrap_pull as sftp_bootstrap_pull,
)
from .state import PublishedState, load_state, save_state, utc_now_iso


log = logging.getLogger(__name__)


def _today_version() -> str:
    return dt.date.today().strftime("%Y.%m.%d")


def _setup_logging(verbose: bool) -> None:
    logging.basicConfig(
        level=logging.DEBUG if verbose else logging.INFO,
        format="%(levelname)s %(name)s: %(message)s",
    )


def _default_output_path(config_dir: Path, name: str, version: str) -> Path:
    safe = name.replace("/", "_").replace("\\", "_").strip() or "modpack"
    return config_dir / "dist" / f"{safe}-{version}.mrpack"


def _run_custom_mod_sync(config: Config) -> None:
    """Refresh any [[custom_mods]] in the instance before building."""
    if not config.custom_mods:
        return
    instance = read_instance(config.instance_path)
    result = sync_custom_mods(config.custom_mods, instance, config.config_dir)
    print_custom_summary(result)
    if result.warnings:
        print()


def _print_build_summary(result: BuildResult) -> None:
    cdn = len(result.file_entries)
    overrides = len(result.overrides)
    total = cdn + overrides
    print(f"Built {result.output_path}")
    print(f"  Pack: {result.instance.instance_path}")
    print(
        f"  Minecraft {result.instance.minecraft_version}, "
        f"{result.instance.loader.mrpack_key} {result.instance.loader.version}"
    )
    print(f"  Files: {total} ({cdn} CDN-referenced, {overrides} bundled in overrides/)")
    if result.optional_files:
        print(
            f"  Optional/disabled files shipped (consumer can toggle on by "
            f"removing the .disabled suffix): {len(result.optional_files)}"
        )
        for path in result.optional_files:
            print(f"    * {path}")
    if result.unresolved_mods:
        print(
            f"  Note: {len(result.unresolved_mods)} mod jar(s) could not be resolved "
            "via Modrinth and were bundled as overrides:"
        )
        for path in result.unresolved_mods:
            print(f"    - {path}")
    if result.pw_toml_mismatches:
        print(
            f"  Warning: {len(result.pw_toml_mismatches)} jar(s) had a SHA512 "
            "mismatch with their .pw.toml metadata (fell back to hash lookup):"
        )
        for path in result.pw_toml_mismatches:
            print(f"    - {path}")


def cmd_build(args: argparse.Namespace) -> int:
    config = load_config(args.config_dir)
    _run_custom_mod_sync(config)
    version = args.version or _today_version()
    out = (
        Path(args.out).resolve()
        if args.out
        else _default_output_path(config.config_dir, config.name, version)
    )
    result = build_mrpack(config, output_path=out, version_id=version)
    _print_build_summary(result)
    return 0


def _bootstrap_state_from_remote(config: Config) -> PublishedState:
    """Download the latest Modrinth version, fingerprint it, persist as state."""
    project_id = config.require_project_id()
    log.info("Fetching latest published version for %s...", project_id)
    version = latest_version(project_id, config.user_agent)
    if version is None:
        raise RuntimeError(
            f"No published versions found for project {project_id!r}. "
            "Make a first publish before using --from-remote."
        )
    dest_dir = config.config_dir / ".cache"
    dest = dest_dir / version.primary_file_filename
    log.info(
        "Downloading %s (%s) to %s",
        version.primary_file_filename,
        version.version_number,
        dest,
    )
    download_mrpack(version, config.user_agent, dest)
    fp = fingerprint_from_mrpack(dest)
    state = PublishedState(
        version_id=version.version_number,
        fingerprint=fp,
        published_at=None,
        notes={"source": "remote-bootstrap", "modrinth_version_id": version.id},
    )
    save_state(config.state_file, state)
    log.info(
        "Wrote bootstrap state to %s (%d files).", config.state_file, len(fp)
    )
    return state


def _print_diff(diff, previous_version: str | None) -> None:
    if diff.is_empty:
        print(
            f"No changes since {previous_version or 'last published state'}."
        )
        return
    print(
        f"Changes since {previous_version or 'last published state'}: "
        f"+{len(diff.added)} ~{len(diff.updated)} -{len(diff.removed)}"
    )
    for kind, items in (("+", diff.added), ("~", diff.updated), ("-", diff.removed)):
        for path in items:
            print(f"  {kind} {path}")


def cmd_check(args: argparse.Namespace) -> int:
    config = load_config(args.config_dir)
    _run_custom_mod_sync(config)
    version = args.version or _today_version()
    out = (
        Path(args.out).resolve()
        if args.out
        else _default_output_path(config.config_dir, config.name, version)
    )

    state = load_state(config.state_file)
    if args.from_remote:
        state = _bootstrap_state_from_remote(config)
    elif state is None:
        print(
            f"No state file at {config.state_file} and --from-remote not "
            "passed; treating every file as new. Use --from-remote to bootstrap "
            "from the latest published version.",
            file=sys.stderr,
        )
        state = PublishedState(version_id="", fingerprint={})

    result = build_mrpack(config, output_path=out, version_id=version)
    _print_build_summary(result)
    diff = diff_fingerprints(state.fingerprint, result.fingerprint)
    print()
    _print_diff(diff, state.version_id)
    if args.changelog and not diff.is_empty:
        print()
        print("--- Changelog ---")
        print(render_changelog(diff, state.version_id, version))
    return 0 if diff.is_empty else 1


def cmd_publish(args: argparse.Namespace) -> int:
    config = load_config(args.config_dir)
    _run_custom_mod_sync(config)
    project_id = config.require_project_id()

    state = load_state(config.state_file)
    if state is None and not args.allow_no_state:
        raise RuntimeError(
            f"No state file at {config.state_file}. Run `sync check --from-remote` "
            "first to bootstrap from the latest published version, or pass "
            "--allow-no-state for an initial publish."
        )

    base_version = args.version or _today_version()
    final_version = base_version
    if not args.version and not args.dry_run:
        # Auto-bump for same-day reissues (date-based versioning).
        final_version = next_available_version_number(
            project_id, base_version, config.user_agent
        )
        if final_version != base_version:
            log.info(
                "Version %s already exists; auto-bumping to %s.",
                base_version,
                final_version,
            )

    out = (
        Path(args.out).resolve()
        if args.out
        else _default_output_path(config.config_dir, config.name, final_version)
    )
    result = build_mrpack(config, output_path=out, version_id=final_version)
    _print_build_summary(result)
    print()

    if state is None:
        diff = None
        previous_version: str | None = None
    else:
        diff = diff_fingerprints(state.fingerprint, result.fingerprint)
        previous_version = state.version_id
        _print_diff(diff, previous_version)
        if diff.is_empty and not args.allow_no_changes:
            print()
            print(
                "No changes since last publish. Pass --allow-no-changes to "
                "publish anyway.",
                file=sys.stderr,
            )
            return 1

    if args.changelog is not None:
        changelog_text = args.changelog
    elif diff is None:
        changelog_text = "Initial release."
    else:
        changelog_text = render_changelog(diff, previous_version, final_version)

    payload = make_payload(
        config,
        result.instance,
        out,
        version_number=final_version,
        changelog=changelog_text,
        version_type=args.type,
        draft=args.draft,
        featured=False,
        include_auth=not args.dry_run,
    )

    print()
    print("--- Publish payload ---")
    print(format_dry_run(payload))

    if args.dry_run:
        print()
        print("Dry run: not posting to Modrinth.")
        return 0

    print()
    print("Posting to Modrinth...")
    response = post_version(payload)
    print(
        f"Published version {response.get('version_number')} "
        f"(id={response.get('id')}, status={response.get('status')})."
    )

    new_state = PublishedState(
        version_id=final_version,
        fingerprint=result.fingerprint,
        published_at=utc_now_iso(),
        notes={
            "modrinth_version_id": response.get("id", ""),
            "modrinth_status": response.get("status", ""),
        },
    )
    save_state(config.state_file, new_state)
    print(f"Updated state file: {config.state_file}")

    if args.push:
        _post_publish_git(
            config_dir=config.config_dir,
            state_file=config.state_file,
            diff=diff,
            previous_version=previous_version,
            new_version=final_version,
            commit_message_override=args.message,
        )

    return 0


# ---------------------------------------------------------------------------
# Packwiz subcommands
# ---------------------------------------------------------------------------


def _require_packwiz_enabled(config: Config) -> None:
    if not config.packwiz.enabled:
        raise PackwizError(
            "Packwiz is not enabled. Add a [packwiz] section to config.toml "
            "with `enabled = true` (see config.example.toml)."
        )


def _print_packwiz_diff(diff: PackDiff, previous_version: str | None) -> None:
    if diff.is_empty:
        print(
            f"No changes since {previous_version or 'last published state'}."
        )
        return
    print(
        f"Changes since {previous_version or 'last published state'}: "
        f"+{len(diff.added)} ~{len(diff.updated)} -{len(diff.removed)}"
    )
    for kind, items in (("+", diff.added), ("~", diff.updated), ("-", diff.removed)):
        for path in items:
            print(f"  {kind} {path}")


def cmd_packwiz_build(args: argparse.Namespace) -> int:
    config = load_config(args.config_dir)
    _require_packwiz_enabled(config)
    version = args.version or _today_version()
    result = build_packwiz(config, config.packwiz, version_id=version)
    print_packwiz_summary(result)
    if config.packwiz.base_url:
        print(f"\nServed at: {config.packwiz.base_url}/pack.toml")
    return 0


def cmd_packwiz_check(args: argparse.Namespace) -> int:
    """Dry-run the Packwiz build and diff it against the shared state file."""
    config = load_config(args.config_dir)
    _require_packwiz_enabled(config)
    version = args.version or _today_version()

    state = load_state(config.state_file)
    if state is None:
        print(
            f"No state file at {config.state_file}; treating every file as new.",
            file=sys.stderr,
        )
        state = PublishedState(version_id="", fingerprint={})

    result = build_packwiz(config, config.packwiz, version_id=version)
    print_packwiz_summary(result)
    diff = diff_fingerprints(state.fingerprint, result.fingerprint)
    print()
    _print_packwiz_diff(diff, state.version_id)
    if args.changelog and not diff.is_empty:
        print()
        print("--- Changelog ---")
        print(render_changelog(diff, state.version_id, version))
    return 0 if diff.is_empty else 1


def cmd_packwiz_publish(args: argparse.Namespace) -> int:
    """Build the Packwiz tree, update state, optionally commit and push."""
    config = load_config(args.config_dir)
    _require_packwiz_enabled(config)
    version = args.version or _today_version()

    state = load_state(config.state_file)
    previous_version = state.version_id if state else None
    result = build_packwiz(config, config.packwiz, version_id=version)
    print_packwiz_summary(result)
    print()

    if state is not None:
        diff = diff_fingerprints(state.fingerprint, result.fingerprint)
        _print_packwiz_diff(diff, previous_version)
        if diff.is_empty and not args.allow_no_changes:
            print(
                "\nNo changes since last publish. Pass --allow-no-changes to "
                "publish anyway.",
                file=sys.stderr,
            )
            return 1
    else:
        diff = None

    new_state = PublishedState(
        version_id=version,
        fingerprint=result.fingerprint,
        published_at=utc_now_iso(),
        notes={"published_via": "packwiz"},
    )
    save_state(config.state_file, new_state)
    print(f"Updated state file: {config.state_file}")

    if args.push:
        _post_publish_packwiz_git(
            config_dir=config.config_dir,
            state_file=config.state_file,
            packwiz_output_dir=result.output_dir,
            diff=diff,
            previous_version=previous_version,
            new_version=version,
            commit_message_override=args.message,
        )

    if config.packwiz.base_url:
        print(f"\nServed at: {config.packwiz.base_url}/pack.toml")
    return 0


def _post_publish_packwiz_git(
    *,
    config_dir: Path,
    state_file: Path,
    packwiz_output_dir: Path,
    diff: PackDiff | None,
    previous_version: str | None,
    new_version: str,
    commit_message_override: str | None,
) -> None:
    """After a successful Packwiz publish, commit the generated tree and push.

    The Packwiz publish path shares the same state file and CHANGELOG.md as
    the Modrinth path, but stages both the state file *and* the generated
    ``docs/packwiz/`` tree under the workspace root (not the
    ``prism-to-modrinth-sync/`` dir).
    """
    # The state file and changelog live in config_dir (the tool's own dir);
    # the Packwiz output lives at the repo root. Find the repo root by
    # walking up until we hit a .git directory.
    repo_root = _find_git_root(config_dir)
    if repo_root is None:
        print(
            f"warning: no .git directory found above {config_dir}; skipping --push.",
            file=sys.stderr,
        )
        return

    changelog_path = config_dir / "CHANGELOG.md"
    fallback = "Initial release." if diff is None else None
    entry = render_file_entry(
        diff or PackDiff(),
        previous_version=previous_version,
        new_version=f"{new_version} (packwiz)",
        fallback_summary=fallback,
    )
    prepend_to_changelog_file(changelog_path, entry)
    print(f"Updated changelog: {changelog_path}")

    # Stage everything that needs to land in the commit:
    # - the generated Packwiz tree (under docs/packwiz)
    # - the shared state file (in prism-to-modrinth-sync/)
    # - the CHANGELOG.md
    tracked_paths = [
        _repo_relative(repo_root, packwiz_output_dir),
        _repo_relative(repo_root, state_file),
        _repo_relative(repo_root, changelog_path),
    ]
    gitutil.stage(repo_root, tracked_paths)

    if not gitutil.has_staged_or_unstaged_changes_for(repo_root, tracked_paths):
        print("Nothing to commit (no tracked files changed).")
        return

    if commit_message_override:
        message = commit_message_override
    elif diff is None or diff.is_empty:
        message = f"Packwiz release {new_version}"
    else:
        message = (
            f"Packwiz release {new_version}: "
            f"+{len(diff.added)} ~{len(diff.updated)} -{len(diff.removed)}"
        )
    gitutil.commit(repo_root, message)
    print(f"Committed: {message}")

    if not gitutil.has_remote(repo_root):
        print(
            "No `origin` remote configured; skipping push.",
            file=sys.stderr,
        )
        return
    try:
        gitutil.push(repo_root)
        print(f"Pushed to {gitutil.current_branch(repo_root)}.")
    except GitError as exc:
        print(f"warning: push failed: {exc}", file=sys.stderr)


def _find_git_root(start: Path) -> Path | None:
    """Walk up from ``start`` until a directory containing ``.git`` is found."""
    current = start.resolve()
    for candidate in (current, *current.parents):
        if (candidate / ".git").exists():
            return candidate
    return None


def _repo_relative(repo_root: Path, target: Path) -> str:
    """Return ``target`` as a POSIX path relative to ``repo_root``.

    Falls back to the absolute path if ``target`` isn't under ``repo_root``
    (shouldn't happen in normal use; the fallback exists so that misconfigured
    output paths surface as a noisy git error rather than a silent skip).
    """
    target = target.resolve()
    try:
        return target.relative_to(repo_root).as_posix()
    except ValueError:
        return str(target)


# ---------------------------------------------------------------------------


def _post_publish_git(
    *,
    config_dir: Path,
    state_file: Path,
    diff: PackDiff | None,
    previous_version: str | None,
    new_version: str,
    commit_message_override: str | None,
) -> None:
    """After a successful publish, append the changelog entry, commit, and push."""
    if not gitutil.is_git_repo(config_dir):
        print(
            f"warning: {config_dir} is not a git repo; skipping --push.",
            file=sys.stderr,
        )
        return

    # Append a dated section to CHANGELOG.md mirroring what was sent to Modrinth.
    changelog_path = config_dir / "CHANGELOG.md"
    fallback = "Initial release." if diff is None else None
    entry = render_file_entry(
        diff or PackDiff(),
        previous_version=previous_version,
        new_version=new_version,
        fallback_summary=fallback,
    )
    prepend_to_changelog_file(changelog_path, entry)
    print(f"Updated changelog: {changelog_path}")

    tracked_paths = [
        "CHANGELOG.md",
        state_file.relative_to(config_dir).as_posix()
        if state_file.is_relative_to(config_dir)
        else str(state_file),
        "config.toml",
    ]
    gitutil.stage(config_dir, tracked_paths)

    if not gitutil.has_staged_or_unstaged_changes_for(config_dir, tracked_paths):
        print("Nothing to commit (no tracked files changed).")
        return

    if commit_message_override:
        message = commit_message_override
    elif diff is None or diff.is_empty:
        message = f"Release {new_version}"
    else:
        message = (
            f"Release {new_version}: "
            f"+{len(diff.added)} ~{len(diff.updated)} -{len(diff.removed)}"
        )
    gitutil.commit(config_dir, message)
    print(f"Committed: {message}")

    if not gitutil.has_remote(config_dir):
        print(
            "No `origin` remote configured; skipping push. Add one with:\n"
            "  git remote add origin <url>",
            file=sys.stderr,
        )
        return

    try:
        gitutil.push(config_dir)
        print(f"Pushed to {gitutil.current_branch(config_dir)}.")
    except GitError as exc:
        print(f"warning: push failed: {exc}", file=sys.stderr)


# ---------------------------------------------------------------------------
# Server-pack subcommands (Phase 0: bootstrap)
# ---------------------------------------------------------------------------


def _require_server_enabled(config: Config) -> None:
    """Most server-* commands need [packwiz_server].enabled = true.

    Exception: server-bootstrap-from-sftp runs even when the pipeline isn't
    flipped on yet (you need the local mirror BEFORE you can configure or
    publish), so it skips this check itself.
    """
    if not config.packwiz_server.enabled:
        raise PackwizError(
            "Server pipeline is not enabled. Set [packwiz_server].enabled = true "
            "in config.toml."
        )


def cmd_server_bootstrap_from_sftp(args: argparse.Namespace) -> int:
    """One-time: pull the current BloomHost server tree into Custom Mods/server/.

    Mirrors every path listed in [packwiz_server.deploy].bootstrap_pull from
    the remote into local subdirectories of [packwiz_server].server_dir. This
    is your starting snapshot — after running, commit Custom Mods/server/ as
    "Server state snapshot YYYY-MM-DD" so you have a rollback anchor.

    Re-running is non-destructive by default (files get overwritten; nothing
    is deleted). Pass --clean to wipe each target subdir first.
    """
    config = load_config(args.config_dir)
    deploy = config.packwiz_server.deploy
    server_dir = config.packwiz_server.server_dir

    if not deploy.configured:
        raise SftpError(
            "SFTP not configured. Set host + user in [packwiz_server.deploy] "
            "and password_env (or key_path) in config.local.toml."
        )
    if not deploy.bootstrap_pull:
        raise SftpError(
            "[packwiz_server.deploy].bootstrap_pull is empty. Add at least one "
            'remote path (e.g. "mods").'
        )

    if args.clean and server_dir.exists():
        import shutil as _shutil

        for rel in deploy.bootstrap_pull:
            target = server_dir / rel
            if target.exists():
                print(f"Cleaning {target}")
                _shutil.rmtree(target)

    server_dir.mkdir(parents=True, exist_ok=True)
    print(f"SFTP {deploy.user}@{deploy.host}:{deploy.remote_dir} -> {server_dir}")
    print(f"Paths: {', '.join(deploy.bootstrap_pull)}")
    print()

    stats_by_rel = sftp_bootstrap_pull(deploy, server_dir)

    total_files = 0
    total_bytes = 0
    total_skipped = 0
    total_denied: list[str] = []
    print()
    print("Pull summary:")
    for rel, stats in stats_by_rel.items():
        marker = "+" if stats.files else ("-" if stats.skipped else ".")
        print(
            f"  {marker} {rel}: {stats.files} files, {stats.megabytes:.1f} MB"
            + (f" (skipped: {stats.skipped})" if stats.skipped else "")
            + (f" (denied: {len(stats.denied)})" if stats.denied else "")
        )
        total_files += stats.files
        total_bytes += stats.bytes
        total_skipped += stats.skipped
        total_denied.extend(f"{rel}/{d}" for d in stats.denied)
    print()
    print(
        f"Total: {total_files} files, {total_bytes / (1024 * 1024):.1f} MB"
        + (f" ({total_skipped} remote path(s) skipped)" if total_skipped else "")
    )
    if total_denied:
        print()
        print(f"Denied by [packwiz_server.deploy].bootstrap_deny_paths:")
        for path in total_denied:
            print(f"  ! {path}")
        print(
            "(These files exist on BloomHost and are preserved across pack "
            "updates by [packwiz_server].preserve_globs; they just won't "
            "live in this repo.)"
        )
    print()
    print("Next steps:")
    print(f"  1. git add \"{server_dir.relative_to(config.config_dir.parent)}\"")
    print('  2. git commit -m "Server state snapshot from BloomHost"')
    print("  3. Flip [packwiz_server].enabled = true in config.toml")
    print("  4. Run `prism_sync server-build` and inspect docs/packwiz-server/")
    return 0


# ---------------------------------------------------------------------------
# Server build / check / publish
# ---------------------------------------------------------------------------


def _print_server_diff(diff: PackDiff, previous_version: str | None) -> None:
    if diff.is_empty:
        print(f"No changes since {previous_version or 'last published state'}.")
        return
    print(
        f"Changes since {previous_version or 'last published state'}: "
        f"+{len(diff.added)} ~{len(diff.updated)} -{len(diff.removed)}"
    )
    for kind, items in (("+", diff.added), ("~", diff.updated), ("-", diff.removed)):
        for path in items:
            print(f"  {kind} {path}")


def cmd_server_build(args: argparse.Namespace) -> int:
    """Build the server pack from Custom Mods/server/ + docs/packwiz/."""
    config = load_config(args.config_dir)
    _require_server_enabled(config)
    version = args.version or _today_version()
    result = build_server_pack(config, version_id=version)
    print_server_summary(result, verbose=args.verbose)
    if config.packwiz_server.base_url:
        print(f"\nServed at: {config.packwiz_server.base_url}/pack.toml")
    return 0


def cmd_server_check(args: argparse.Namespace) -> int:
    """Build the server tree and diff against the server state file."""
    config = load_config(args.config_dir)
    _require_server_enabled(config)
    version = args.version or _today_version()

    state = load_state(config.server_state_file)
    if state is None:
        print(
            f"No state file at {config.server_state_file}; treating every file as new.",
            file=sys.stderr,
        )
        state = PublishedState(version_id="", fingerprint={})

    result = build_server_pack(config, version_id=version)
    print_server_summary(result, verbose=args.verbose)
    diff = diff_fingerprints(state.fingerprint, result.fingerprint)
    print()
    _print_server_diff(diff, state.version_id)
    if args.changelog and not diff.is_empty:
        print()
        print("--- Changelog ---")
        print(render_changelog(diff, state.version_id, version))
    return 0 if diff.is_empty else 1


def cmd_server_publish(args: argparse.Namespace) -> int:
    """Build, update server state, optionally commit+push the server tree."""
    config = load_config(args.config_dir)
    _require_server_enabled(config)
    version = args.version or _today_version()

    state = load_state(config.server_state_file)
    previous_version = state.version_id if state else None
    result = build_server_pack(config, version_id=version)
    print_server_summary(result, verbose=args.verbose)
    print()

    if state is not None:
        diff = diff_fingerprints(state.fingerprint, result.fingerprint)
        _print_server_diff(diff, previous_version)
        if diff.is_empty and not args.allow_no_changes:
            print(
                "\nNo changes since last server publish. Pass --allow-no-changes "
                "to publish anyway.",
                file=sys.stderr,
            )
            return 1
    else:
        diff = None

    new_state = PublishedState(
        version_id=version,
        fingerprint=result.fingerprint,
        published_at=utc_now_iso(),
        notes={"published_via": "server"},
    )
    save_state(config.server_state_file, new_state)
    print(f"Updated state file: {config.server_state_file}")

    if args.push:
        _post_publish_server_git(
            config_dir=config.config_dir,
            state_file=config.server_state_file,
            server_output_dir=result.output_dir,
            diff=diff,
            previous_version=previous_version,
            new_version=version,
            commit_message_override=args.message,
        )

    if args.notify:
        webhook = config.packwiz_server.notify.discord_webhook_url
        if not webhook:
            print(
                "warning: --notify passed but [packwiz_server.notify]."
                "discord_webhook_url is not set in config.local.toml; "
                "skipping Discord post.",
                file=sys.stderr,
            )
        elif diff is None or diff.is_empty:
            # Either first publish or no-changes-with-allow-no-changes; still
            # post so subscribers know a publish happened.
            changelog_text = (
                "_Initial server pack publish._"
                if diff is None
                else f"_Republished (no content changes since {previous_version})._"
            )
            try:
                post_discord_changelog(
                    webhook,
                    version=version,
                    changelog_md=changelog_text,
                    approve_command_hint=f"To apply: `prism_sync server-deploy-to-bloomhost` then restart the server via panel.",
                )
                print("Posted notification to Discord.")
            except NotifyError as exc:
                print(f"warning: Discord notify failed: {exc}", file=sys.stderr)
        else:
            changelog_text = render_changelog(diff, previous_version, version)
            try:
                post_discord_changelog(
                    webhook,
                    version=version,
                    changelog_md=changelog_text,
                    approve_command_hint=f"To apply: `prism_sync server-deploy-to-bloomhost` then restart the server via panel.",
                )
                print("Posted notification to Discord.")
            except NotifyError as exc:
                print(f"warning: Discord notify failed: {exc}", file=sys.stderr)

    if config.packwiz_server.base_url:
        print(f"\nServed at: {config.packwiz_server.base_url}/pack.toml")
    return 0


def _post_publish_server_git(
    *,
    config_dir: Path,
    state_file: Path,
    server_output_dir: Path,
    diff: PackDiff | None,
    previous_version: str | None,
    new_version: str,
    commit_message_override: str | None,
) -> None:
    """Stage server tree + state + CHANGELOG, commit with `(server)` tag, push."""
    repo_root = _find_git_root(config_dir)
    if repo_root is None:
        print(
            f"warning: no .git directory found above {config_dir}; skipping --push.",
            file=sys.stderr,
        )
        return

    changelog_path = config_dir / "CHANGELOG.md"
    fallback = "Initial release." if diff is None else None
    entry = render_file_entry(
        diff or PackDiff(),
        previous_version=previous_version,
        new_version=f"{new_version} (server)",
        fallback_summary=fallback,
    )
    prepend_to_changelog_file(changelog_path, entry)
    print(f"Updated changelog: {changelog_path}")

    tracked_paths = [
        _repo_relative(repo_root, server_output_dir),
        _repo_relative(repo_root, state_file),
        _repo_relative(repo_root, changelog_path),
    ]
    gitutil.stage(repo_root, tracked_paths)

    if not gitutil.has_staged_or_unstaged_changes_for(repo_root, tracked_paths):
        print("Nothing to commit (no tracked files changed).")
        return

    if commit_message_override:
        message = commit_message_override
    elif diff is None or diff.is_empty:
        message = f"Server release {new_version}"
    else:
        message = (
            f"Server release {new_version}: "
            f"+{len(diff.added)} ~{len(diff.updated)} -{len(diff.removed)}"
        )
    gitutil.commit(repo_root, message)
    print(f"Committed: {message}")

    if not gitutil.has_remote(repo_root):
        print("No `origin` remote configured; skipping push.", file=sys.stderr)
        return
    try:
        gitutil.push(repo_root)
        print(f"Pushed to {gitutil.current_branch(repo_root)}.")
    except GitError as exc:
        print(f"warning: push failed: {exc}", file=sys.stderr)


# ---------------------------------------------------------------------------
# Server-only Modrinth attach + refresh
# ---------------------------------------------------------------------------


def cmd_server_attach_metafiles(args: argparse.Namespace) -> int:
    """Hash-lookup server-only jars against Modrinth, write .pw.toml's for matches.

    "Server-only" here means: jar in Custom Mods/server/mods/ whose mod ID
    doesn't match any client mod (those already auto-resolve via the
    client's metafile). For each such jar, we hash it (SHA-1), batch-query
    Modrinth, and if a match is found write a .pw.toml alongside the jar
    so subsequent builds ship it as a CDN reference instead of self-host.

    Idempotent: skips jars that already have an adjacent .pw.toml.
    """
    config = load_config(args.config_dir)
    _require_server_enabled(config)

    server_dir = config.packwiz_server.server_dir
    mods_dir = server_dir / "mods"
    if not mods_dir.is_dir():
        raise ServerBuildError(
            f"{mods_dir} does not exist. Run `server-bootstrap-from-sftp` first."
        )

    # Build the set of mod IDs already present on the client side so we can
    # skip "client-matched" jars (those resolve automatically; don't need a
    # standalone server-side metafile).
    client_mods_dir = config.packwiz.output_dir / "mods"
    client_mod_ids: set[str] = set()
    if client_mods_dir.is_dir():
        for jar in client_mods_dir.glob("*.jar"):
            meta = read_jar_meta(jar)
            if meta:
                client_mod_ids.add(meta.mod_id)
    # Also scan Prism instance mods for IDs (client metafiles point at jars
    # there too).
    prism_ids = collect_mod_ids(config.minecraft_dir / "mods")
    client_mod_ids.update(prism_ids.keys())

    # Find candidate jars (server-only, no adjacent .pw.toml).
    candidates: list[Path] = []
    sha1_by_path: dict[Path, str] = {}
    skipped_paired = 0
    skipped_client_matched = 0
    for jar in sorted(mods_dir.glob("*.jar")):
        adj = jar.with_suffix(".pw.toml")
        # Also check for slug-based adjacent .pw.toml.
        meta = read_jar_meta(jar)
        if adj.exists() or (meta and (mods_dir / f"{meta.mod_id}.pw.toml").exists()):
            skipped_paired += 1
            continue
        if meta and meta.mod_id in client_mod_ids:
            skipped_client_matched += 1
            continue
        candidates.append(jar)
        sha1_by_path[jar] = hash_file(jar).sha1

    if not candidates:
        print(
            f"Nothing to attach: {skipped_paired} already paired, "
            f"{skipped_client_matched} client-matched (use client's metafile)."
        )
        return 0

    print(
        f"Hash-looking up {len(candidates)} server-only jar(s) against Modrinth..."
    )
    sha1_list = list(sha1_by_path.values())
    lookups = lookup_by_sha1(sha1_list, config.user_agent)

    written = 0
    unmatched: list[Path] = []
    for jar in candidates:
        sha1 = sha1_by_path[jar]
        version = lookups.get(sha1)
        if version is None:
            unmatched.append(jar)
            continue
        file = version.file_by_sha1(sha1) or version.primary_file()
        if file is None or not file.is_cdn_safe:
            unmatched.append(jar)
            continue
        slug = read_jar_meta(jar)
        slug_str = slug.filename_slug if slug else jar.stem
        out_path = mods_dir / f"{slug_str}.pw.toml"
        out_path.write_text(
            synthesize_pwtoml(
                project_slug_or_id=version.project_id,
                version=version,
                file=file,
                side="both",
            ),
            encoding="utf-8",
        )
        print(f"  + {out_path.name} -> {version.project_id} v{version.version_number}")
        written += 1

    print()
    print(
        f"Attached {written} metafile(s); {len(unmatched)} unmatched "
        f"(not on Modrinth or no CDN-safe file)."
    )
    if unmatched and args.verbose:
        for j in unmatched:
            print(f"  - {j.name}")
    return 0


def cmd_server_refresh_metafiles(args: argparse.Namespace) -> int:
    """Bump every .pw.toml in Custom Mods/server/mods/ to the latest compatible version.

    Queries Modrinth's project version list, filtered by the pack's MC +
    loader. If a newer version is available, rewrites the .pw.toml with
    the new download URL + hash. Prints a per-mod diff.
    """
    config = load_config(args.config_dir)
    _require_server_enabled(config)

    instance = read_instance(config.instance_path)
    mc_version = instance.minecraft_version
    loader = instance.loader.mrpack_key
    mods_dir = config.packwiz_server.server_dir / "mods"
    if not mods_dir.is_dir():
        raise ServerBuildError(
            f"{mods_dir} does not exist. Run `server-bootstrap-from-sftp` first."
        )

    pwtomls = sorted(mods_dir.glob("*.pw.toml"))
    if not pwtomls:
        print("No .pw.toml files in server mods/. Nothing to refresh.")
        return 0

    updated = 0
    up_to_date = 0
    failed: list[tuple[str, str]] = []
    for pw in pwtomls:
        try:
            data = tomllib.loads(pw.read_text(encoding="utf-8"))
        except (UnicodeDecodeError, tomllib.TOMLDecodeError) as exc:
            failed.append((pw.name, f"unparseable TOML ({exc})"))
            continue
        update = data.get("update") or {}
        modrinth_block = update.get("modrinth") or {}
        project_id = modrinth_block.get("mod-id")
        current_version_id = modrinth_block.get("version")
        if not project_id:
            failed.append(
                (pw.name, "no [update.modrinth].mod-id (not a Modrinth metafile)")
            )
            continue

        latest = latest_compatible_version(
            project_id,
            user_agent=config.user_agent,
            minecraft_version=mc_version,
            loader=loader,
        )
        if latest is None:
            failed.append(
                (pw.name, f"no compatible version for MC {mc_version} / {loader}")
            )
            continue
        if latest.id == current_version_id:
            up_to_date += 1
            continue
        file = latest.primary_file()
        if file is None:
            failed.append((pw.name, "latest version has no CDN-safe file"))
            continue
        pw.write_text(
            synthesize_pwtoml(
                project_slug_or_id=project_id,
                version=latest,
                file=file,
                side="both",
            ),
            encoding="utf-8",
        )
        print(
            f"  ~ {pw.name}: {current_version_id or '(unset)'} -> "
            f"{latest.id} (v{latest.version_number})"
        )
        updated += 1

    print()
    print(f"Refreshed: {updated} updated, {up_to_date} already current.")
    if failed:
        print()
        print(f"Could not refresh {len(failed)}:")
        for name, reason in failed:
            print(f"  ! {name}: {reason}")
    return 0


# ---------------------------------------------------------------------------
# Server deploy: SFTP-push the resolved pack to BloomHost
# ---------------------------------------------------------------------------


def cmd_server_deploy_to_bloomhost(args: argparse.Namespace) -> int:
    """Materialize docs/packwiz-server/ locally + SFTP-sync to BloomHost /mods/.

    BloomHost's Pterodactyl egg doesn't let us customize the Startup Command,
    so we can't run packwiz-installer on the server itself. Instead we do
    the resolution locally (download CDN-referenced jars, copy self-hosted
    ones) and then SFTP-push the resulting tree, adding/replacing/removing
    files as needed to make /mods/ match the published pack.

    Config dirs are never touched — they're the server admin's domain.
    """
    config = load_config(args.config_dir)
    server_settings = config.packwiz_server
    if not server_settings.enabled:
        raise PackwizError(
            "Server pipeline is not enabled. Set [packwiz_server].enabled = true."
        )
    cache_dir = config.config_dir / ".cache" / "server-resolved"
    run_deploy(
        server_settings=server_settings,
        cache_dir=cache_dir,
        user_agent=config.user_agent,
        confirm=not args.yes,
    )
    return 0


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="sync",
        description="Build and publish a Prism Launcher instance as a Modrinth modpack.",
    )
    parser.add_argument("-v", "--verbose", action="store_true")
    parser.add_argument(
        "--config-dir",
        type=Path,
        default=None,
        help="Directory containing config.toml and config.local.toml. Defaults to cwd.",
    )

    sub = parser.add_subparsers(dest="command", required=True)

    p_build = sub.add_parser("build", help="Build a .mrpack from the Prism instance.")
    p_build.add_argument("--out", type=str, default=None, help="Output .mrpack path.")
    p_build.add_argument(
        "--version",
        type=str,
        default=None,
        help="Pack versionId (default: today's date YYYY.MM.DD).",
    )
    p_build.set_defaults(func=cmd_build)

    p_check = sub.add_parser("check", help="Build + diff against last published state.")
    p_check.add_argument("--out", type=str, default=None)
    p_check.add_argument("--version", type=str, default=None)
    p_check.add_argument(
        "--from-remote",
        action="store_true",
        help="Re-fetch the last published .mrpack from Modrinth and use its "
        "contents as the baseline, overwriting any local state file.",
    )
    p_check.add_argument(
        "--changelog",
        action="store_true",
        help="Also print the generated markdown changelog if there are changes.",
    )
    p_check.set_defaults(func=cmd_check)

    p_publish = sub.add_parser("publish", help="Build + publish to Modrinth.")
    p_publish.add_argument("--out", type=str, default=None)
    p_publish.add_argument(
        "--version",
        type=str,
        default=None,
        help="Override version number. Default: today's date YYYY.MM.DD, "
        "auto-bumped to .N for same-day reissues.",
    )
    p_publish.add_argument(
        "--type", choices=["release", "beta", "alpha"], default="release"
    )
    p_publish.add_argument(
        "--draft",
        action="store_true",
        help="Publish as a draft (not immediately listed).",
    )
    p_publish.add_argument(
        "--dry-run",
        action="store_true",
        help="Print the would-be request without POSTing.",
    )
    p_publish.add_argument(
        "--changelog",
        type=str,
        default=None,
        help="Override the auto-generated changelog text.",
    )
    p_publish.add_argument(
        "--allow-no-state",
        action="store_true",
        help="Permit publishing when no local state file exists (e.g. first ever publish).",
    )
    p_publish.add_argument(
        "--allow-no-changes",
        action="store_true",
        help="Publish even when the diff vs last state is empty.",
    )
    p_publish.add_argument(
        "--push",
        action="store_true",
        help="After a successful publish, append to CHANGELOG.md, stage state + "
        "changelog + config, commit, and push to the configured git remote.",
    )
    p_publish.add_argument(
        "--message",
        "-m",
        type=str,
        default=None,
        help="Override the auto-generated git commit message (only used with --push).",
    )
    p_publish.set_defaults(func=cmd_publish)

    # --- Packwiz subcommands ---

    p_pw_build = sub.add_parser(
        "packwiz-build",
        help="Generate the Packwiz tree under [packwiz].output_dir.",
    )
    p_pw_build.add_argument("--version", type=str, default=None)
    p_pw_build.set_defaults(func=cmd_packwiz_build)

    p_pw_check = sub.add_parser(
        "packwiz-check",
        help="Build the Packwiz tree and diff against the shared state file.",
    )
    p_pw_check.add_argument("--version", type=str, default=None)
    p_pw_check.add_argument(
        "--changelog",
        action="store_true",
        help="Also print the generated markdown changelog if there are changes.",
    )
    p_pw_check.set_defaults(func=cmd_packwiz_check)

    p_pw_publish = sub.add_parser(
        "packwiz-publish",
        help="Build, update state, optionally commit+push the Packwiz tree.",
    )
    p_pw_publish.add_argument("--version", type=str, default=None)
    p_pw_publish.add_argument(
        "--allow-no-changes",
        action="store_true",
        help="Publish even when the diff vs last state is empty.",
    )
    p_pw_publish.add_argument(
        "--push",
        action="store_true",
        help="After build, append to CHANGELOG.md, stage state + docs/packwiz "
        "+ changelog at the repo root, commit, and push.",
    )
    p_pw_publish.add_argument(
        "--message",
        "-m",
        type=str,
        default=None,
        help="Override the auto-generated git commit message (only used with --push).",
    )
    p_pw_publish.set_defaults(func=cmd_packwiz_publish)

    # --- Server subcommands ---

    p_srv_bootstrap = sub.add_parser(
        "server-bootstrap-from-sftp",
        help="One-time: pull current BloomHost server tree into Custom Mods/server/.",
    )
    p_srv_bootstrap.add_argument(
        "--clean",
        action="store_true",
        help="Wipe each [packwiz_server.deploy].bootstrap_pull target subdir "
        "before downloading. Use for a clean re-bootstrap.",
    )
    p_srv_bootstrap.set_defaults(func=cmd_server_bootstrap_from_sftp)

    p_srv_build = sub.add_parser(
        "server-build",
        help="Generate the server Packwiz tree under [packwiz_server].output_dir.",
    )
    p_srv_build.add_argument("--version", type=str, default=None)
    p_srv_build.add_argument(
        "-v",
        "--verbose",
        action="store_true",
        help="Print the per-mod classification table (mod ID + resolution path).",
    )
    p_srv_build.set_defaults(func=cmd_server_build)

    p_srv_check = sub.add_parser(
        "server-check",
        help="Build the server tree and diff against the server state file.",
    )
    p_srv_check.add_argument("--version", type=str, default=None)
    p_srv_check.add_argument(
        "--changelog",
        action="store_true",
        help="Also print the generated markdown changelog if there are changes.",
    )
    p_srv_check.add_argument(
        "-v",
        "--verbose",
        action="store_true",
        help="Print the per-mod classification table (mod ID + resolution path).",
    )
    p_srv_check.set_defaults(func=cmd_server_check)

    p_srv_publish = sub.add_parser(
        "server-publish",
        help="Build, update state, optionally commit+push the server Packwiz tree.",
    )
    p_srv_publish.add_argument("--version", type=str, default=None)
    p_srv_publish.add_argument(
        "--allow-no-changes",
        action="store_true",
        help="Publish even when the diff vs last state is empty.",
    )
    p_srv_publish.add_argument(
        "-v",
        "--verbose",
        action="store_true",
        help="Print the per-mod classification table (mod ID + resolution path).",
    )
    p_srv_publish.add_argument(
        "--push",
        action="store_true",
        help="After build, append to CHANGELOG.md, stage state + "
        "docs/packwiz-server + changelog at the repo root, commit, and push.",
    )
    p_srv_publish.add_argument(
        "--notify",
        action="store_true",
        help="Post a changelog summary to the Discord webhook configured in "
        "[packwiz_server.notify]. (Phase C; not yet wired in.)",
    )
    p_srv_publish.add_argument(
        "--message",
        "-m",
        type=str,
        default=None,
        help="Override the auto-generated git commit message (only used with --push).",
    )
    p_srv_publish.set_defaults(func=cmd_server_publish)

    p_srv_attach = sub.add_parser(
        "server-attach-metafiles",
        help="Hash-lookup server-only jars against Modrinth; attach .pw.toml "
        "for each match so subsequent builds use CDN delivery (auto-update).",
    )
    p_srv_attach.add_argument(
        "-v",
        "--verbose",
        action="store_true",
        help="Also list every jar that didn't match a Modrinth release.",
    )
    p_srv_attach.set_defaults(func=cmd_server_attach_metafiles)

    p_srv_refresh = sub.add_parser(
        "server-refresh-metafiles",
        help="Bump every .pw.toml in Custom Mods/server/mods/ to the latest "
        "compatible Modrinth version. Use to update server-only Modrinth mods.",
    )
    p_srv_refresh.set_defaults(func=cmd_server_refresh_metafiles)

    p_srv_deploy = sub.add_parser(
        "server-deploy-to-bloomhost",
        help="Materialize the published server pack locally + SFTP-sync mods/ "
        "to BloomHost. Prompts for confirmation by default; pass --yes for "
        "batch use.",
    )
    p_srv_deploy.add_argument(
        "-y",
        "--yes",
        action="store_true",
        help="Skip the y/N confirmation; apply the plan immediately.",
    )
    p_srv_deploy.set_defaults(func=cmd_server_deploy_to_bloomhost)

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = _build_parser()
    args = parser.parse_args(argv)
    _setup_logging(args.verbose)
    try:
        return args.func(args)
    except (
        ConfigError,
        InstanceError,
        GitError,
        PackwizError,
        ServerBuildError,
        DeployError,
        SftpError,
        RuntimeError,
    ) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    except requests.HTTPError as exc:
        resp = exc.response
        detail = ""
        if resp is not None:
            try:
                body = resp.json()
                detail = f" — {body.get('description') or body.get('error') or body}"
            except Exception:
                detail = f" — {resp.text[:200]}"
        print(
            f"error: Modrinth API request failed ({exc}){detail}",
            file=sys.stderr,
        )
        return 1
    except requests.RequestException as exc:
        print(f"error: network request failed: {exc}", file=sys.stderr)
        return 1
