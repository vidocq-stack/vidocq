#!/usr/bin/env python3
"""Tests of scan-released-sboms.py, with a fake Maven Central and a fake OSV-Scanner: no network.

    python3 -I .forgejo/scripts/scan-released-sboms-test.py
"""
import importlib.util
import json
import os
import pathlib
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True  # no __pycache__ next to the scripts

HERE = pathlib.Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("scan", HERE / "scan-released-sboms.py")
scan = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(scan)

CENTRAL = scan.CENTRAL


def bom_pom(version, managed):
    dependencies = "".join(
        "<dependency><groupId>%s</groupId><artifactId>%s</artifactId><version>%s</version></dependency>" % m
        for m in managed)
    return ('<project xmlns="http://maven.apache.org/POM/4.0.0"><groupId>io.vidocq.runtime</groupId>'
            '<artifactId>vidocq-runtime-bom</artifactId><version>%s</version><packaging>pom</packaging>'
            '<dependencyManagement><dependencies>%s</dependencies></dependencyManagement></project>'
            % (version, dependencies)).encode()


def sbom(*components):
    return json.dumps({"bomFormat": "CycloneDX", "specVersion": "1.6", "components": [
        {"type": "library", "group": g, "name": a, "version": v, "purl": "pkg:maven/%s/%s@%s" % (g, a, v)}
        for g, a, v in components]}).encode()


def url(group, artifact, version, suffix):
    return "%s/%s/%s/%s/%s-%s%s" % (CENTRAL, group.replace(".", "/"), artifact, version, artifact, version, suffix)


class FakeCentral:
    """Answers the URLs it holds, None (a 404) for the others; records what was asked."""

    def __init__(self, files):
        self.files = dict(files)
        self.asked = []

    def __call__(self, address):
        self.asked.append(address)
        return self.files.get(address)


# The answer of `osv-scanner scan source --format json --all-packages` (shortened), for one vulnerable package.
def osv_answer(paths, vulnerable_path=None):
    results = []
    for path in paths:
        packages = [{"package": {"name": "vauban-core", "version": "0.4.0", "ecosystem": "Maven"}}]
        if path == vulnerable_path:
            packages.append({
                "package": {"name": "jackson-databind", "version": "2.15.2", "ecosystem": "Maven"},
                "vulnerabilities": [
                    {"id": "GHSA-aaaa", "aliases": ["CVE-2026-1"], "database_specific": {"severity": "HIGH"}},
                    {"id": "GHSA-bbbb", "aliases": ["CVE-2026-2"], "database_specific": {"severity": "MODERATE"}}],
                "groups": [{"ids": ["GHSA-aaaa"], "aliases": ["CVE-2026-1", "GHSA-aaaa"], "max_severity": "8.1"},
                           {"ids": ["GHSA-bbbb"], "aliases": ["CVE-2026-2", "GHSA-bbbb"], "max_severity": "6.5"}]})
        results.append({"source": {"path": path, "type": "sbom"}, "packages": packages})
    return {"results": results}


class FakeOsv:
    """Stands for the OSV-Scanner binary: records the files it was given, returns (exit code, JSON answer)."""

    def __init__(self, vulnerable=None, code=None):
        self.vulnerable = vulnerable
        self.code = code
        self.files = []

    def __call__(self, files):
        files = [str(f) for f in files]
        self.files.append(files)
        if self.code is not None:
            return self.code, ""
        names = [pathlib.Path(f).name for f in files]
        hit = next((f for f in files if self.vulnerable and pathlib.Path(f).name.startswith(self.vulnerable)), None)
        assert all(n.endswith(".cdx.json") for n in names), names
        return (1 if hit else 0), json.dumps(osv_answer(files, hit))


