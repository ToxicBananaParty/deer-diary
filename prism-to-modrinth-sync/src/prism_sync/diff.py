from __future__ import annotations

from dataclasses import dataclass, field


@dataclass
class PackDiff:
    added: list[str] = field(default_factory=list)
    updated: list[str] = field(default_factory=list)
    removed: list[str] = field(default_factory=list)

    @property
    def is_empty(self) -> bool:
        return not (self.added or self.updated or self.removed)

    @property
    def total(self) -> int:
        return len(self.added) + len(self.updated) + len(self.removed)


def diff_fingerprints(
    last: dict[str, str], current: dict[str, str]
) -> PackDiff:
    last_keys = set(last)
    current_keys = set(current)
    added = sorted(current_keys - last_keys)
    removed = sorted(last_keys - current_keys)
    updated = sorted(
        k for k in (current_keys & last_keys) if last[k] != current[k]
    )
    return PackDiff(added=added, updated=updated, removed=removed)
