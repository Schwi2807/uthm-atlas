"""engine/atlas.toml loader."""

from __future__ import annotations

import tomllib
from dataclasses import dataclass
from pathlib import Path

from .paths import ENGINE


@dataclass
class Config:
    raw: dict
    path: Path

    def __getitem__(self, key):
        return self.raw[key]

    @property
    def campus(self) -> dict:
        return self.raw["campus"]

    @property
    def core_bbox(self) -> list[float]:
        return list(self.campus["core_bbox"])

    @property
    def bbox(self) -> list[float]:
        """Core bbox grown by the margin: what footprints, suggestions and the basemap cover."""
        w, s, e, n = self.core_bbox
        m = float(self.campus.get("margin_deg", 0))
        return [round(w - m, 6), round(s - m, 6), round(e + m, 6), round(n + m, 6)]

    def dedupe_radius(self, kind: str) -> float:
        return float(self.raw["survey"]["dedupe_radius_m"].get(kind, 5.0))


def load(path: Path | None = None) -> Config:
    path = path or ENGINE / "atlas.toml"
    with open(path, "rb") as f:
        return Config(tomllib.load(f), path)
