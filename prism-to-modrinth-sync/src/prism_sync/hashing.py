from __future__ import annotations

import hashlib
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class FileHashes:
    size: int
    sha1: str
    sha512: str


def hash_file(path: Path, chunk_size: int = 1024 * 1024) -> FileHashes:
    sha1 = hashlib.sha1()
    sha512 = hashlib.sha512()
    size = 0
    with path.open("rb") as fh:
        while True:
            chunk = fh.read(chunk_size)
            if not chunk:
                break
            sha1.update(chunk)
            sha512.update(chunk)
            size += len(chunk)
    return FileHashes(size=size, sha1=sha1.hexdigest(), sha512=sha512.hexdigest())
