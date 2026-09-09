#!/usr/bin/env python3
"""Where does this spec's TCK runner module go? Read the repo, do not invent.

An agent left to itself created `ee/jakarta/tck/persistence/mansart-jkp-tck/` —
the TCK's own Java package path, with the harness's internal 3-letter code used
as a Maven module name. Both are wrong, and neither was forbidden anywhere.

The repository already answers the question. It holds two runners:

    mansart-jakarta-data/mansart-data-tck
    mansart-transactions/mansart-transactions-tck

so the layout is <top-level module>/<name>-tck. Two shapes exist in the wild and
the script tells them apart by looking, not by asking:

  MULTI-SPEC  at least one */*-tck exists  -> parent module + tck submodule
  SINGLE-SPEC a root pom.xml and no */*-tck -> the runner sits at the root

The result is a PROPOSAL written to docs/spec-src/<XXX>/module.conf, which is
yours to edit — naming is a judgement (this repo says mansart-jakarta-data but
mansart-transactions). Everything downstream reads that file, so no agent ever
picks a path again.

Usage: scripts/tck-module.py <XXX> [--force]
"""
import glob
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def existing_runners():
    out = []
    for p in sorted(glob.glob(os.path.join(ROOT, "*", "*-tck"))):
        if os.path.isfile(os.path.join(p, "pom.xml")):
            out.append(os.path.relpath(p, ROOT))
    return out


def prefix_of(runners):
    """The common leading token of the existing modules, e.g. 'mansart'."""
    tops = {r.split(os.sep)[0].split("-")[0] for r in runners}
    return tops.pop() if len(tops) == 1 else os.path.basename(ROOT).split("-")[0]


def propose(code, keyword):
    runners = existing_runners()
    kw = re.sub(r"[^a-z0-9]+", "-", (keyword or code).lower()).strip("-")
    if runners:                       # MULTI-SPEC: mirror what is already here
        pfx = prefix_of(runners)
        parent = next((r.split(os.sep)[0] for r in runners if kw in r), None)
        if not parent:
            parent = f"{pfx}-jakarta-{kw}"
        return f"{parent}/{parent}-tck", "multi-spec", runners
    if os.path.isfile(os.path.join(ROOT, "pom.xml")):   # SINGLE-SPEC: at the root
        pfx = os.path.basename(ROOT).split("-")[0]
        return f"{pfx}-{kw}-tck", "single-spec", runners
    return f"{kw}-tck", "single-spec (no root pom)", runners


def read_conf(path):
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.split("#", 1)[0].strip()
            if line.startswith("tck_module"):
                return line.split("=", 1)[1].strip()
    return None


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    force = "--force" in sys.argv
    if len(args) != 1 or not re.fullmatch(r"[A-Z]{3}", args[0]):
        print(__doc__)
        return 2
    code = args[0]
    d = os.path.join(ROOT, "docs", "spec-src", code)
    conf = os.path.join(d, "module.conf")

    if os.path.isfile(conf) and not force:
        m = read_conf(conf)
        if m:
            print(m)
            return 0

    keyword = code.lower()
    meta = os.path.join(d, "spec-meta.json")
    if os.path.isfile(meta):
        keyword = (json.load(open(meta, encoding="utf-8")).get("tck") or {}).get("keyword") or keyword
    path, shape, runners = propose(code, keyword)

    os.makedirs(d, exist_ok=True)
    with open(conf, "w", encoding="utf-8") as f:
        f.write(f"# Where the TCK runner module lives. EDIT THIS if the name is wrong —\n"
                f"# naming is a judgement, and everything downstream reads this file so\n"
                f"# that no agent ever invents a path again.\n"
                f"# layout detected: {shape}\n")
        for r in runners:
            f.write(f"# existing runner: {r}\n")
        f.write(f"tck_module = {path}\n")
    print(path, file=sys.stdout)
    print(f"proposed in docs/spec-src/{code}/module.conf ({shape}) — edit it if wrong",
          file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
