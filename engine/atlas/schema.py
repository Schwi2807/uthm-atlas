"""JSON Schema loading and validation against the shared schema/ folder."""

from __future__ import annotations

import json
from functools import lru_cache
from pathlib import Path
from typing import Any, Iterable

from jsonschema import Draft202012Validator, FormatChecker

from . import SCHEMA_VERSION
from .paths import SCHEMA_DIR

SCHEMAS = {
    "feature": "feature.schema.json",
    "manifest": "survey-manifest.schema.json",
    "track": "track.schema.json",
    "bundle": "bundle.schema.json",
    "curation": "curation.schema.json",
}


class SchemaError(ValueError):
    """Raised with every violation listed, so a bad file can be fixed in one pass."""

    def __init__(self, what: str, errors: list[str]):
        self.what = what
        self.errors = errors
        shown = "\n  ".join(errors[:20])
        more = f"\n  ... and {len(errors) - 20} more" if len(errors) > 20 else ""
        super().__init__(f"{what}: {len(errors)} schema violation(s)\n  {shown}{more}")


@lru_cache(maxsize=None)
def load(name: str, schema_dir: Path = SCHEMA_DIR) -> dict:
    schema = json.loads((schema_dir / SCHEMAS[name]).read_text(encoding="utf-8"))
    version = schema.get("x-schema-version")
    if version != SCHEMA_VERSION:
        raise RuntimeError(f"{SCHEMAS[name]} is schema version {version}, engine expects {SCHEMA_VERSION}")
    return schema


@lru_cache(maxsize=None)
def validator(name: str) -> Draft202012Validator:
    schema = load(name)
    Draft202012Validator.check_schema(schema)
    return Draft202012Validator(schema, format_checker=FormatChecker())


def errors(name: str, doc: Any) -> list[str]:
    out = []
    for e in sorted(validator(name).iter_errors(doc), key=lambda e: list(e.absolute_path)):
        where = "/".join(str(p) for p in e.absolute_path) or "(root)"
        out.append(f"{where}: {e.message}")
    return out


def check(name: str, doc: Any, what: str | None = None) -> None:
    errs = errors(name, doc)
    if errs:
        raise SchemaError(what or name, errs)


def check_features(features: Iterable[dict], what: str) -> None:
    errs = []
    for i, f in enumerate(features):
        label = f.get("properties", {}).get("name_en") or f.get("id") or f"#{i}"
        errs += [f"feature {i} ({label}): {m}" for m in errors("feature", f)]
    if errs:
        raise SchemaError(what, errs)
