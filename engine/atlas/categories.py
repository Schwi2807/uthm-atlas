"""Bilingual category table shipped in the bundle. Keep ids in sync with schema/feature.schema.json."""

from __future__ import annotations

# id: (en, ms, group, icon, colour, chip)
CATEGORIES: dict[str, tuple[str, str, str, str, str, bool]] = {
    "faculty":        ("Faculty", "Fakulti", "building", "school", "#1D4ED8", True),
    "lecture_hall":   ("Lecture hall", "Dewan kuliah", "study", "lectern", "#1D4ED8", False),
    "admin":          ("Administration", "Pentadbiran", "building", "briefcase", "#5B21B6", False),
    "library":        ("Library", "Perpustakaan", "study", "book", "#7E22CE", True),
    "hall":           ("Hall", "Dewan", "building", "hall", "#C2410C", False),
    "sports":         ("Sports", "Sukan", "building", "ball", "#15803D", False),
    "residential":    ("Residential college", "Kolej kediaman", "living", "bed", "#B45309", True),
    "clinic":         ("Clinic", "Klinik", "services", "cross", "#DC2626", False),
    "mosque":         ("Mosque", "Masjid", "faith", "dome", "#0F766E", False),
    "building_other": ("Building", "Bangunan", "building", "building", "#475569", False),
    # OSM drafts whose name says nothing about what the building is: shown so they can be checked on foot
    "building_unconfirmed": ("Unconfirmed building", "Bangunan belum disahkan", "building", "question", "#9CA3AF", True),
    "entrance":       ("Entrance", "Pintu masuk", "access", "door", "#111827", False),
    "cafe":           ("Café", "Kafe", "food", "cup", "#EA580C", True),
    "surau":          ("Surau", "Surau", "faith", "dome", "#0F766E", True),
    "atm":            ("ATM", "ATM", "services", "cash", "#0E7490", True),
    "printing":       ("Printing", "Cetak", "services", "printer", "#4338CA", True),
    "bus_stop":       ("Bus stop", "Hentian bas", "transport", "bus", "#CA8A04", True),
    "parking":        ("Parking", "Tempat letak kereta", "transport", "parking", "#2563EB", True),
    "toilet":         ("Toilet", "Tandas", "services", "toilet", "#0891B2", True),
    "kolej_block":    ("College block", "Blok kolej", "living", "bed", "#B45309", False),
    "lecture_room":   ("Lecture room", "Bilik kuliah", "study", "lectern", "#1D4ED8", False),
    "lab":            ("Lab", "Makmal", "study", "flask", "#7C3AED", False),
    "shop":           ("Shop", "Kedai", "food", "bag", "#DB2777", False),
    "office":         ("Office", "Pejabat", "services", "briefcase", "#5B21B6", False),
    "landmark":       ("Landmark", "Mercu tanda", "other", "flag", "#334155", False),
    "ramp":           ("Ramp", "Tanjakan", "access", "ramp", "#16A34A", False),
    "lift":           ("Lift", "Lif", "access", "lift", "#16A34A", False),
    "poi_other":      ("Place", "Tempat", "other", "pin", "#475569", False),
    "path_note":      ("Path note", "Nota laluan", "other", "note", "#64748B", False),
}


def table() -> list[dict]:
    return [
        {"id": cid, "en": en, "ms": ms, "group": group, "icon": icon, "color": color, "chip": chip}
        for cid, (en, ms, group, icon, color, chip) in CATEGORIES.items()
    ]
