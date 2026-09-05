#!/usr/bin/env python3
"""Captive-portal helper: answers every HTTP request on :80 with a redirect to
the Dock's setup page. Run as root by repaper-portal.service, only while the
setup hotspot is up. Together with the dnsmasq wildcard, this makes phones pop
their captive sheet straight onto the setup UI."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TARGET = "http://10.42.0.1:9631/setup"

class Redirect(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def _go(self):
        self.send_response(302)
        self.send_header("Location", TARGET)
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
    do_GET = do_POST = do_HEAD = _go

ThreadingHTTPServer(("0.0.0.0", 80), Redirect).serve_forever()
