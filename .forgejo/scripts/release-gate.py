#!/usr/bin/env python3
"""Fails unless the whole build and every TCK passed on the commit a release is about to publish.

The release (release.yml, through release.sh of Vidocq/ci) builds with -DskipTests: it trusts the runs before it. Those
are the nightly (nightly.yml: the whole reactor and every test) and the TCKs (tck-nightly.yml: every component), and a
push build of main (ci.yml) runs only the tests of what a push changed. So a release needs a successful run of each of
the two on the very commit it publishes, scheduled or dispatched by hand (Actions, run workflow, on main).

    release-gate.py <commit sha>        reads <server>/api/v1/repos/<repository>/actions/runs, with GITHUB_SERVER_URL,
                                        GITHUB_REPOSITORY and, when set, GATE_TOKEN
    release-gate.py <sha> <runs.json>   reads the runs from a file (a saved answer of that API), for a local check
"""
import json
import os
import sys
import urllib.request

REQUIRED = {"nightly.yml": "Nightly build", "tck-nightly.yml": "TCK nightly"}


def fetch(sha):
    url = "%s/api/v1/repos/%s/actions/runs?head_sha=%s&limit=50" % (
        os.environ["GITHUB_SERVER_URL"].rstrip("/"), os.environ["GITHUB_REPOSITORY"], sha)
    request = urllib.request.Request(url)
    if os.environ.get("GATE_TOKEN"):
        request.add_header("Authorization", "token " + os.environ["GATE_TOKEN"])
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.loads(response.read().decode(), strict=False)


def missing(sha, answer):
    """The required workflows without a successful run on sha, each with what its runs on sha ended with."""
    runs = [r for r in answer.get("workflow_runs") or [] if (r.get("commit_sha") or r.get("head_sha")) == sha]
    result = {}
    for workflow, name in REQUIRED.items():
        states = [r.get("status") for r in runs if r.get("workflow_id") == workflow]
        if "success" not in states:
            result[name] = states
    return result


def main(argv):
    sha = argv[1]
    if len(argv) > 2:
        with open(argv[2], encoding="utf-8") as file:
            answer = json.load(file, strict=False)
    else:
        answer = fetch(sha)
    gaps = missing(sha, answer)
    for name, states in gaps.items():
        print("no successful %s run on %s (runs: %s)" % (name, sha[:10], ", ".join(states) or "none"))
    if gaps:
        print("Run them on main (Actions, run workflow) and release once both are green.")
        return 1
    print("%s green on %s" % (" and ".join(REQUIRED.values()), sha[:10]))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
