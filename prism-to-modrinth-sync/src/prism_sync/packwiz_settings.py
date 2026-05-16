"""Settings dataclass for the Packwiz publish pipeline.

Lives in its own module because :mod:`config` needs it but :mod:`packwiz`
(which holds the build implementation) also needs :class:`Config`. Splitting
the settings out breaks the cycle: ``config`` → ``packwiz_settings``;
``packwiz`` → both. Mirrors how ``customs.CustomModConfig`` sits below
``config``.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path


@dataclass(frozen=True)
class OptionalMod:
    """An entry in ``[packwiz.optional_mods]``.

    The slug (e.g. ``"iris"``) is the basename of the Prism ``.pw.toml``
    minus ``.pw.toml``. ``default`` is what the install-time checkbox
    starts at; ``description`` is shown next to the checkbox. Players who
    want to change their choice later edit ``packwiz.json`` in the
    instance, or delete it to get re-prompted on next launch.
    """

    default: bool
    description: str


@dataclass
class PackwizSettings:
    """The ``[packwiz]`` section of ``config.toml``, parsed and defaulted.

    ``output_dir`` and ``base_url`` are the publishing target. The
    self-host allowlist gates which mod jars we'll copy as direct files
    (anything not on the list and without ``.pw.toml`` metadata is an error).
    """

    enabled: bool = False
    output_dir: Path = Path("../docs/packwiz")
    base_url: str = ""
    author: str = ""
    pack_format: str = "packwiz:1.1.0"
    self_host_allowed_globs: list[str] = field(default_factory=list)
    preserve_globs: list[str] = field(default_factory=list)
    optional_mods: dict[str, OptionalMod] = field(default_factory=dict)
    bootstrap_installer_url: str = (
        "https://github.com/packwiz/packwiz-installer-bootstrap/releases/"
        "download/v0.0.3/packwiz-installer-bootstrap.jar"
    )

    @classmethod
    def from_dict(cls, raw: dict | None, config_dir: Path) -> "PackwizSettings":
        raw = raw or {}
        self_host = raw.get("self_host") or {}
        output_raw = raw.get("output_dir", "../docs/packwiz")
        output_dir = Path(output_raw)
        if not output_dir.is_absolute():
            output_dir = (config_dir / output_dir).resolve()
        optional_mods_raw = raw.get("optional_mods", {}) or {}
        optional_mods: dict[str, OptionalMod] = {}
        for slug, entry in optional_mods_raw.items():
            if not isinstance(entry, dict):
                continue
            optional_mods[str(slug)] = OptionalMod(
                default=bool(entry.get("default", True)),
                description=str(entry.get("description", "")),
            )
        return cls(
            enabled=bool(raw.get("enabled", False)),
            output_dir=output_dir,
            base_url=str(raw.get("base_url", "")).rstrip("/"),
            author=str(raw.get("author", "")),
            pack_format=str(raw.get("pack_format", "packwiz:1.1.0")),
            self_host_allowed_globs=list(self_host.get("allowed_globs", []) or []),
            preserve_globs=list(raw.get("preserve_globs", []) or []),
            optional_mods=optional_mods,
            bootstrap_installer_url=str(
                raw.get(
                    "bootstrap_installer_url",
                    cls.bootstrap_installer_url,
                )
            ),
        )
