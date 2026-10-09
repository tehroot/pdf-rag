#!/usr/bin/env python3
"""Read-only HTTP server for ISOs, with Range support (CIMC vMedia reads ISOs by byte range).

usage: copy next to the ISOs, then  python3 serve.py [port]   (default 8099)
Serves only the *.iso files in this file's directory, listed at startup: restart it after you add an ISO.
HTTP/1.1 is required: the CIMC's httpfs2 client fails with "HEAD: reply does not contain status" on 1.0.
"""
import os, re, sys
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

ROOT = os.path.dirname(os.path.abspath(__file__))
FILES = {f for f in os.listdir(ROOT) if f.endswith('.iso')}

class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def _target(self):
        name = self.path.lstrip('/').split('?')[0]
        return os.path.join(ROOT, name) if name in FILES else None

    def _head(self, send_body):
        p = self._target()
        if not p:
            self.send_error(404); return
        size = os.path.getsize(p)
        start, end, code = 0, size - 1, 200
        m = re.match(r'bytes=(\d*)-(\d*)$', self.headers.get('Range', ''))
        if m and (m.group(1) or m.group(2)):
            if m.group(1):
                start = int(m.group(1)); end = int(m.group(2)) if m.group(2) else size - 1
            else:
                start = max(0, size - int(m.group(2)))
            end = min(end, size - 1)
            if start > end:
                self.send_response(416); self.send_header('Content-Range', f'bytes */{size}'); self.end_headers(); return
            code = 206
        self.send_response(code)
        self.send_header('Content-Type', 'application/octet-stream')
        self.send_header('Accept-Ranges', 'bytes')
        self.send_header('Content-Length', str(end - start + 1))
        if code == 206:
            self.send_header('Content-Range', f'bytes {start}-{end}/{size}')
        self.end_headers()
        if send_body:
            with open(p, 'rb') as f:
                f.seek(start); left = end - start + 1
                while left > 0:
                    chunk = f.read(min(1 << 20, left))
                    if not chunk: break
                    self.wfile.write(chunk); left -= len(chunk)

    def do_HEAD(self): self._head(False)
    def do_GET(self): self._head(True)

ThreadingHTTPServer(('0.0.0.0', int(sys.argv[1]) if len(sys.argv) > 1 else 8099), H).serve_forever()
