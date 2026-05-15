from __future__ import annotations

import os
import tomllib
from dataclasses import dataclass, field
from pathlib import Path


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
    extra_ignore: list[str]
    optional_files: list[str]
    modrinth_pat: str | None

    @property
    def minecraft_dir(self) -> Path:
        return self.instance_path / "minecraft"

    @property
    def state_file(self) -> Path:
        return self.config_dir / ".last-published-state.json"

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


def load_config(config_dir: Path | None = None) -> Config:
    config_dir = (config_dir or Path.cwd()).resolve()

    base = _load_toml(config_dir / "config.toml")
    local = _load_toml(config_dir / "config.local.toml")

    if not base and not local:
        raise ConfigError(
            f"No config.toml or config.local.toml found in {config_dir}. "
            "Copy config.example.toml to config.toml to get started."
        )

    merged: dict = {**base, **local}

    instance_path_raw = merged.get("instance_path")
    if not instance_path_raw:
        raise ConfigError("`instance_path` is required in config.toml.")
    instance_path = Path(instance_path_raw)
    if not instance_path.is_absolute():
        instance_path = (config_dir / instance_path).resolve()

    pat = os.environ.get(PAT_ENV_VAR) or merged.get("modrinth_pat") or None

    return Config(
        config_dir=config_dir,
        instance_path=instance_path,
        name=merged.get("name") or instance_path.name,
        summary=merged.get("summary", ""),
        project_id=merged.get("project_id", ""),
        user_agent=merged.get("user_agent", ""),
        include_paths=list(merged.get("include_paths", [])),
        extra_ignore=list(merged.get("extra_ignore", [])),
        optional_files=list(merged.get("optional_files", [])),
        modrinth_pat=pat,
    )
