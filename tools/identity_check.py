#!/usr/bin/env python3
"""Identity check: the only author identity allowed anywhere in this repo is Schwi2807 (or Shizuka).

Checks every commit's author/committer and every tracked text file for:
  - names you pass in ATLAS_FORBIDDEN_NAMES (comma-separated; kept out of the repo on purpose),
  - home-directory paths (C:\\Users\\..., /Users/..., /home/...) that leak an account name,
  - e-mail addresses other than GitHub no-reply / example.com / noreply@anthropic.com.

Usage:  python tools/identity_check.py            (exit 1 on any finding)
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ALLOWED_PEOPLE = {"schwi2807", "shizuka", "github", "github-actions[bot]", "claude"}
ALLOWED_MAIL = re.compile(r"(users\.noreply\.github\.com|noreply@github\.com|noreply@anthropic\.com|@example\.(com|org|net)$)", re.I)
# an account name starts with a letter/digit, so "C:\Users\..." in this docstring and regex text don't match
HOME_PATH = re.compile(r"([A-Za-z]:[\\/]+Users[\\/]+[A-Za-z0-9_~][^\\/\s\"',)]*|/Users/[A-Za-z0-9_][^/\s\"',)]*|/home/[A-Za-z0-9_][^/\s\"',)]*)")
EMAIL = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
TEXT_EXT = {".py", ".js", ".mjs", ".json", ".geojson", ".md", ".txt", ".toml", ".yml", ".yaml", ".html", ".css",
            ".kt", ".kts", ".xml", ".gradle", ".properties", ".pro", ".csv", ".svg", ".webmanifest", ".ps1", ".bat"}
SKIP = ("site/vendor/", "site/map/", "site/fonts/", "collector/gradle/wrapper/")


def git(*args: str) -> str:
    return subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True, encoding="utf-8", check=True).stdout


def main() -> int:
    findings: list[str] = []
    forbidden = [n.strip().lower() for n in os.environ.get("ATLAS_FORBIDDEN_NAMES", "").split(",") if n.strip()]

    for line in git("log", "--all", "--format=%H|%an|%ae|%cn|%ce").splitlines():
        h, an, ae, cn, ce = line.split("|")
        for who, mail in ((an, ae), (cn, ce)):
            if who.lower() not in ALLOWED_PEOPLE or not ALLOWED_MAIL.search(mail):
                findings.append(f"commit {h[:8]}: identity {who} <{mail}>")
            if any(f in who.lower() or f in mail.lower() for f in forbidden):
                findings.append(f"commit {h[:8]}: forbidden name in {who} <{mail}>")
        body = git("show", "-s", "--format=%B", h).lower()
        for f in forbidden:
            if f in body:
                findings.append(f"commit {h[:8]}: forbidden name in the message")

    for rel in git("ls-files").splitlines():
        if rel.startswith(SKIP) or Path(rel).suffix.lower() not in TEXT_EXT:
            continue
        try:
            text = (ROOT / rel).read_text(encoding="utf-8")
        except (UnicodeDecodeError, FileNotFoundError):
            continue
        low = text.lower()
        for f in forbidden:
            if f in low:
                findings.append(f"{rel}: contains a forbidden name")
        for m in HOME_PATH.finditer(text):
            findings.append(f"{rel}: home-directory path {m.group(0)!r}")
        for m in EMAIL.finditer(text):
            addr = m.group(0)
            if not ALLOWED_MAIL.search(addr) and not re.search(r"@\d?x\.(png|json)$", addr):
                findings.append(f"{rel}: e-mail address {addr}")

    if findings:
        print("identity check FAILED:")
        for f in findings:
            print("  -", f)
        return 1
    extra = f" and {len(forbidden)} forbidden name(s)" if forbidden else ""
    print(f"identity check OK: commit identities, home paths, e-mails{extra}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
