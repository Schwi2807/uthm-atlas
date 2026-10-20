"""Search index terms: names (EN + BM), codes, aliases, rooms. The site does the fuzzy matching."""

from __future__ import annotations

import re
import unicodedata

WEIGHT_CODE, WEIGHT_NAME, WEIGHT_ALIAS = 3, 2, 1
_CODE = re.compile(r"^([A-Za-z]{1,3})\s*-?\s*(\d{1,3}[A-Za-z]?)$")
_ACRONYM = re.compile(r"^[A-Z][A-Z0-9&]{1,7}$")


def normalize(s: str) -> str:
    """Lower-case, strip accents, punctuation -> space, collapse spaces. Mirrored in site/js/search.js."""
    s = unicodedata.normalize("NFKD", s)
    s = "".join(ch for ch in s if not unicodedata.combining(ch)).lower()
    s = re.sub(r"[^a-z0-9]+", " ", s)
    return s.strip()


def code_variants(code: str) -> list[tuple[str, int]]:
    """G3 -> g3, g 3, blok g3, block g3 (code weight) and blok g, block g (alias weight)."""
    out = [(normalize(code), WEIGHT_CODE)]
    m = _CODE.match(code.strip())
    if m:
        letters, num = m.group(1).lower(), m.group(2).lower()
        out += [
            (f"{letters} {num}", WEIGHT_CODE),
            (f"blok {letters}{num}", WEIGHT_CODE),
            (f"block {letters}{num}", WEIGHT_CODE),
            (f"blok {letters}", WEIGHT_ALIAS),
            (f"block {letters}", WEIGHT_ALIAS),
        ]
    return out


def place_terms(place: dict) -> list[tuple[str, int]]:
    terms: list[tuple[str, int]] = []
    if place.get("code"):
        terms += code_variants(place["code"])
    for key in ("en", "ms"):
        if place.get(key):
            terms.append((normalize(place[key]), WEIGHT_NAME))
    for a in place.get("al") or []:
        terms.append((normalize(a), WEIGHT_CODE if _ACRONYM.match(a.strip()) else WEIGHT_ALIAS))
    for room in place.get("rooms") or []:
        name, code = room[0], room[1]
        terms.append((normalize(name), WEIGHT_ALIAS))
        if code:
            terms.append((normalize(code), WEIGHT_ALIAS))
    # keep the best weight per term
    best: dict[str, int] = {}
    for t, w in terms:
        if t and w > best.get(t, 0):
            best[t] = w
    return sorted(best.items())


def index(places: list[dict]) -> list[list]:
    out = []
    for i, p in enumerate(places):
        for term, w in place_terms(p):
            out.append([term, i, w])
    out.sort(key=lambda r: (r[0], r[1]))
    return out
