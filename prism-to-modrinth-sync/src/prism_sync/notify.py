"""Discord webhook notifier for server pack publishes.

A successful ``server-publish --notify`` posts a single message to the
configured Discord channel containing the version number, the generated
markdown changelog, and a one-liner reminding you how to approve.

Discord webhook limits we care about:
* 2000 chars per ``content`` payload — we truncate at 1900 to leave room.
* 30 requests per 60s per webhook — we make at most 1 per publish, so
  rate limiting doesn't apply in practice.
"""

from __future__ import annotations

import logging

import requests


log = logging.getLogger(__name__)


# Discord's content field max is 2000 chars; leave a safety margin for the
# trailing truncation marker.
_MAX_CONTENT = 1900
_TRUNCATION_MARKER = "\n_(truncated; see CHANGELOG.md for the full diff)_"


class NotifyError(RuntimeError):
    """Raised when the Discord post fails for a recoverable reason."""


def post_discord_changelog(
    webhook_url: str,
    *,
    version: str,
    changelog_md: str,
    approve_command_hint: str | None = None,
) -> None:
    """Post the version + changelog markdown to a Discord webhook.

    ``approve_command_hint`` is an optional trailing line (typically the
    ``prism_sync server-approve <version>`` reminder). Truncates the
    ``changelog_md`` body if the total would exceed Discord's 2000-char
    cap; the truncation marker leaves a pointer to the canonical
    CHANGELOG.md in the repo.
    """
    if not webhook_url:
        raise NotifyError("Discord webhook URL is empty.")

    header = f"**Deer Diary server pack `{version}` published**"
    footer = f"\n\n> {approve_command_hint}" if approve_command_hint else ""

    # Compute how much room the changelog body gets.
    fixed_overhead = len(header) + len(footer) + 2  # the \n\n joiner
    budget = _MAX_CONTENT - fixed_overhead
    body = changelog_md.rstrip()
    if len(body) > budget:
        body = body[: budget - len(_TRUNCATION_MARKER)].rstrip() + _TRUNCATION_MARKER

    content = f"{header}\n\n{body}{footer}"

    try:
        resp = requests.post(
            webhook_url,
            json={"content": content, "allowed_mentions": {"parse": []}},
            timeout=15,
        )
    except requests.RequestException as exc:
        raise NotifyError(f"Discord webhook request failed: {exc}")

    if not 200 <= resp.status_code < 300:
        snippet = resp.text[:200]
        raise NotifyError(
            f"Discord webhook returned HTTP {resp.status_code}: {snippet}"
        )
    log.info("Posted changelog to Discord (%d chars).", len(content))
