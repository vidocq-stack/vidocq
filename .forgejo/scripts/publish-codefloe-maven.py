#!/usr/bin/env python3
"""Publishes a release to the Maven package registry of Codefloe, next to Maven Central.

release.sh of Vidocq/ci deploys to Central through central-publishing-maven-plugin, which leaves the signed bundle it
uploaded in target/central-publishing/*.zip. This script sends the same files, unchanged, to
<server>/api/packages/<owner>/maven: jars, POMs, their .asc signatures and the CycloneDX SBOMs. The registry computes
the checksums and maven-metadata.xml itself, so those entries of the bundle are left out, with the local repository's
files the plugin bundles too (maven-metadata-local.xml, _remote.repositories).

    publish-codefloe-maven.py probe [--stamp S]
        uploads a throwaway POM (io.vidocq.ci:codefloe-probe:0.0.0-probe-S) and deletes its package: proves, before
        anything is released, that the token can write to the registry
    publish-codefloe-maven.py publish --root DIR --version V [--dry-run]
        uploads every file of every bundle under DIR; stops before any upload when a file is not at version V;
        --dry-run only counts them

Environment: CODEFLOE_URL (default GITHUB_SERVER_URL), CODEFLOE_OWNER (default Vidocq), CODEFLOE_USER, CODEFLOE_TOKEN.
A file the registry already holds (409) is not an error, so a failed run can be run again.
"""
import argparse
import base64
import json
import os
import pathlib
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile

SKIPPED_SUFFIXES = (".md5", ".sha1", ".sha256", ".sha512")
# central-publishing 0.10.0 also bundles the local repository's bookkeeping: maven-metadata-local.xml next to the
# versions, _remote.repositories in each version. The registry writes its own maven-metadata.xml.
SKIPPED_PREFIXES = ("maven-metadata",)
SKIPPED_FILES = ("_remote.repositories",)
ATTEMPTS = 3
RETRY_PAUSE_SECONDS = 10


def settings():
    server = os.environ.get("CODEFLOE_URL") or os.environ.get("GITHUB_SERVER_URL", "")
    return (server.rstrip("/"), os.environ.get("CODEFLOE_OWNER", "Vidocq"),
            os.environ.get("CODEFLOE_USER", ""), os.environ.get("CODEFLOE_TOKEN", ""))


def call(method, url, user, token, body=None):
    """The HTTP status of the request; 5xx and connection errors are tried again."""
    request = urllib.request.Request(url, data=body, method=method)
    request.add_header("Authorization", "Basic " + base64.b64encode(("%s:%s" % (user, token)).encode()).decode())
    if body is not None:
        # urllib's default is form-encoded: Forgejo then reads the body as a form and rejects a POM with 400 "EOF".
        request.add_header("Content-Type", "application/octet-stream")
    status = None
    for attempt in range(1, ATTEMPTS + 1):
        try:
            with urllib.request.urlopen(request, timeout=300) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as error:
            status = error.code
            if status < 500:
                return status, error.read()
        except (urllib.error.URLError, OSError) as error:
            status = str(error)
        if attempt < ATTEMPTS:
            time.sleep(RETRY_PAUSE_SECONDS)
    return status, b""


def bundle_entries(root, version):
    """(path in the repository layout, bytes) of every file to upload, or an error message."""
    bundles = sorted(pathlib.Path(root).glob("**/target/central-publishing/*.zip"))
    if not bundles:
        return None, "no central-publishing bundle under %s" % root
    entries, wrong = [], []
    for bundle in bundles:
        with zipfile.ZipFile(bundle) as z:
            for name in z.namelist():
                file = name.rsplit("/", 1)[-1]
                if (name.endswith("/") or file.endswith(SKIPPED_SUFFIXES) or file.startswith(SKIPPED_PREFIXES)
                        or file in SKIPPED_FILES):
                    continue
                parts = name.split("/")
                if len(parts) < 4 or parts[-2] != version:
                    wrong.append(name)
                    continue
                entries.append((name, z.read(name)))
    if wrong:
        return None, "files not at version %s: %s" % (version, ", ".join(wrong))
    return entries, None


def publish(root, version, dry_run=False):
    server, owner, user, token = settings()
    entries, error = bundle_entries(root, version)
    if error:
        print("::error::" + error)
        return 1
    base = "%s/api/packages/%s/maven/" % (server, owner)
    if dry_run:
        size = sum(len(body) for _, body in entries)
        print("%s: %d files would be uploaded (%.1f MB) to %s" % (version, len(entries), size / 1e6, base))
        return 0
    uploaded = present = 0
    for name, body in entries:
        status, _ = call("PUT", base + name, user, token, body)
        if status == 201:
            uploaded += 1
        elif status == 409:
            present += 1
        else:
            print("::error::%s: the registry answered %s" % (name, status))
            return 1
    print("%s: %d uploaded, %d already there (%s)" % (version, uploaded, present, base))
    return 0


def probe(stamp):
    server, owner, user, token = settings()
    version = "0.0.0-probe-%s" % stamp
    path = "io/vidocq/ci/codefloe-probe/%s/codefloe-probe-%s.pom" % (version, version)
    pom = ("<project><modelVersion>4.0.0</modelVersion><groupId>io.vidocq.ci</groupId>"
           "<artifactId>codefloe-probe</artifactId><version>%s</version></project>" % version).encode()
    status, _ = call("PUT", "%s/api/packages/%s/maven/%s" % (server, owner, path), user, token, pom)
    if status != 201:
        print("::error::the registry refused the probe upload: %s" % status)
        return 1
    status, body = call("GET", "%s/api/v1/packages/%s?type=maven&q=codefloe-probe" % (server, owner), user, token)
    names = [p["name"] for p in json.loads(body or b"[]") if p.get("version") == version] if status == 200 else []
    for name in names:
        url = "%s/api/v1/packages/%s/maven/%s/%s" % (server, owner, urllib.parse.quote(name, safe=""), version)
        status, _ = call("DELETE", url, user, token)
        if status != 204:
            print("::warning::the probe package %s %s stays: delete answered %s" % (name, version, status))
    if not names:
        print("::warning::the probe package %s was not found to delete" % version)
    print("the registry %s/api/packages/%s/maven accepts uploads from %s" % (server, owner, user))
    return 0


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    commands = parser.add_subparsers(dest="command", required=True)
    p = commands.add_parser("probe")
    p.add_argument("--stamp", default=str(int(time.time())))
    p = commands.add_parser("publish")
    p.add_argument("--root", required=True)
    p.add_argument("--version", required=True)
    p.add_argument("--dry-run", action="store_true", help="list what would be uploaded, upload nothing")
    args = parser.parse_args(argv)
    if args.command == "probe":
        return probe(args.stamp)
    return publish(args.root, args.version, args.dry_run)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
