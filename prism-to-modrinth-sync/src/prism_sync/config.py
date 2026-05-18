from __future__ import annotations

import os
import tomllib
from dataclasses import dataclass, field
from pathlib import Path

from .customs import CustomModConfig, VALID_SIDES
from .packwiz_settings import PackwizServerSettings, PackwizSettings


PAT_ENV_VAR = "MODRINTH_PAT"


@dataclass
class Config:
    config_dir: Path
    instance_path: Path
    name: str
    summary: str
    project_id: str
    user_agent: str
    include_paths: list[str]
    include_files: list[str]
    extra_ignore: list[str]
    optional_files: list[str]
    custom_mods: list[CustomModConfig]
    modrinth_pat: str | None
    packwiz: PackwizSettings
    packwiz_server: PackwizServerSettings

    @property
    def minecraft_dir(self) -> Path:
        return self.instance_path / "minecraft"

    @property
    def state_file(self) -> Path:
        return self.config_dir / ".last-published-state.json"

    @property
    def server_state_file(self) -> Path:
        return self.config_dir / ".last-published-state-server.json"

    def require_pat(self) -> str:
        if not self.modrinth_pat:
            raise ConfigError(
                f"Modrinth PAT not set. Provide via the {PAT_ENV_VAR} environment "
                "variable or `modrinth_pat` in config.local.toml."
            )
        return self.modrinth_pat

    def require_project_id(self) -> str:
        if not self.project_id:
            raise ConfigError(
                "`project_id` is not set in config. Required for `sync publish`."
            )
        return self.project_id


class ConfigError(Exception):
    pass


def _load_toml(path: Path) -> dict:
    if not path.is_file():
        return {}
    with path.open("rb") as fh:
        return tomllib.load(fh)


def _deep_merge(base: dict, override: dict) -> dict:
    """Recursively merge ``override`` into ``base``, returning a new dict.

    Tables merge key-by-key; non-table values in ``override`` replace those in
    ``base``. Lists are NOT merged (override wins) — TOML arrays are usually
    whole-value choices (e.g. ``include_paths``) where partial-override would
    surprise.

    Used so that ``config.local.toml`` can supply just the secrets for a
    nested table like ``[packwiz_server.deploy]`` (host/user/password_env)
    without having to repeat the public-config fields (``remote_dir``,
    ``bootstrap_pull``, ``wrapper_push``).
    """
    out = dict(base)
    for key, value in override.items():
        existing = out.get(key)
        if isinstance(existing, dict) and isinstance(value, dict):
            out[key] = _deep_merge(existing, value)
        else:
            out[key] = value
    return out


def load_config(config_dir: Path | None = None) -> Config:
    config_dir = (config_dir or Path.cwd()).resolve()

    base = _load_toml(config_dir / "config.toml")
    local = _load_toml(config_dir / "config.local.toml")

    if not base and not local:
        raise ConfigError(
            f"No config.toml or config.local.toml found in {config_dir}. "
            "Copy config.example.toml to config.toml to get started."
        )

    merged: dict = _deep_merge(base, local)

    instance_path_raw = merged.get("instance_path")
    if not instance_path_raw:
        raise ConfigError("`instance_path` is required in config.toml.")
    instance_path = Path(instance_path_raw)
    if not instance_path.is_absolute():
        instance_path = (config_dir / instance_path).resolve()

    pat = os.environ.get(PAT_ENV_VAR) or merged.get("modrinth_pat") or None

    custom_mods: list[CustomModConfig] = []
    for entry in merged.get("custom_mods", []) or []:
        if not isinstance(entry, dict):
            raise ConfigError(
                f"[[custom_mods]] entries must be tables; got: {entry!r}"
            )
        name = entry.get("name")
        source_dir = entry.get("source_dir")
        if not name or not source_dir:
            raise ConfigError(
                "Each [[custom_mods]] entry needs `name` and `source_dir`."
            )
        side = entry.get("side", "both")
        if side not in VALID_SIDES:
            raise ConfigError(
                f"[[custom_mods]] entry {name!r}: invalid `side` value "
                f"{side!r}. Must be one of: {', '.join(VALID_SIDES)}."
            )
        custom_mods.append(
            CustomModConfig(
                name=name,
                source_dir=Path(source_dir),
                target_dir=entry.get("target_dir", "mods"),
                source_pattern=entry.get("source_pattern", ""),
                side=side,
            )
        )

    return Config(
        config_dir=config_dir,
        instance_path=instance_path,
        name=merged.get("name") or instance_path.name,
        summary=merged.get("summary", ""),
        project_id=merged.get("project_id", ""),
        user_agent=merged.get("user_agent", ""),
        include_paths=list(merged.get("include_paths", [])),
        include_files=list(merged.get("include_files", [])),
        extra_ignore=list(merged.get("extra_ignore", [])),
        optional_files=list(merged.get("optional_files", [])),
        custom_mods=custom_mods,
        modrinth_pat=pat,
        packwiz=PackwizSettings.from_dict(merged.get("packwiz"), config_dir),
        packwiz_server=PackwizServerSettings.from_dict(
            merged.get("packwiz_server"), config_dir
        ),
    )
