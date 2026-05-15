from __future__ import annotations

import argparse
import datetime as dt
import logging
import sys
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
from .instance import InstanceError, read_instance
from .mrpack import BuildResult, build_mrpack, fingerprint_from_mrpack
from .publish import format_dry_run, make_payload, next_available_version_number, post_version
from .remote import download_mrpack, latest_version
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

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = _build_parser()
    args = parser.parse_args(argv)
    _setup_logging(args.verbose)
    try:
        return args.func(args)
    except (ConfigError, InstanceError, GitError, RuntimeError) as exc:
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
