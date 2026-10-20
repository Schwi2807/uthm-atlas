"""Photo pipeline: source JPEG/PNG -> resized WebP with every metadata block removed.

Re-encoding from decoded pixels is what strips metadata: the new file is written without exif/xmp/icc/comment, and
`verify_clean` re-opens it to prove that. Output names are the first 12 hex digits of the source's sha256, so
re-running a build never duplicates photos and nothing in the name points back at the device or person.
"""

from __future__ import annotations

import hashlib
import io
from dataclasses import dataclass
from pathlib import Path

from PIL import Image, ImageOps

META_KEYS = ("exif", "xmp", "XML:com.adobe.xmp", "icc_profile", "comment", "photoshop", "iptc")


class PhotoError(RuntimeError):
    pass


@dataclass
class Processed:
    source: Path
    sha256: str
    out_name: str  # photos/<12 hex>.webp
    width: int
    height: int
    bytes: int
    quality: int


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _encode(img: Image.Image, quality: int) -> bytes:
    buf = io.BytesIO()
    # No exif=, icc_profile= or xmp= arguments: nothing but pixels goes in.
    img.save(buf, "WEBP", quality=quality, method=6)
    return buf.getvalue()


def verify_clean(data: bytes) -> None:
    with Image.open(io.BytesIO(data)) as im:
        found = [k for k in META_KEYS if im.info.get(k)]
        if len(im.getexif()):
            found.append("exif-tags")
    if found:
        raise PhotoError(f"metadata survived re-encode: {found}")
    # Belt and braces: no RIFF chunk for EXIF / XMP / ICC in the container.
    for chunk in (b"EXIF", b"XMP ", b"ICCP"):
        if _is_chunk(data, chunk):
            raise PhotoError(f"RIFF {chunk!r} chunk present")


def _is_chunk(data: bytes, fourcc: bytes) -> bool:
    """Walk the RIFF chunk list (WebP is RIFF) and report whether fourcc is a real chunk id."""
    pos = 12
    while pos + 8 <= len(data):
        cid = data[pos:pos + 4]
        size = int.from_bytes(data[pos + 4:pos + 8], "little")
        if cid == fourcc:
            return True
        pos += 8 + size + (size & 1)
    return False


def process(src: Path, out_dir: Path, *, max_px: int = 1280, quality: int = 72, min_quality: int = 40,
            budget_bytes: int = 150 * 1024) -> Processed:
    digest = sha256_file(src)
    name = f"photos/{digest[:12]}.webp"
    with Image.open(src) as im:
        im = ImageOps.exif_transpose(im)  # bake the orientation in before EXIF is dropped
        im = im.convert("RGB")
        size = max_px
        q = quality
        while True:
            frame = im.copy()
            frame.thumbnail((size, size), Image.Resampling.LANCZOS)
            data = _encode(frame, q)
            if len(data) <= budget_bytes:
                break
            if q > min_quality:
                q = max(min_quality, q - 8)
            elif size > 480:
                size = int(size * 0.85)
            else:
                raise PhotoError(f"{src.name}: cannot get under {budget_bytes // 1024} KB")
    verify_clean(data)
    out = out_dir / name
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(data)
    return Processed(src, digest, name, frame.width, frame.height, len(data), q)
