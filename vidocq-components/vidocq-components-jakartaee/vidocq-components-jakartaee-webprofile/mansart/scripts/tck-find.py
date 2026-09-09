#!/usr/bin/env python3
"""Find the official TCK for a spec, and the best in-repo runner to copy.

Deterministic lookup, no model: scans the local M2 for TCK artifacts matching a
keyword, and scans the repo for an existing *-tck module to use as a template.

Why it matters: a spec without a running TCK has no progress metric. Attempt 1
of this project reached "2/1745" and attempt 3 marked 24 cards DONE while the
real counter said 2. The TCK is the only thing that can contradict an agent.

Usage:  scripts/tck-find.py <keyword> [--json]
        scripts/tck-find.py persistence
        scripts/tck-find.py data --json

Exit codes: 0 = found, 1 = nothing found (not an error — the spec may have no TCK)
"""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
M2 = os.path.expanduser(os.environ.get("M2_REPO", "~/.m2/repository"))
# Where Jakarta publishes TCKs. Keep this list short and explicit.
TCK_ROOTS = ["jakarta/tck", "jakarta", "org/eclipse/ee4j"]
VERSION_RE = re.compile(r"^\d+[\w.\-]*$")


def is_version_dir(p):
    return os.path.isdir(p) and VERSION_RE.match(os.path.basename(p))


def scan_m2(keyword):
    """Return [{groupId, artifactId, versions[]}] for artifacts matching keyword."""
    kw = keyword.lower()
    out, seen = [], set()
    for root in TCK_ROOTS:
        base = os.path.join(M2, root)
        if not os.path.isdir(base):
            continue
        for dirpath, dirnames, _ in os.walk(base):
            depth = dirpath[len(base):].count(os.sep)
            if depth > 3:
                dirnames[:] = []
                continue
            name = os.path.basename(dirpath)
            if "tck" not in name.lower() or kw not in name.lower():
                continue
            grp = os.path.relpath(os.path.dirname(dirpath), M2).replace(os.sep, ".")
            if (grp, name) in seen:
                dirnames[:] = []
                continue
            versions = sorted(
                os.path.basename(v) for v in
                (os.path.join(dirpath, d) for d in os.listdir(dirpath))
                if is_version_dir(v)
            )
            if not versions:
                continue
            group = os.path.relpath(os.path.dirname(dirpath), M2).replace(os.sep, ".")
            seen.add((group, name))  # TCK_ROOTS overlap: jakarta/tck is under jakarta
            out.append({"groupId": group, "artifactId": name, "versions": versions})
            dirnames[:] = []
    return sorted(out, key=lambda a: (len(a["artifactId"]), a["artifactId"]))


def scan_repo_runners():
    """Existing *-tck modules in this repo — the template to copy."""
    runners = []
    for dirpath, dirnames, filenames in os.walk(ROOT):
        if any(part in dirpath for part in (".git", "target", "node_modules", "docs/spec-src")):
            dirnames[:] = []
            continue
        if dirpath.rstrip("/").endswith("-tck") and "pom.xml" in filenames:
            rel = os.path.relpath(dirpath, ROOT)
            has_tests = os.path.isdir(os.path.join(dirpath, "src", "test"))
            scripts = [f for f in os.listdir(dirpath) if f.startswith("run-") and f.endswith(".sh")]
            runners.append({"path": rel, "has_tests": has_tests, "scripts": scripts})
    return runners


def pick_version(versions):
    """Prefer a release over a SNAPSHOT, highest first."""
    releases = [v for v in versions if "SNAPSHOT" not in v]
    return sorted(releases or versions)[-1]


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    as_json = "--json" in sys.argv
    if len(args) != 1:
        print(__doc__)
        return 2
    keyword = args[0]

    arts = scan_m2(keyword)
    runners = scan_repo_runners()
    main_art = next((a for a in arts if a["artifactId"].endswith("-tck")), arts[0] if arts else None)

    result = {
        "keyword": keyword,
        "found": bool(arts),
        "artifacts": arts,
        "recommended": None,
        "repo_runners": runners,
    }
    if main_art:
        result["recommended"] = {
            "groupId": main_art["groupId"],
            "artifactId": main_art["artifactId"],
            "version": pick_version(main_art["versions"]),
        }

    if as_json:
        print(json.dumps(result, indent=2))
        return 0 if arts else 1

    if not arts:
        print(f"no TCK artifact matching '{keyword}' in {M2}")
        print("the spec may have no TCK, or it is not installed locally")
        print("install it first, or pass a different keyword")
        return 1

    print(f"TCK artifacts matching '{keyword}':")
    for a in arts:
        print(f"  {a['groupId']}:{a['artifactId']}")
        print(f"    versions: {', '.join(a['versions'])}")
    r = result["recommended"]
    print(f"\nrecommended: {r['groupId']}:{r['artifactId']}:{r['version']}")
    if runners:
        print("\nin-repo runners to copy (module layout, Arquillian wiring, run script):")
        for run in runners:
            flag = "tests" if run["has_tests"] else "no tests"
            sc = f", {run['scripts'][0]}" if run["scripts"] else ""
            print(f"  {run['path']}  ({flag}{sc})")
    else:
        print("\nno existing *-tck module in this repo to copy")
    return 0


if __name__ == "__main__":
    sys.exit(main())
