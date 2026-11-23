#!/usr/bin/env python3
"""Tests of publish-codefloe-maven.py, against a fake package registry on localhost.

    python3 -I .forgejo/scripts/publish-codefloe-maven-test.py
"""
import base64
import importlib.util
import io
import json
import os
import pathlib
import sys
import tempfile
import threading
import unittest
import zipfile
from contextlib import redirect_stdout
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.dont_write_bytecode = True  # no __pycache__ next to the scripts

HERE = pathlib.Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("publish", HERE / "publish-codefloe-maven.py")
publish = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(publish)

AUTH = "Basic " + base64.b64encode(b"bot:secret").decode()


class Registry:
    """What the fake registry received, and what it answers."""

    def __init__(self):
        self.requests = []          # (method, path, body)
        self.existing = set()       # paths that answer 409
        self.fail_once = set()      # paths that answer 500 the first time
        self.packages = []          # what GET /api/v1/packages/<owner> lists


def handler_for(registry):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def answer(self, status, body=b""):
            self.send_response(status)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_PUT(self):
            body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
            registry.requests.append(("PUT", self.path, body))
            if self.headers.get("Authorization") != AUTH:
                return self.answer(401)
            # Forgejo reads a form-encoded body as a form, and its POM parser then finds nothing: 400 "EOF".
            if self.headers.get("Content-Type") != "application/octet-stream":
                return self.answer(400, b"EOF")
            if self.path in registry.fail_once:
                registry.fail_once.discard(self.path)
                return self.answer(500)
            if self.path in registry.existing:
                return self.answer(409)
            registry.existing.add(self.path)
            self.answer(201)

        def do_GET(self):
            registry.requests.append(("GET", self.path, b""))
            self.answer(200, json.dumps(registry.packages).encode())

        def do_DELETE(self):
            registry.requests.append(("DELETE", self.path, b""))
            self.answer(204)

    return Handler


