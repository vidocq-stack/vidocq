#!/usr/bin/env python3
"""Find the official TCK for a spec, and the best in-repo runner to copy.

Deterministic lookup, no model: scans the local M2 for TCK artifacts matching a
keyword, and scans the repo for an existing *-tck module to use as a template.

Why it matters: a spec without a running TCK has no progress metric. Attempt 1
of this project reached "2/1745" and attempt 3 marked 24 cards DONE while the
real counter said 2. The TCK is the only thing that can contradict an agent.

Usage:  scripts/tck-find.py <keyword> [--spec-version X.Y] [--json]
        scripts/tck-find.py persistence
        scripts/tck-find.py data --json

Exit codes: 0 = found, 1 = nothing found (not an error — the spec may have no TCK)
"""
import json
import zipfile
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
            v = pick_version(versions)
            out.append({"groupId": group, "artifactId": name, "versions": versions,
                        "matching_version": v,
                        "test_classes": count_test_classes(dirpath, name, v)})
            dirnames[:] = []
    return sorted(out, key=lambda a: (len(a["artifactId"]), a["artifactId"]))


def count_test_classes(art_dir, name, version):
    """How many test classes the jar actually holds. -1 = no jar at all.

    WHY: `jakarta.tck:persistence-tck:3.2.1` is a POM-only aggregator — no jar.
    Recommending it sent an agent chasing a coordinate that cannot resolve, and
    it "fixed" the red build by deleting the TCK dependency. Measured here:
    persistence-tck-dist 8 KB / 0 tests, persistence-tck-common 156 KB / 0,
    persistence-tck-spec-tests 2.4 MB / **161**. Only the last one runs anything.
    Names lie about which artifact carries the suite; the jar does not.
    """
    if not version:
        return -1
    jar = os.path.join(art_dir, version, "{}-{}.jar".format(name, version))
    if not os.path.isfile(jar):
        return -1
    try:
        with zipfile.ZipFile(jar) as z:
            return sum(1 for n in z.namelist()
                       if n.endswith("Client.class") or n.endswith("Test.class"))
    except Exception:
        return -1


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


SPEC_VERSION = None  # set from --spec-version; keeps a 3.2 spec off a 4.0 TCK


def version_matches(candidate, wanted):
    """3.2 matches 3.2, 3.2.1, 3.2.2-SNAPSHOT — never 3.20 or 4.0."""
    head = candidate.split("-")[0]
    return head == wanted or head.startswith(wanted + ".")


def pick_version(versions):
    """Newest release, or newest snapshot if that is all there is.

    SPEC_VERSION is a HARD filter, not a preference: the TCK of Jakarta
    Persistence 3.2 is the only thing that can measure an implementation of
    Jakarta Persistence 3.2. Falling back to another version silently would
    produce a counter that looks real and means nothing — the 4.0.0-SNAPSHOT
    jar on this machine holds 321 test classes against 161 for 3.2.1.
    Returns None when nothing matches, and the caller must then say so.
    """
    if SPEC_VERSION:
        versions = [v for v in versions if version_matches(v, SPEC_VERSION)]
        if not versions:
            return None
    """Prefer a release over a SNAPSHOT, highest first."""
    releases = [v for v in versions if "SNAPSHOT" not in v]
    return sorted(releases or versions)[-1]


def main():
    argv, args = sys.argv[1:], []
    skip = False
    for n, a in enumerate(argv):          # --spec-version takes a VALUE: skip it,
        if skip:                          # else "3.2" looked like a second keyword
            skip = False
            continue
        if a == "--spec-version":
            skip = True
            continue
        if not a.startswith("--"):
            args.append(a)
    as_json = "--json" in sys.argv
    if len(args) != 1:
        print(__doc__)
        return 2
    keyword = args[0]

    global SPEC_VERSION
    for n, a in enumerate(sys.argv):
        if a == "--spec-version" and n + 1 < len(sys.argv):
            SPEC_VERSION = sys.argv[n + 1]

    arts = scan_m2(keyword)
    runners = scan_repo_runners()
    # Pick what actually runs tests, not what is named most plausibly.
    with_tests = [a for a in arts if a.get("test_classes", -1) > 0]
    if with_tests:
        # A release beats a SNAPSHOT even with fewer tests: measured here, the
        # richest jar was jakarta.persistence:...-spec-tests:4.0.0-SNAPSHOT (321
        # classes) — the TCK of the NEXT spec version. Test count alone would
        # have pointed a 3.2 implementation at 4.0.
        def rank(a):
            v = a["matching_version"] or ""
            return ("SNAPSHOT" not in v, a["test_classes"], -len(a["artifactId"]))
        main_art = max(with_tests, key=rank)
    else:
        with_jar = [a for a in arts if a.get("test_classes", -1) == 0]
        main_art = (with_jar[0] if with_jar
                    else next((a for a in arts if a["artifactId"].endswith("-tck")),
                              arts[0] if arts else None))

    result = {
        "keyword": keyword,
        "found": bool(arts),
        "artifacts": arts,
        "recommended": None,
        "repo_runners": runners,
    }
    if main_art and main_art.get("matching_version"):
        result["recommended"] = {
            "groupId": main_art["groupId"],
            "artifactId": main_art["artifactId"],
            "version": main_art["matching_version"],
        }
    # "artifacts exist" is not "a TCK we can run". Say which, so the plan can
    # make installing it a card instead of pretending the metric is there.
    result["runnable"] = bool(result["recommended"]
                              and (main_art or {}).get("test_classes", 0) > 0)
    if not result["runnable"]:
        seen_versions = sorted({v for a in arts for v in a["versions"]})
        result["reason"] = (
            "no TCK jar with test classes for {} {} in the local M2".format(
                keyword, SPEC_VERSION or "(any version)")
            + (" — versions present: " + ", ".join(seen_versions) if seen_versions else ""))
        result["found"] = False

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
    if not result["runnable"]:
        # Loud, and exit 1. An earlier version printed nothing here and returned
        # 0 — a lookup that finds no usable TCK must never look like a success.
        print("\nNO RUNNABLE TCK: " + result["reason"])
        print("the artifacts above (if any) carry no test class for this spec version.")
        print("next step: install the official TCK into the local M2, then re-run.")
        return 1
    if r:
        n = next((a["test_classes"] for a in arts
                  if a["artifactId"] == r["artifactId"] and a["groupId"] == r["groupId"]), -1)
        how = (f"{n} test classes in the jar" if n > 0
               else "jar present but no test class" if n == 0 else "NO JAR — nothing will run")
        print(f"\nrecommended: {r['groupId']}:{r['artifactId']}:{r['version']}  ({how})")
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
