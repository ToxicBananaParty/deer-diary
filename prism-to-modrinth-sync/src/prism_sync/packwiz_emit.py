"""Shared Packwiz-tree emit helpers.

Both the client (``packwiz.py``) and server (``server.py``) build pipelines
write ``pack.toml`` + ``index.toml`` + a tree of metafiles / direct files
into an output dir. The TOML rendering, file copy, and metafile-bytes
normalization are identical between them, so they live here and both
build modules import them.

This module intentionally has no dependency on the client OR server build
internals — keeps the dependency graph DAG-shaped.
"""

from __future__ import annotations

import logging
import re
import shutil
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


log = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# TOML rendering
# ---------------------------------------------------------------------------
#
# We hand-write the two pack-root TOML files because Python's stdlib doesn't
# bundle a writer, and our schemas are simple enough that pulling in tomli-w
# isn't worth the dependency churn. All string values we serialize here are
# generated from filenames, hashes, and config values — they never contain
# quotes, backslashes, or non-ASCII, so the basic-string form is safe.


def toml_str(s: str) -> str:
    """Render a string as a basic TOML string literal (with double quotes).

    Escapes the few characters that ever realistically appear in our inputs.
    For arbitrary user input you'd want a full TOML serializer, but our
    inputs are pack metadata: paths (forward-slash POSIX), hashes (hex),
    version numbers, and config values we control.
    """
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


@dataclass
class IndexFile:
    """One row in ``index.toml``'s ``[[files]]`` table.

    Shared between client and server builds. ``metafile=True`` flags the
    entry as a Packwiz ``.pw.toml`` (download instructions live in the
    file, not directly in ``index.toml``); ``preserve=True`` tells
    packwiz-installer not to overwrite a player-local copy across updates.
    """

    file: str       # POSIX, relative to pack root
    hash: str       # sha256 hex
    metafile: bool = False
    preserve: bool = False
    alias: str = ""


def render_pack_toml(
    *,
    name: str,
    author: str,
    version: str,
    pack_format: str,
    index_hash: str,
    minecraft_version: str,
    loader_key: str,
    loader_version: str,
) -> str:
    lines = [
        f"name = {toml_str(name)}",
    ]
    if author:
        lines.append(f"author = {toml_str(author)}")
    lines.append(f"version = {toml_str(version)}")
    lines.append(f"pack-format = {toml_str(pack_format)}")
    lines.append("")
    lines.append("[index]")
    lines.append('file = "index.toml"')
    lines.append('hash-format = "sha256"')
    lines.append(f"hash = {toml_str(index_hash)}")
    lines.append("")
    lines.append("[versions]")
    lines.append(f"minecraft = {toml_str(minecraft_version)}")
    lines.append(f"{loader_key} = {toml_str(loader_version)}")
    lines.append("")
    return "\n".join(lines)


def render_index_toml(entries: Iterable[IndexFile]) -> str:
    lines = ['hash-format = "sha256"', ""]
    for entry in sorted(entries, key=lambda e: e.file):
        lines.append("[[files]]")
        lines.append(f"file = {toml_str(entry.file)}")
        lines.append(f"hash = {toml_str(entry.hash)}")
        if entry.metafile:
            lines.append("metafile = true")
        if entry.preserve:
            lines.append("preserve = true")
        if entry.alias:
            lines.append(f"alias = {toml_str(entry.alias)}")
        lines.append("")
    return "\n".join(lines)


# ---------------------------------------------------------------------------
# File ops
# ---------------------------------------------------------------------------


def copy_direct(src: Path, dest: Path) -> None:
    """Copy a file into the output tree, creating parent dirs as needed."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, dest)


# ---------------------------------------------------------------------------
# Metafile bytes normalization
# ---------------------------------------------------------------------------


# Matches `side = '...'` or `side = "..."` at the start of a line (TOML's
# top-level `side` key). Captures the existing value for replacement.
# Uses [ \t]* (not \s*) for the trailing whitespace so the regex doesn't
# greedily consume newlines / blank lines after the side line.
_SIDE_LINE_RE = re.compile(
    r"""(?m)^side[ \t]*=[ \t]*['"]([^'"]*)['"][ \t]*$"""
)


def normalize_metafile_bytes(
    data: bytes,
    *,
    optional_block: str | None = None,
    force_side: str | None = None,
) -> bytes:
    """Fix Prism quirks, optionally inject options, optionally force side.

    Normalizations always applied:
    - Line endings normalized to LF (input may be CRLF if the source
      .pw.toml was written by ``Path.write_text`` on Windows; the regex
      below expects LF-terminated lines and would otherwise miss the
      ``side =`` line and incorrectly insert a duplicate).
    - ``side = ''`` -> ``side = 'both'`` (Prism writes empty when Modrinth
      doesn't expose a side; packwiz-installer rejects empty as "Invalid
      side name").

    If ``force_side`` is given, rewrites ANY existing ``side = '...'`` line
    to that value (or appends one if the metafile doesn't have a side key).
    Used by the server emit to set ``side = 'both'`` regardless of what
    the source metafile (often the client's metafile) said — presence in
    the server folder is the user's explicit override of upstream side
    metadata.

    If ``optional_block`` is given, appends it as a ``[option]`` table
    (used by the client emit for packwiz_installer GUI checkboxes).
    """
    text = data.decode("utf-8")
    # Normalize line endings BEFORE pattern matching. Files written via
    # Path.write_text on Windows get CRLF; the _SIDE_LINE_RE pattern only
    # tolerates [ \t]* between the closing quote and end-of-line, so a
    # stray \r would prevent the match and force the "insert before first
    # [section]" branch — producing duplicate `side =` lines (broken TOML).
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    # Always-applied: empty -> both.
    text = text.replace("side = ''", "side = 'both'")
    text = text.replace('side = ""', 'side = "both"')

    if force_side is not None:
        replacement = f"side = {toml_str(force_side)}"
        if _SIDE_LINE_RE.search(text):
            text = _SIDE_LINE_RE.sub(replacement, text, count=1)
        else:
            # No side key at all. Insert before the first [section] header so
            # it lands in the top-level table (where `side` belongs).
            insertion = replacement + "\n"
            header_match = re.search(r"(?m)^\[", text)
            if header_match:
                idx = header_match.start()
                text = text[:idx] + insertion + text[idx:]
            else:
                text = text.rstrip() + "\n" + insertion

    if optional_block is not None:
        text = text.rstrip() + "\n\n" + optional_block.rstrip() + "\n"

    return text.encode("utf-8")


def build_optional_block(default: bool, description: str) -> str:
    """Render a Packwiz ``[option]`` block.

    Kept here (rather than in packwiz.py) so server.py could reuse it
    if you ever want server-side optional mods. Currently only the
    client emit uses it.
    """
    return (
        "[option]\n"
        "optional = true\n"
        f"default = {'true' if default else 'false'}\n"
        f"description = {toml_str(description)}\n"
    )
