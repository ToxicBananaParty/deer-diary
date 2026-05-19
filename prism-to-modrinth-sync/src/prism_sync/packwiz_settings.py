"""Settings dataclasses for the Packwiz publish pipelines.

Lives in its own module because :mod:`config` needs it but :mod:`packwiz`
(which holds the build implementation) also needs :class:`Config`. Splitting
the settings out breaks the cycle: ``config`` → ``packwiz_settings``;
``packwiz`` → both. Mirrors how ``customs.CustomModConfig`` sits below
``config``.

Hosts both :class:`PackwizSettings` (the client ``[packwiz]`` section) and
:class:`PackwizServerSettings` (the new ``[packwiz_server]`` section). They
share a lot of structure but diverge on side-handling, server-folder
walking, and SFTP deployment — keeping them as separate dataclasses keeps
each one's intent clear instead of overloading one class with optionals.
"""

from __future__ import annotations

import os
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


# ---------------------------------------------------------------------------
# [packwiz_server] — the server pack publishing pipeline.
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class SftpDeploy:
    """``[packwiz_server.deploy]`` — credentials + remote layout for SFTP.

    Secrets (password, key path) are read from ``password_env`` /
    ``key_path_env`` so they can live in ``config.local.toml`` or as
    environment variables, never in tracked config. ``bootstrap_pull``
    declares which remote paths the bootstrap command mirrors; the deploy
    command always operates on ``mods/`` (config stays under user control).
    """

    host: str = ""
    port: int = 22
    user: str = ""
    password_env: str = ""
    key_path: Path | None = None
    remote_dir: str = "/home/container"
    bootstrap_pull: list[str] = field(default_factory=list)
    bootstrap_deny_paths: list[str] = field(default_factory=list)

    @property
    def configured(self) -> bool:
        return bool(self.host and self.user)

    def resolve_password(self) -> str | None:
        if not self.password_env:
            return None
        return os.environ.get(self.password_env)

    @classmethod
    def from_dict(cls, raw: dict | None) -> "SftpDeploy":
        raw = raw or {}
        key_path_raw = raw.get("key_path")
        key_path = Path(key_path_raw).expanduser() if key_path_raw else None
        return cls(
            host=str(raw.get("host", "")),
            port=int(raw.get("port", 22)),
            user=str(raw.get("user", "")),
            password_env=str(raw.get("password_env", "")),
            key_path=key_path,
            remote_dir=str(raw.get("remote_dir", "/home/container")),
            bootstrap_pull=list(raw.get("bootstrap_pull", []) or []),
            bootstrap_deny_paths=list(raw.get("bootstrap_deny_paths", []) or []),
        )


@dataclass(frozen=True)
class DiscordNotify:
    """``[packwiz_server.notify]`` — Discord webhook for changelog pings."""

    discord_webhook_url: str = ""
    post_changelog: bool = True

    @property
    def configured(self) -> bool:
        return bool(self.discord_webhook_url)

    @classmethod
    def from_dict(cls, raw: dict | None) -> "DiscordNotify":
        raw = raw or {}
        return cls(
            discord_webhook_url=str(raw.get("discord_webhook_url", "")),
            post_changelog=bool(raw.get("post_changelog", True)),
        )


@dataclass
class PackwizServerSettings:
    """The ``[packwiz_server]`` section of ``config.toml``, parsed and defaulted.

    Server pack: a second Packwiz tree built from ``server_dir`` (the
    authoritative roster of what runs on the server) plus shared configs
    from the client instance. Mod-ID matching against the client pack
    decides per-mod delivery: client metafile passthrough, client self-host
    passthrough, or server-only (direct file or attached metafile).
    """

    enabled: bool = False
    output_dir: Path = Path("../docs/packwiz-server")
    base_url: str = ""
    author: str = ""
    pack_format: str = "packwiz:1.1.0"
    bootstrap_installer_url: str = (
        "https://github.com/packwiz/packwiz-installer-bootstrap/releases/"
        "download/v0.0.3/packwiz-installer-bootstrap.jar"
    )
    server_dir: Path = Path("../Custom Mods/server")
    include_paths: list[str] = field(default_factory=list)
    preserve_globs: list[str] = field(default_factory=list)
    shared_config_paths: list[str] = field(default_factory=list)
    self_host_additional_globs: list[str] = field(default_factory=list)
    self_host_override_globs: list[str] | None = None
    deploy: SftpDeploy = field(default_factory=SftpDeploy)
    notify: DiscordNotify = field(default_factory=DiscordNotify)

    def effective_self_host_globs(self, client_globs: list[str]) -> list[str]:
        """Combine inherited client globs with server-only additions.

        If ``self_host_override_globs`` is set (the user uncommented
        ``allowed_globs`` in ``[packwiz_server.self_host]``), it fully
        replaces the client list. Otherwise we return client + additional.
        """
        if self.self_host_override_globs is not None:
            return list(self.self_host_override_globs)
        return list(client_globs) + list(self.self_host_additional_globs)

    @classmethod
    def from_dict(cls, raw: dict | None, config_dir: Path) -> "PackwizServerSettings":
        raw = raw or {}
        output_raw = raw.get("output_dir", "../docs/packwiz-server")
        output_dir = Path(output_raw)
        if not output_dir.is_absolute():
            output_dir = (config_dir / output_dir).resolve()
        server_raw = raw.get("server_dir", "../Custom Mods/server")
        server_dir = Path(server_raw)
        if not server_dir.is_absolute():
            server_dir = (config_dir / server_dir).resolve()

        self_host = raw.get("self_host") or {}
        # `allowed_globs` (if present) is an explicit override of the
        # inherited client list; `additional_globs` adds to it.
        override = self_host.get("allowed_globs")
        return cls(
            enabled=bool(raw.get("enabled", False)),
            output_dir=output_dir,
            base_url=str(raw.get("base_url", "")).rstrip("/"),
            author=str(raw.get("author", "")),
            pack_format=str(raw.get("pack_format", "packwiz:1.1.0")),
            bootstrap_installer_url=str(
                raw.get("bootstrap_installer_url", cls.bootstrap_installer_url)
            ),
            server_dir=server_dir,
            include_paths=list(raw.get("include_paths", []) or []),
            preserve_globs=list(raw.get("preserve_globs", []) or []),
            shared_config_paths=list(raw.get("shared_config_paths", []) or []),
            self_host_additional_globs=list(self_host.get("additional_globs", []) or []),
            self_host_override_globs=list(override) if override is not None else None,
            deploy=SftpDeploy.from_dict(raw.get("deploy")),
            notify=DiscordNotify.from_dict(raw.get("notify")),
        )
