#!/usr/bin/env python3
"""Tests of patch-plan.py, on a small reactor written in a temporary folder.

    python3 -I .forgejo/scripts/patch-plan-test.py
"""
import importlib.util
import json
import pathlib
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True  # no __pycache__ next to the scripts

HERE = pathlib.Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("patch_plan", HERE / "patch-plan.py")
patch_plan = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(patch_plan)

NS = 'xmlns="http://maven.apache.org/POM/4.0.0"'


def pom(group, artifact, packaging="jar", managed=()):
    management = ""
    if managed:
        management = "<dependencyManagement><dependencies>%s</dependencies></dependencyManagement>" % "".join(
            "<dependency><groupId>%s</groupId><artifactId>%s</artifactId><version>1.0.0</version></dependency>"
            % m for m in managed)
    return "<project %s><groupId>%s</groupId><artifactId>%s</artifactId><packaging>%s</packaging>%s</project>" % (
        NS, group, artifact, packaging, management)


class PatchPlanTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.tmp.name)
        published = [("io.x", "brick-api"), ("io.x", "brick-core"), ("io.x.ext", "brick-extension"),
                     ("io.x.ext", "brick-extension-codegen"), ("io.x.ext", "brick-extension-dev"),
                     ("io.x.ext", "other-extension")]
        self.write(".", pom("io.x", "build", "pom"))
        self.write("brick", pom("io.x", "brick-parent", "pom"))
        self.write("brick/brick-api", pom("io.x", "brick-api"))
        self.write("brick/brick-core", pom("io.x", "brick-core"))
        self.write("brick/brick-tests", pom("io.x", "brick-tests"))
        self.write("ext", pom("io.x.ext", "extensions", "pom"))
        for name in ("brick-extension", "brick-extension-codegen", "brick-extension-dev", "other-extension"):
            self.write("ext/" + name, pom("io.x.ext", name))
        self.write("runtime/runtime-bom", pom("io.x", "runtime-bom", "pom", published))
        self.write("runtime/runtime-cli", pom("io.x", "runtime-cli"))
        (self.root / ".forgejo").mkdir()
        (self.root / ".forgejo/release-patch.json").write_text(json.dumps({
            "always": ["runtime/runtime-bom", "runtime/runtime-cli"],
            "companions": ["-codegen", "-dev"]}))
        self._bom = patch_plan.BOM
        patch_plan.BOM = "runtime/runtime-bom/pom.xml"

    def tearDown(self):
        patch_plan.BOM = self._bom
        self.tmp.cleanup()

    def write(self, folder, text):
        path = self.root / folder / "pom.xml"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)

    def plan(self, *files):
        return patch_plan.plan(self.root, "v1.0.0", list(files))[0]

    def test_a_change_of_the_main_sources_publishes_the_module_and_the_folders_of_always(self):
        self.assertEqual(["brick/brick-core", "runtime/runtime-bom", "runtime/runtime-cli"],
                         self.plan("brick/brick-core/src/main/java/io/x/Core.java"))

    def test_a_change_of_a_module_pom_publishes_the_module(self):
        self.assertIn("brick/brick-api", self.plan("brick/brick-api/pom.xml"))

    def test_tests_docs_and_unpublished_modules_publish_nothing_more(self):
        self.assertEqual(["runtime/runtime-bom", "runtime/runtime-cli"], self.plan(
            "brick/brick-core/src/test/java/io/x/CoreTest.java",
            "brick/brick-core/README.md",
            "brick/docs/en/modules/ROOT/pages/index.adoc",
            "brick/brick-tests/src/main/java/io/x/Probe.java",
            "README.md"))

    def test_an_extension_brings_its_companions_and_a_companion_its_extension(self):
        companions = ["ext/brick-extension", "ext/brick-extension-codegen", "ext/brick-extension-dev"]
        for changed in ("ext/brick-extension/src/main/java/A.java", "ext/brick-extension-dev/src/main/java/B.java"):
            with self.subTest(changed=changed):
                self.assertEqual(companions + ["runtime/runtime-bom", "runtime/runtime-cli"], self.plan(changed))

    def test_a_parent_or_a_grouping_pom_stops_the_plan(self):
        for changed in ("brick/pom.xml", "ext/pom.xml", "pom.xml"):
            with self.subTest(changed=changed):
                with self.assertRaises(patch_plan.PlanError) as e:
                    self.plan(changed)
                self.assertIn(changed, str(e.exception))
                self.assertIn("full release", str(e.exception))

    def test_a_parent_pom_the_maintainer_accepts_publishes_nothing_more(self):
        folders, report = patch_plan.plan(self.root, "v1.0.0", ["brick/pom.xml"], accepted=["brick/pom.xml"])

        self.assertEqual(["runtime/runtime-bom", "runtime/runtime-cli"], folders)
        self.assertIn("brick/pom.xml (a parent, accepted as a change of the build only)", "\n".join(report))
        with self.assertRaises(patch_plan.PlanError):
            patch_plan.plan(self.root, "v1.0.0", ["brick/pom.xml", "ext/pom.xml"], accepted=["brick/pom.xml"])

    def test_the_command_line_takes_accepted_poms_before_the_tag(self):
        self.assertEqual(2, patch_plan.main(["patch-plan.py", "--accept"]))

    def test_a_folder_of_always_without_pom_stops_the_plan(self):
        (self.root / ".forgejo/release-patch.json").write_text(json.dumps({"always": ["runtime/gone"]}))
        with self.assertRaises(patch_plan.PlanError):
            self.plan()

    def test_the_report_says_why_each_module_is_in_the_plan_and_what_was_left_out(self):
        _, report = patch_plan.plan(self.root, "v1.0.0", ["ext/brick-extension/src/main/java/A.java",
                                                          "brick/brick-core/src/test/java/T.java"])
        text = "\n".join(report)
        self.assertIn("changed: ext/brick-extension/src/main/java/A.java", text)
        self.assertIn("companion of brick-extension", text)
        self.assertIn("always (.forgejo/release-patch.json)", text)
        self.assertIn("left out:", text)
        self.assertIn("brick/brick-core/src/test/java/T.java (not shipped by brick-core)", text)


if __name__ == "__main__":
    unittest.main()
