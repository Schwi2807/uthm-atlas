"""Well-known repo locations. The engine always runs from inside the monorepo."""

from pathlib import Path

ENGINE = Path(__file__).resolve().parent.parent
REPO = ENGINE.parent
SCHEMA_DIR = REPO / "schema"
SITE = REPO / "site"
SITE_DATA = SITE / "data"
SURVEYS = REPO / "surveys"
OVERRIDES = ENGINE / "overrides"
CACHE = ENGINE / ".cache"
WORK = ENGINE / "work"  # ingested, merged survey state (git-tracked: survey.geojson)
