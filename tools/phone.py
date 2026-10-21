#!/usr/bin/env python3
"""Tiny adb helper for driving the Surveyor in tests: tap UI elements by their visible text, type, screenshot.

    python tools/phone.py tap "CAPTURE"        # first element whose text/desc contains it (case-insensitive)
    python tools/phone.py type "Main entrance"
    python tools/phone.py shot out.png
    python tools/phone.py texts                # list visible texts
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ADB = os.environ.get("ADB") or str(Path(os.environ.get("LOCALAPPDATA", "")) / "Android/Sdk/platform-tools/adb.exe")


def adb(*args: str, binary: bool = False):
    r = subprocess.run([ADB, *args], capture_output=True)
    if r.returncode:
        raise SystemExit(r.stderr.decode(errors="replace"))
    return r.stdout if binary else r.stdout.decode(errors="replace")


def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/atlas-ui.xml")
    xml = adb("shell", "cat", "/sdcard/atlas-ui.xml")
    root = ET.fromstring(xml[xml.index("<"):])
    for n in root.iter("node"):
        text = (n.get("text") or "") + " " + (n.get("content-desc") or "")
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
        if m and text.strip():
            x1, y1, x2, y2 = map(int, m.groups())
            yield text.strip(), ((x1 + x2) // 2, (y1 + y2) // 2), n.get("clickable") == "true"


def tap(label: str) -> None:
    want = label.lower()
    for text, (x, y), _ in nodes():
        if want in text.lower():
            adb("shell", "input", "tap", str(x), str(y))
            time.sleep(0.8)
            return
    raise SystemExit(f"no element with text {label!r}")


def main() -> None:
    cmd, *rest = sys.argv[1:]
    if cmd == "tap":
        tap(rest[0])
    elif cmd == "type":
        adb("shell", "input", "text", rest[0].replace(" ", "%s"))
    elif cmd == "key":
        adb("shell", "input", "keyevent", rest[0])
    elif cmd == "shot":
        Path(rest[0]).write_bytes(adb("exec-out", "screencap", "-p", binary=True))
    elif cmd == "texts":
        for text, xy, click in nodes():
            print(("*" if click else " "), xy, text[:80])
    else:
        raise SystemExit(__doc__)


if __name__ == "__main__":
    main()
