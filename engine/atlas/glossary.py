"""Tiny Malay -> English gloss for OSM draft names, so the English UI is readable before a name is walked.

Only used when EVERY word of a name is in the glossary (proper names like "Tun Dr. Ismail" are never guessed).
Malay noun phrases put the head first ("Makmal Kejuruteraan Elektrik"); English puts it last
("Electrical Engineering Lab"), so the glossed words are reversed. Multi-word entries are matched first.
"""

from __future__ import annotations

import re

PHRASES = {
    "bilik kuliah": "lecture rooms",
    "dewan kuliah": "lecture hall",
    "bilik pensyarah": "lecturers' rooms",
    "bilik pengajar": "tutors' rooms",
    "teknologi maklumat": "information technology",
    "alam sekitar": "environmental",
    "bola keranjang": "basketball",
    "bola tampar": "volleyball",
    "sepak takraw": "sepak takraw",
    "dewan makan": "dining hall",
    "pentas terbuka": "open stage",
    "dewan terbuka": "open hall",
    "tempat peperiksaan": "exam venue",
    "kemudahan am": "general facilities",
}
WORDS = {
    "makmal": "lab", "bengkel": "workshop", "kejuruteraan": "engineering", "elektrik": "electrical",
    "mekanikal": "mechanical", "awam": "civil", "dewan": "hall", "peperiksaan": "exam", "blok": "block",
    "pejabat": "office", "akademik": "academic", "bangunan": "building", "pusat": "centre", "kabin": "cabin",
    "asrama": "hostel", "perpustakaan": "library", "automasi": "automation", "bahasa": "language",
    "fizik": "physics", "kimia": "chemistry", "struktur": "structures", "struktuk": "structures",
    "studio": "studio", "lukisan": "drawing", "stor": "store", "utama": "main", "garaj": "garage",
    "penerbitan": "publishing", "gelanggang": "court", "futsal": "futsal", "tenis": "tennis",
    "badminton": "badminton", "seminar": "seminar", "kuliah": "lecture", "pensyarah": "lecturers",
    "kafeteria": "cafeteria", "nano": "nano", "pentadbiran": "administration", "kediaman": "residences",
    "gunasama": "shared", "dan": "and", "&": "and", "kokurikulum": "co-curricular", "pengajian": "studies",
    "umum": "general", "felo": "fellows", "rumah": "house", "padang": "field", "sukan": "sports",
}
_TOKEN = re.compile(r"[A-Za-z]+|&")


def gloss(name: str) -> str | None:
    words = [w.lower() for w in _TOKEN.findall(name)]
    if not words or len(words) != len(name.replace("/", " ").split()):
        return None  # punctuation/numbers we don't understand
    out: list[str] = []
    i = 0
    while i < len(words):
        two = " ".join(words[i:i + 2])
        if two in PHRASES:
            out.append(PHRASES[two])
            i += 2
        elif words[i] in WORDS:
            out.append(WORDS[words[i]])
            i += 1
        else:
            return None
    if "and" in out:
        # "Makmal Fizik & Kimia" -> keep the coordinated pair together: "Physics and Chemistry Lab"
        k = out.index("and")
        if 0 < k < len(out) - 1:
            head = out[: k - 1]
            pair = out[k - 1: k + 2]
            tail = out[k + 2:]
            return _title(list(reversed(tail)) + pair + list(reversed(head)))
    return _title(list(reversed(out)))


def _title(parts: list[str]) -> str:
    words = " ".join(parts).split(" ")
    return " ".join(w if w == "and" else w[:1].upper() + w[1:] for w in words)
