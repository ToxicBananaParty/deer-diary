from __future__ import annotations

import datetime as dt
import json
from dataclasses import dataclass, field
from pathlib import Path


@dataclass
class PublishedState:
    version_id: str
    fingerprint: dict[str, str]  # path -> sha512
    published_at: str | None = None  # ISO-8601 UTC, optional
    notes: dict = field(default_factory=dict)  # free-form

    def to_json(self) -> dict:
        out = {
            "version_id": self.version_id,
            "fingerprint": dict(sorted(self.fingerprint.items())),
        }
        if self.published_at:
            out["published_at"] = self.published_at
        if self.notes:
            out["notes"] = self.notes
        return out

    @classmethod
    def from_json(cls, data: dict) -> "PublishedState":
        return cls(
            version_id=data.get("version_id", "0.0.0"),
            fingerprint=dict(data.get("fingerprint", {})),
            published_at=data.get("published_at"),
            notes=dict(data.get("notes", {})),
        )


def load_state(path: Path) -> PublishedState | None:
    if not path.is_file():
        return None
    with path.open("r", encoding="utf-8") as fh:
        return PublishedState.from_json(json.load(fh))


def save_state(path: Path, state: PublishedState) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as fh:
        json.dump(state.to_json(), fh, indent=2, ensure_ascii=False)
        fh.write("\n")


def utc_now_iso() -> str:
    return dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
