import copy
import json

import pytest

from atlas import SCHEMA_VERSION, schema
from atlas.paths import SCHEMA_DIR

SAMPLE = json.loads((SCHEMA_DIR / "examples" / "feature-entrance.json").read_text(encoding="utf-8"))


def test_all_schemas_are_valid_and_versioned():
    for name in schema.SCHEMAS:
        doc = schema.load(name)
        assert doc["x-schema-version"] == SCHEMA_VERSION
        schema.validator(name)  # check_schema raises if the schema itself is malformed


def test_hand_written_sample_validates():
    assert schema.errors("feature", SAMPLE) == []


@pytest.mark.parametrize(
    "mutate, expect",
    [
        (lambda f: f["properties"].pop("bearing_deg"), "bearing_deg"),
        (lambda f: f["properties"].pop("capture"), "capture"),
        (lambda f: f["properties"].update(kind="tower"), "kind"),
        (lambda f: f["properties"].update(category="nightclub"), "category"),
        (lambda f: f["geometry"].update(coordinates=[200, 1.8]), "coordinates"),
        (lambda f: f["properties"].update(last_verified="20-10-2026"), "last_verified"),
        (lambda f: f.update(id="not-a-uuid"), "id"),
        (lambda f: f["properties"].update(author="someone"), "Additional properties"),
        (lambda f: f["properties"]["photos"][0].update(file="../etc/passwd"), "file"),
    ],
)
def test_violations_are_reported(mutate, expect):
    f = copy.deepcopy(SAMPLE)
    mutate(f)
    errs = schema.errors("feature", f)
    assert errs, "expected a violation"
    assert any(expect in e for e in errs), errs


def test_osm_draft_needs_no_capture():
    f = copy.deepcopy(SAMPLE)
    p = f["properties"]
    p.update(kind="building", category="faculty", source="osm", verified=False, last_verified=None)
    for k in ("capture", "bearing_deg", "parent_id"):
        p.pop(k)
    assert schema.errors("feature", f) == []


def test_check_features_lists_every_problem():
    bad1 = copy.deepcopy(SAMPLE)
    bad1["properties"]["kind"] = "x"
    bad2 = copy.deepcopy(SAMPLE)
    bad2["properties"].pop("name_en")
    with pytest.raises(schema.SchemaError) as e:
        schema.check_features([SAMPLE, bad1, bad2], "test")
    assert len(e.value.errors) >= 2
    assert "feature 1" in str(e.value) and "feature 2" in str(e.value)