class SelectReleasesTest(unittest.TestCase):

    TAGS = ["v0.2.0", "v0.2.3-cli", "v0.3.0", "v0.3.1", "v0.4.0-RC1", "v0.4.0-RC2", "v0.4.0", "vauban/v0.4.0",
            "vidocq-parent/v0.4.0-RC2"]

    def test_the_newest_line_alone_without_a_maintenance_branch(self):
        self.assertEqual(["0.4.0"], scan.select_releases(self.TAGS, []))

    def test_a_maintenance_branch_keeps_the_latest_release_of_its_line(self):
        self.assertEqual(["0.3.1", "0.4.0"], scan.select_releases(self.TAGS, ["maintenance/0.3"]))

    def test_a_patch_release_replaces_the_release_of_its_line(self):
        self.assertEqual(["0.4.1"], scan.select_releases(self.TAGS + ["v0.4.1"], ["maintenance/0.4"]))

    def test_a_maintenance_branch_without_a_release_adds_nothing(self):
        self.assertEqual(["0.4.0"], scan.select_releases(self.TAGS, ["maintenance/0.1", "maintenance/0.5"]))

    def test_versions_compare_as_numbers(self):
        self.assertEqual(["0.10.0"], scan.select_releases(["v0.9.0", "v0.10.0"], []))


class SbomSourcesTest(unittest.TestCase):

    def test_the_aggregate_sbom_when_the_release_has_one(self):
        aggregate = url("io.vidocq", "vidocq-build", "0.5.0", "-cyclonedx.json")
        central = FakeCentral({aggregate: sbom()})
        kind, sources = scan.sbom_sources("0.5.0", central, extras=[("io.vidocq.runtime", "vidocq-runtime-cli")])
        self.assertEqual("aggregate", kind)
        self.assertEqual([("io.vidocq:vidocq-build", aggregate)], sources)

    def test_one_sbom_per_published_module_otherwise(self):
        bom = url("io.vidocq.runtime", "vidocq-runtime-bom", "0.4.0", ".pom")
        central = FakeCentral({bom: bom_pom("0.4.0", [("io.vidocq.vauban", "vauban-core", "0.4.0"),
                                                      ("io.vidocq.runtime", "vidocq-runtime-core", "0.4.0"),
                                                      ("org.example", "third-party", "1.0"),
                                                      ("io.vidocq.foy", "foy-old", "0.3.0")])})
        kind, sources = scan.sbom_sources("0.4.0", central, extras=[("io.vidocq.runtime", "vidocq-runtime-core"),
                                                                     ("io.vidocq.runtime", "vidocq-runtime-cli")])
        self.assertEqual("per module", kind)
        self.assertEqual([
            ("io.vidocq.runtime:vidocq-runtime-bom", url("io.vidocq.runtime", "vidocq-runtime-bom", "0.4.0", "-cyclonedx.json")),
            ("io.vidocq.runtime:vidocq-runtime-cli", url("io.vidocq.runtime", "vidocq-runtime-cli", "0.4.0", "-cyclonedx.json")),
            ("io.vidocq.runtime:vidocq-runtime-core", url("io.vidocq.runtime", "vidocq-runtime-core", "0.4.0", "-cyclonedx.json")),
            ("io.vidocq.vauban:vauban-core", url("io.vidocq.vauban", "vauban-core", "0.4.0", "-cyclonedx.json")),
        ], sources)

    def test_a_release_with_neither_an_aggregate_nor_a_bom_is_an_error(self):
        with self.assertRaises(scan.ScanError):
            scan.sbom_sources("0.4.0", FakeCentral({}), extras=[])


class ScanTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.work = pathlib.Path(self.tmp.name)
        bom = url("io.vidocq.runtime", "vidocq-runtime-bom", "0.4.0", ".pom")
        self.central = FakeCentral({
            bom: bom_pom("0.4.0", [("io.vidocq.vauban", "vauban-core", "0.4.0"),
                                   ("io.vidocq.mansart", "mansart-pool", "0.4.0"),
                                   ("io.vidocq.foy", "foy-api", "0.4.0")]),
            url("io.vidocq.runtime", "vidocq-runtime-bom", "0.4.0", "-cyclonedx.json"): sbom(),
            url("io.vidocq.vauban", "vauban-core", "0.4.0", "-cyclonedx.json"): sbom(
                ("io.vidocq.vauban", "vauban-core", "0.4.0")),
            url("io.vidocq.mansart", "mansart-pool", "0.4.0", "-cyclonedx.json"): sbom(
                ("com.fasterxml.jackson.core", "jackson-databind", "2.15.2")),
            # foy-api: no SBOM on Central
        })

    def tearDown(self):
        self.tmp.cleanup()

    def run_scan(self, osv):
        return scan.scan(["0.4.0"], self.central, osv, self.work, extras=[])

    def test_a_vulnerability_fails_the_scan_and_names_the_package_and_the_module(self):
        osv = FakeOsv(vulnerable="io.vidocq.mansart_mansart-pool")
        code, report = self.run_scan(osv)
        self.assertEqual(1, code)
        self.assertIn("com.fasterxml.jackson.core:jackson-databind", report)
        self.assertIn("2.15.2", report)
        self.assertIn("GHSA-aaaa (CVE-2026-1)", report)
        self.assertIn("HIGH 8.1", report)
        self.assertIn("io.vidocq.mansart:mansart-pool", report)
        self.assertIn("2 vulnerabilities in 1 package", report)

    def test_no_vulnerability_passes(self):
        code, report = self.run_scan(FakeOsv())
        self.assertEqual(0, code)
        self.assertIn("no known vulnerability", report)

    def test_every_sbom_reaches_the_scanner_under_a_name_it_recognises(self):
        osv = FakeOsv()
        self.run_scan(osv)
        self.assertEqual(1, len(osv.files))
        names = sorted(pathlib.Path(f).name for f in osv.files[0])
        self.assertEqual(["io.vidocq.mansart_mansart-pool.cdx.json", "io.vidocq.runtime_vidocq-runtime-bom.cdx.json",
                          "io.vidocq.vauban_vauban-core.cdx.json"], names)

    def test_a_module_without_an_sbom_is_reported(self):
        _, report = self.run_scan(FakeOsv())
        self.assertIn("io.vidocq.foy:foy-api", report)
        self.assertIn("3 SBOMs (per module)", report)

    def test_a_failure_of_the_scanner_is_an_error(self):
        with self.assertRaises(scan.ScanError):
            self.run_scan(FakeOsv(code=127))

    def test_a_release_without_any_sbom_is_an_error(self):
        central = FakeCentral({url("io.vidocq.runtime", "vidocq-runtime-bom", "0.4.0", ".pom"):
                               bom_pom("0.4.0", [("io.vidocq.foy", "foy-api", "0.4.0")])})
        with self.assertRaises(scan.ScanError):
            scan.scan(["0.4.0"], central, FakeOsv(), self.work, extras=[])


class ExtrasTest(unittest.TestCase):

    def test_the_always_published_folders_of_release_patch_json(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            (root / ".forgejo").mkdir()
            (root / ".forgejo/release-patch.json").write_text(json.dumps({"always": ["cli", "gone"]}))
            (root / "cli").mkdir()
            (root / "cli/pom.xml").write_text(
                '<project xmlns="http://maven.apache.org/POM/4.0.0"><parent><groupId>io.vidocq.runtime</groupId>'
                '<artifactId>vidocq-runtime-build</artifactId></parent><artifactId>vidocq-runtime-cli</artifactId>'
                '</project>')
            self.assertEqual([("io.vidocq.runtime", "vidocq-runtime-cli")], scan.always_published(root))


class MainTest(unittest.TestCase):

    def test_an_option_without_its_value_is_an_error(self):
        self.assertEqual(2, scan.main(["scan-released-sboms.py", "--work"]))

    def test_the_report_goes_to_the_step_summary(self):
        with tempfile.TemporaryDirectory() as tmp:
            summary = pathlib.Path(tmp) / "summary.md"
            summary.write_text("before\n")
            scan.write_summary("## report\n", {"GITHUB_STEP_SUMMARY": str(summary)})
            self.assertEqual("before\n## report\n", summary.read_text())
            scan.write_summary("ignored", {})  # no summary outside CI


if __name__ == "__main__":
    unittest.main()
