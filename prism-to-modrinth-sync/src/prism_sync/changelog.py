from __future__ import annotations

import datetime as dt
from collections import defaultdict

from .diff import PackDiff


# Top-level pack directories we group changelog entries by, in display order.
KNOWN_SECTIONS = (
    "mods",
    "config",
    "resourcepacks",
    "shaderpacks",
    "defaultconfigs",
    "moonlight-global-datapacks",
)

SECTION_TITLES = {
    "mods": "Mods",
    "config": "Config",
    "resourcepacks": "Resource packs",
    "shaderpacks": "Shaders",
    "defaultconfigs": "Default configs",
    "moonlight-global-datapacks": "Datapacks",
}


def _section_of(path: str) -> str:
    head = path.split("/", 1)[0]
    return head if head in KNOWN_SECTIONS else "other"


def _group_by_section(paths: list[str]) -> dict[str, list[str]]:
    grouped: dict[str, list[str]] = defaultdict(list)
    for p in paths:
        grouped[_section_of(p)].append(p)
    return grouped


def _render_diff_body(diff: PackDiff) -> list[str]:
    """The shared bullet-list body used by both renderers."""
    lines: list[str] = []
    section_order = list(KNOWN_SECTIONS) + ["other"]

    def render_block(title: str, paths_by_section: dict[str, list[str]]) -> None:
        if not any(paths_by_section.values()):
            return
        lines.append(f"### {title}")
        for section in section_order:
            section_paths = paths_by_section.get(section, [])
            if not section_paths:
                continue
            lines.append(f"**{SECTION_TITLES.get(section, section.title())}**")
            for path in section_paths:
                lines.append(f"- `{path}`")
            lines.append("")

    render_block("Added", _group_by_section(diff.added))
    render_block("Updated", _group_by_section(diff.updated))
    render_block("Removed", _group_by_section(diff.removed))
    return lines


def render_changelog(
    diff: PackDiff,
    previous_version: str | None,
    new_version: str,
) -> str:
    """Render a markdown changelog for a Modrinth version description.

    Sections appear only when they have content. Files are grouped by their
    top-level directory (Mods, Config, etc.).
    """
    if diff.is_empty:
        return f"No content changes since {previous_version or 'previous version'}."

    lines: list[str] = []
    header = f"Changes in {new_version}"
    if previous_version:
        header += f" (from {previous_version})"
    lines.append(f"## {header}")
    lines.append("")
    lines.extend(_render_diff_body(diff))
    return "\n".join(lines).rstrip() + "\n"


def render_file_entry(
    diff: PackDiff,
    previous_version: str | None,
    new_version: str,
    date: dt.date | None = None,
    fallback_summary: str | None = None,
) -> str:
    """Render a single section for CHANGELOG.md, dated and version-titled.

    Use `fallback_summary` for first-publish or override cases where there is
    no diff to bullet (e.g. "Initial release.").
    """
    date = date or dt.date.today()
    lines = [f"## {new_version} — {date.isoformat()}", ""]
    if fallback_summary is not None or diff.is_empty:
        summary = (
            fallback_summary
            if fallback_summary is not None
            else f"No content changes since {previous_version or 'previous version'}."
        )
        lines.append(summary)
        lines.append("")
        return "\n".join(lines).rstrip() + "\n"
    if previous_version:
        lines.append(f"Changes since `{previous_version}`:")
        lines.append("")
    lines.extend(_render_diff_body(diff))
    return "\n".join(lines).rstrip() + "\n"


def prepend_to_changelog_file(path, entry: str) -> None:
    """Insert a new release section at the top of CHANGELOG.md.

    If the file doesn't exist, create it with an H1 heading first. If it does
    exist, find the H1 and inject the new entry directly after it.
    """
    from pathlib import Path as _Path

    path = _Path(path)
    if not path.is_file():
        contents = "# Changelog\n\n" + entry.rstrip() + "\n"
        path.write_text(contents, encoding="utf-8")
        return
    existing = path.read_text(encoding="utf-8")
    if existing.startswith("# "):
        # Insert after the H1 line and its trailing blank line if present.
        header_end = existing.find("\n")
        head = existing[: header_end + 1]
        rest = existing[header_end + 1 :].lstrip("\n")
        new_contents = head + "\n" + entry.rstrip() + "\n\n" + rest
    else:
        new_contents = entry.rstrip() + "\n\n" + existing
    path.write_text(new_contents, encoding="utf-8")
