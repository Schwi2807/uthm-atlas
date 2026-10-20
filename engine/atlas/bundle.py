"""campus.bundle encode/decode. Byte layout in schema/BUNDLE_FORMAT.md.

    0  'UATL'          magic
    4  uint16 BE       schema version
    6  uint32 BE       build timestamp (Unix seconds)
   10  gzip(msgpack(payload))
"""

from __future__ import annotations

import gzip
import struct
from dataclasses import dataclass

import msgpack

from . import SCHEMA_VERSION

MAGIC = b"UATL"
HEADER = struct.Struct(">4sHI")


class BundleError(ValueError):
    pass


@dataclass
class Bundle:
    version: int
    ts: int
    payload: dict


def encode(payload: dict, ts: int, version: int = SCHEMA_VERSION) -> bytes:
    if not (0 <= ts < 2**32):
        raise BundleError(f"timestamp {ts} does not fit uint32")
    body = msgpack.packb(payload, use_bin_type=True)
    # mtime=0 keeps the gzip bytes deterministic for identical payloads
    return HEADER.pack(MAGIC, version, ts) + gzip.compress(body, compresslevel=9, mtime=0)


def read_header(data: bytes) -> tuple[int, int]:
    if len(data) < HEADER.size:
        raise BundleError("file too short for a UATL header")
    magic, version, ts = HEADER.unpack_from(data)
    if magic != MAGIC:
        raise BundleError(f"bad magic {magic!r}, expected {MAGIC!r}")
    return version, ts


def decode(data: bytes, expect_version: int | None = SCHEMA_VERSION) -> Bundle:
    version, ts = read_header(data)
    if expect_version is not None and version != expect_version:
        raise BundleError(f"bundle schema version {version}, reader supports {expect_version}")
    try:
        body = gzip.decompress(data[HEADER.size:])
    except (OSError, EOFError) as e:
        raise BundleError(f"payload is not valid gzip: {e}") from e
    payload = msgpack.unpackb(body, raw=False, strict_map_key=False)
    if not isinstance(payload, dict):
        raise BundleError("payload is not a map")
    return Bundle(version, ts, payload)
