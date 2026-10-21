"""atlas preview: static server for site/ with HTTP Range support (PMTiles reads byte ranges).

Python's SimpleHTTPRequestHandler ignores Range; this one answers single ranges with 206 like GitHub Pages does.
"""

from __future__ import annotations

import functools
import gzip
import http.server
import io
import os
import re
import socketserver
from pathlib import Path

_RANGE = re.compile(r"bytes=(\d*)-(\d*)$")


class RangeHandler(http.server.SimpleHTTPRequestHandler):
    extensions_map = {
        **http.server.SimpleHTTPRequestHandler.extensions_map,
        ".webmanifest": "application/manifest+json",
        ".js": "text/javascript",
        ".mjs": "text/javascript",
        ".json": "application/json",
        ".pmtiles": "application/octet-stream",
        ".bundle": "application/octet-stream",
        ".webp": "image/webp",
        ".pbf": "application/x-protobuf",
        ".svg": "image/svg+xml",
    }

    def log_message(self, fmt, *args):  # quieter: only errors
        if args and str(args[1]).startswith(("4", "5")):
            super().log_message(fmt, *args)

    def end_headers(self):
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Cache-Control", "no-cache")
        super().end_headers()

    GZIP_TYPES = (".html", ".js", ".mjs", ".css", ".json", ".svg", ".webmanifest", ".geojson")

    def send_head(self):
        rng = self.headers.get("Range")
        path = Path(self.translate_path(self.path))
        if path.is_dir():
            path = path / "index.html"
        # gzip text like GitHub Pages does, so local Lighthouse numbers are realistic
        if not rng and path.is_file() and path.suffix in self.GZIP_TYPES and "gzip" in self.headers.get("Accept-Encoding", ""):
            body = gzip.compress(path.read_bytes(), 6)
            self.send_response(200)
            self.send_header("Content-Type", self.guess_type(str(path)))
            self.send_header("Content-Encoding", "gzip")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            return io.BytesIO(body)
        if not rng or not path.is_file():
            return super().send_head()
        m = _RANGE.match(rng.strip())
        size = path.stat().st_size
        if not m or (m.group(1) == "" and m.group(2) == ""):
            self.send_error(416, "Bad range")
            return None
        if m.group(1) == "":  # suffix range: last N bytes
            start, end = max(0, size - int(m.group(2))), size - 1
        else:
            start = int(m.group(1))
            end = min(int(m.group(2)) if m.group(2) else size - 1, size - 1)
        if start >= size or start > end:
            self.send_response(416)
            self.send_header("Content-Range", f"bytes */{size}")
            self.end_headers()
            return None
        f = open(path, "rb")
        f.seek(start)
        self.send_response(206)
        self.send_header("Content-Type", self.guess_type(str(path)))
        self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.send_header("Content-Length", str(end - start + 1))
        self.end_headers()
        self._remaining = end - start + 1
        return f

    def copyfile(self, source, outputfile):
        remaining = getattr(self, "_remaining", None)
        if remaining is None:
            return super().copyfile(source, outputfile)
        while remaining > 0:
            chunk = source.read(min(64 * 1024, remaining))
            if not chunk:
                break
            outputfile.write(chunk)
            remaining -= len(chunk)
        self._remaining = None


class Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def serve(root: Path, port: int = 8000, host: str = "127.0.0.1") -> None:
    handler = functools.partial(RangeHandler, directory=os.fspath(root))
    with Server((host, port), handler) as httpd:
        print(f"Serving {root} at http://{host}:{port}/  (Ctrl+C to stop)")
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            pass
