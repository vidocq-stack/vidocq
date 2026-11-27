#!/usr/bin/env python3
"""Tests of tck-report.py, on report files written in a temporary folder.

    python3 -I .forgejo/scripts/tck-report-test.py
"""
import contextlib
import importlib.util
import io
import pathlib
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True  # no __pycache__ next to the scripts

HERE = pathlib.Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("tck_report", HERE / "tck-report.py")
tck_report = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(tck_report)

GREEN = """Noether Jakarta Persistence 3.2 TCK report
Generated  : 2026-11-17T10:00:00Z
Validation : off    Area: all    Fixtures: see below
Tests      : 2135  pass=2131  fail=0  skipped=4
  execution1  : 2134  pass=2130  fail=0  skipped=4
  execution2  : 1  pass=1  fail=0  skipped=0

Failure causes (deepest cause):
"""

RED = GREEN.replace("pass=2131  fail=0", "pass=2120  fail=11").replace(
    "Failure causes (deepest cause):\n", "Failure causes (deepest cause):\n     11  java.sql.SQLException: boom\n")

EMPTY = """Noether Jakarta Persistence 3.2 TCK report
Tests      : 0  pass=0  fail=0  skipped=0
WARNING    : no TCK test ran
"""


class TckReportTest(unittest.TestCase):

    def run_report(self, report_text=None, status=0, reason=""):
        with tempfile.TemporaryDirectory() as tmp:
            report = pathlib.Path(tmp, "report.txt")
            if report_text is not None:
                report.write_text(report_text, encoding="utf-8")
            summary = pathlib.Path(tmp, "summary.md")
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                code = tck_report.main(["tck-report.py", "--name", "NOETHER", "--report", str(report),
                                        "--status", str(status), "--reason", reason, "--summary", str(summary)])
            return code, out.getvalue(), summary.read_text(encoding="utf-8")

    def test_a_green_report_gives_no_warning(self):
        code, out, summary = self.run_report(GREEN)
        self.assertEqual(0, code)
        self.assertNotIn("::warning", out)
        self.assertIn("2131 passed", summary)
        self.assertIn("0 failed", summary)
        self.assertIn("Tests      : 2135", summary)  # the report itself, verbatim

    def test_failures_give_a_warning_and_never_a_failure(self):
        code, out, summary = self.run_report(RED)
        self.assertEqual(0, code)
        self.assertIn("::warning title=TCK NOETHER (report mode)::11 of 2135 tests failed", out)
        self.assertIn("11 failed", summary)
        self.assertIn("java.sql.SQLException: boom", summary)

    def test_no_test_run_gives_a_warning(self):
        code, out, _ = self.run_report(EMPTY)
        self.assertEqual(0, code)
        self.assertIn("::warning title=TCK NOETHER (report mode)::no TCK test ran", out)

    def test_a_missing_report_gives_the_reason(self):
        code, out, summary = self.run_report(None, status=0, reason="psql could not be installed")
        self.assertEqual(0, code)
        self.assertIn("::warning title=TCK NOETHER (report mode)::the TCK did not run: psql could not be installed",
                      out)
        self.assertIn("did not run", summary)

    def test_a_missing_report_without_a_reason_names_the_exit_status(self):
        code, out, _ = self.run_report(None, status=69)
        self.assertEqual(0, code)
        self.assertIn("the TCK did not run: the runner exited with status 69 and wrote no report", out)

    def test_a_non_zero_status_with_a_report_gives_a_warning(self):
        # The runner exits non-zero after its report when a report did not run on the prepared spec-tests jar.
        code, out, _ = self.run_report(GREEN, status=1)
        self.assertEqual(0, code)
        self.assertIn("the runner exited with status 1", out)

    def test_a_timeout_is_named(self):
        code, out, _ = self.run_report(None, status=124)
        self.assertEqual(0, code)
        self.assertIn("timed out", out)

    def test_an_unreadable_counter_line_gives_a_warning(self):
        code, out, _ = self.run_report("Noether Jakarta Persistence 3.2 TCK report\n")
        self.assertEqual(0, code)
        self.assertIn("no test counters in the report", out)

    def test_the_summary_is_appended(self):
        with tempfile.TemporaryDirectory() as tmp:
            report = pathlib.Path(tmp, "report.txt")
            report.write_text(GREEN, encoding="utf-8")
            summary = pathlib.Path(tmp, "summary.md")
            summary.write_text("earlier step\n", encoding="utf-8")
            with contextlib.redirect_stdout(io.StringIO()):
                tck_report.main(["tck-report.py", "--name", "NOETHER", "--report", str(report),
                                 "--summary", str(summary)])
            self.assertTrue(summary.read_text(encoding="utf-8").startswith("earlier step\n"))


if __name__ == "__main__":
    unittest.main()
