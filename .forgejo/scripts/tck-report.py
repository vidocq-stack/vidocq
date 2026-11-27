#!/usr/bin/env python3
"""Reports a TCK run in report mode: the job summary and a warning, never a failure (BUG-20261123-58).

A TCK in report mode runs in the TCK nightly (tck-nightly.yml, job tck-report) but cannot turn it red: the release
requires a green TCK nightly (release-gate.py), and that TCK is not a release gate yet. Its failures are reported
instead, as a `::warning::` annotation of the job and in its summary.

    tck-report.py --name <tag> --report <report file> [--status <runner exit status>] [--reason <why it did not run>]
                  [--summary <file>]       default: $GITHUB_STEP_SUMMARY; appended to

The report file is the one Noether's run-official-tck-persistence-3.2.sh writes: a line
`Tests      : <n>  pass=<p>  fail=<f>  skipped=<s>` (fail counts failures and errors), then the failure causes.
Always exits 0.
"""
import argparse
import os
import re
import sys

COUNTERS = re.compile(r"^Tests\s*:\s*(\d+)\s+pass=(\d+)\s+fail=(\d+)\s+skipped=(\d+)", re.MULTILINE)
TIMEOUT_STATUS = 124  # timeout(1)


def problems(report, status, reason):
    """What a maintainer must look at, one sentence each; empty when the run is green. Also the counters, or None."""
    if report is None:
        if reason:
            why = reason
        elif status == TIMEOUT_STATUS:
            why = "the runner timed out before it wrote a report"
        else:
            why = "the runner exited with status %d and wrote no report" % status
        return ["the TCK did not run: " + why], None
    found = []
    match = COUNTERS.search(report)
    counters = tuple(int(g) for g in match.groups()) if match else None
    if counters is None:
        found.append("no test counters in the report")
    elif counters[0] == 0:
        found.append("no TCK test ran")
    elif counters[2]:
        found.append("%d of %d tests failed" % (counters[2], counters[0]))
    for line in report.splitlines():
        if line.startswith("WARNING") and "no TCK test ran" not in line:
            found.append(line.split(":", 1)[-1].strip())
    if status == TIMEOUT_STATUS:
        found.append("the runner timed out")
    elif status:
        found.append("the runner exited with status %d" % status)
    if reason:
        found.append(reason)
    return found, counters


def summary(name, report, found, counters):
    lines = ["## TCK %s (report mode)" % name, ""]
    if counters is not None:
        total, passed, failed, skipped = counters
        lines.append("%d tests: %d passed, %d failed, %d skipped." % (total, passed, failed, skipped))
        lines.append("")
    if report is None:
        lines.append("The TCK did not run.")
        lines.append("")
    if found:
        lines.append("Report mode: these do not fail the TCK nightly.")
        lines.append("")
        lines.extend("- " + f for f in found)
        lines.append("")
    if report is not None:
        lines.extend(["```", report.rstrip("\n"), "```", ""])
    return "\n".join(lines) + "\n"


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--name", required=True)
    parser.add_argument("--report", required=True)
    parser.add_argument("--status", type=int, default=0)
    parser.add_argument("--reason", default="")
    parser.add_argument("--summary", default=os.environ.get("GITHUB_STEP_SUMMARY", ""))
    args = parser.parse_args(argv[1:])
    try:
        with open(args.report, encoding="utf-8", errors="replace") as file:
            report = file.read()
    except OSError:
        report = None
    found, counters = problems(report, args.status, args.reason.strip())
    for problem in found:
        print("::warning title=TCK %s (report mode)::%s" % (args.name, problem))
    text = summary(args.name, report, found, counters)
    print(text)
    if args.summary:
        try:
            with open(args.summary, "a", encoding="utf-8") as file:
                file.write(text)
        except OSError as error:
            print("::warning title=TCK %s (report mode)::no job summary: %s" % (args.name, error))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