class PublishTest(unittest.TestCase):

    def setUp(self):
        self.registry = Registry()
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), handler_for(self.registry))
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.tmp.name)
        self.env = {
            "CODEFLOE_URL": "http://127.0.0.1:%d" % self.server.server_port,
            "CODEFLOE_OWNER": "Vidocq",
            "CODEFLOE_USER": "bot",
            "CODEFLOE_TOKEN": "secret",
        }
        self.saved = dict(os.environ)
        os.environ.update(self.env)
        publish.RETRY_PAUSE_SECONDS = 0

    def tearDown(self):
        os.environ.clear()
        os.environ.update(self.saved)
        self.server.shutdown()
        self.server.server_close()
        self.tmp.cleanup()

    def bundle(self, version="0.4.0", extra=()):
        """A central-publishing bundle, where release.sh leaves it."""
        directory = self.root / "target" / "central-publishing"
        directory.mkdir(parents=True)
        path = directory / "central-bundle.zip"
        base = "io/vidocq/chappe-api/%s/chappe-api-%s" % (version, version)
        with zipfile.ZipFile(path, "w") as z:
            z.writestr("io/vidocq/chappe-api/%s/" % version, b"")
            for suffix in (".pom", ".jar", ".jar.asc", "-sources.jar", "-cyclonedx.json", ".pom.md5", ".jar.sha1",
                           ".jar.sha256", ".jar.sha512"):
                z.writestr(base + suffix, ("content of " + suffix).encode())
            z.writestr("io/vidocq/chappe-api/maven-metadata.xml", b"<metadata/>")
            # What central-publishing 0.10.0 puts in its bundle besides the artifacts (seen in a local bundle).
            z.writestr("io/vidocq/chappe-api/maven-metadata-local.xml", b"<metadata/>")
            z.writestr("io/vidocq/chappe-api/%s/_remote.repositories" % version, b"#")
            for name in extra:
                z.writestr(name, b"x")
        return path

    def run_main(self, *args):
        out = io.StringIO()
        with redirect_stdout(out):
            code = publish.main(list(args))
        return code, out.getvalue()

    def puts(self):
        return sorted(path for method, path, _ in self.registry.requests if method == "PUT")

    def test_publish_uploads_the_signed_files_and_skips_the_checksums_and_metadata(self):
        self.bundle()
        code, _ = self.run_main("publish", "--root", str(self.root), "--version", "0.4.0")
        self.assertEqual(0, code)
        prefix = "/api/packages/Vidocq/maven/io/vidocq/chappe-api/0.4.0/chappe-api-0.4.0"
        self.assertEqual(sorted(prefix + s for s in (".pom", ".jar", ".jar.asc", "-sources.jar", "-cyclonedx.json")),
                         self.puts())
        body = {path: body for method, path, body in self.registry.requests if method == "PUT"}
        self.assertEqual(b"content of .jar.asc", body[prefix + ".jar.asc"])

    def test_a_file_already_in_the_registry_is_not_an_error(self):
        self.bundle()
        self.registry.existing.add("/api/packages/Vidocq/maven/io/vidocq/chappe-api/0.4.0/chappe-api-0.4.0.jar")
        code, out = self.run_main("publish", "--root", str(self.root), "--version", "0.4.0")
        self.assertEqual(0, code)
        self.assertIn("4 uploaded, 1 already there", out)

    def test_a_server_error_is_tried_again(self):
        self.bundle()
        path = "/api/packages/Vidocq/maven/io/vidocq/chappe-api/0.4.0/chappe-api-0.4.0.pom"
        self.registry.fail_once.add(path)
        code, _ = self.run_main("publish", "--root", str(self.root), "--version", "0.4.0")
        self.assertEqual(0, code)
        self.assertEqual(2, sum(1 for method, p, _ in self.registry.requests if method == "PUT" and p == path))

    def test_a_refused_upload_fails_the_step(self):
        self.bundle()
        os.environ["CODEFLOE_TOKEN"] = "wrong"
        code, out = self.run_main("publish", "--root", str(self.root), "--version", "0.4.0")
        self.assertEqual(1, code)
        self.assertIn("401", out)

    def test_a_file_of_another_version_stops_before_any_upload(self):
        self.bundle(extra=["io/vidocq/chappe-http/0.4.1/chappe-http-0.4.1.jar"])
        code, out = self.run_main("publish", "--root", str(self.root), "--version", "0.4.0")
        self.assertEqual(1, code)
        self.assertIn("chappe-http-0.4.1.jar", out)
        self.assertEqual([], self.puts())

    def test_a_dry_run_lists_the_files_and_uploads_nothing(self):
        self.bundle()
        code, out = self.run_main("publish", "--root", str(self.root), "--version", "0.4.0", "--dry-run")
        self.assertEqual(0, code)
        self.assertIn("0.4.0: 5 files would be uploaded", out)
        self.assertEqual([], self.registry.requests)

    def test_no_bundle_fails(self):
        code, out = self.run_main("publish", "--root", str(self.root), "--version", "0.4.0")
        self.assertEqual(1, code)
        self.assertIn("no central-publishing bundle", out)

    def test_the_probe_uploads_a_file_then_deletes_its_package(self):
        self.registry.packages = [{"name": "io.vidocq.ci:codefloe-probe", "version": "0.0.0-probe-7"}]
        code, _ = self.run_main("probe", "--stamp", "7")
        self.assertEqual(0, code)
        self.assertEqual(["/api/packages/Vidocq/maven/io/vidocq/ci/codefloe-probe/0.0.0-probe-7/"
                          "codefloe-probe-0.0.0-probe-7.pom"], self.puts())
        deletes = [path for method, path, _ in self.registry.requests if method == "DELETE"]
        self.assertEqual(["/api/v1/packages/Vidocq/maven/io.vidocq.ci%3Acodefloe-probe/0.0.0-probe-7"], deletes)

    def test_a_probe_the_registry_refuses_fails(self):
        os.environ["CODEFLOE_TOKEN"] = "wrong"
        code, out = self.run_main("probe", "--stamp", "7")
        self.assertEqual(1, code)
        self.assertIn("401", out)


if __name__ == "__main__":
    unittest.main()
