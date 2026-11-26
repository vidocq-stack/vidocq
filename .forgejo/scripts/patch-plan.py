#!/usr/bin/env python3
"""The modules a patch release (x.y.Z, Z > 0) publishes: one reactor folder per line, on stdout.

    patch-plan.py [--accept <pom.xml>]... <previous release tag> [<root>]

A patch release publishes only what changed since the previous release of its line, and what names it:
  - each published module with a change under src/main/ or in its pom.xml since <tag> (a change to its tests, its
    docs or its README publishes nothing);
  - the -codegen and -dev companions of each of them, and the module of a companion: the plugin resolves a
    companion at the version of its extension (.forgejo/release-patch.json, "companions");
  - the folders of "always" in .forgejo/release-patch.json, every time.
A module is published when vidocq-runtime-bom manages it (ApplicationBomAndParentTest keeps that list whole) or it is
in "always". A change to the pom.xml of a parent or of a grouping pom stops the plan: every module below it would
change, and a patch release cannot carry it. Make it a full release (x.y.0). --accept names such a pom.xml whose change
the maintainer knows touches the build only (a plugin, a profile), never what a module ships: it is reported, and
publishes nothing.

The report (what changed, why each module is in the plan, what was left out) goes to stderr.
"""
import json
import pathlib
import subprocess
import sys
import xml.etree.ElementTree as ET

CONFIG = ".forgejo/release-patch.json"
BOM = "vidocq-runtime/vidocq-runtime-bom/pom.xml"
SKIPPED_PARTS = {"target", "target-ide", "node_modules", "src"}


class PlanError(Exception):
    """A change a patch release cannot carry."""


def _ns(root):
    return root.tag[1:root.tag.index("}")] if root.tag.startswith("{") else ""


def _find(element, ns, name):
    return element.find("{%s}%s" % (ns, name) if ns else name)


def _text(element, ns, name):
    found = _find(element, ns, name)
    return found.text.strip() if found is not None and found.text else None


def read_pom(path):
    """(groupId, artifactId, packaging) of the project of path."""
    root = ET.parse(path).getroot()
    ns = _ns(root)
    group = _text(root, ns, "groupId")
    if group is None:
        parent = _find(root, ns, "parent")
        group = _text(parent, ns, "groupId") if parent is not None else None
    return group, _text(root, ns, "artifactId"), _text(root, ns, "packaging") or "jar"


def reactor(root):
    """folder (relative, '.' for the root) -> (groupId, artifactId, packaging), for every pom.xml of the tree."""
    modules = {}
    for pom in root.rglob("pom.xml"):
        relative = pom.parent.relative_to(root)
        if SKIPPED_PARTS.intersection(relative.parts) or any(p.startswith(".") for p in relative.parts):
            continue
        try:
            modules[relative.as_posix() or "."] = read_pom(pom)
        except ET.ParseError:
            continue
    return modules


def managed_by_bom(root):
    """groupId:artifactId of every dependency the BOM manages."""
    tree = ET.parse(root / BOM).getroot()
    ns = _ns(tree)
    management = _find(tree, ns, "dependencyManagement")
    result = set()
    if management is not None:
        for dependency in management.iter("{%s}dependency" % ns if ns else "dependency"):
            result.add("%s:%s" % (_text(dependency, ns, "groupId"), _text(dependency, ns, "artifactId")))
    return result


def changed_files(root, since):
    out = subprocess.run(["git", "-C", str(root), "diff", "--name-only", since + "..HEAD"],
                         check=True, capture_output=True, text=True).stdout
    return [line for line in out.splitlines() if line.strip()]


def owner(path, modules):
    """The folder of the nearest pom.xml above path."""
    folder = pathlib.PurePosixPath(path).parent
    while True:
        key = folder.as_posix() if folder.as_posix() != "" else "."
        if key in modules:
            return key
        if key == ".":
            return None
        folder = folder.parent


def plan(root, since, files=None, accepted=()):
    """(sorted folders, report lines). files replaces the git diff (tests); accepted lists build-only parent poms."""
    root = pathlib.Path(root)
    config = json.loads((root / CONFIG).read_text())
    modules = reactor(root)
    bom = managed_by_bom(root)
    always = list(config["always"])
    for folder in always:
        if folder not in modules:
            raise PlanError("%s names %s, which has no pom.xml" % (CONFIG, folder))
    published = {f for f, (g, a, _) in modules.items() if "%s:%s" % (g, a) in bom} | set(always)
    by_artifact = {}
    for folder in published:
        by_artifact.setdefault(modules[folder][1], []).append(folder)

    report, chosen, left_out = [], {}, []
    for path in (changed_files(root, since) if files is None else files):
        folder = owner(path, modules)
        if folder is None:
            left_out.append("%s (no module)" % path)
            continue
        inside = path[len(folder) + 1:] if folder != "." else path
        if folder in published:
            if inside == "pom.xml" or inside.startswith("src/main/"):
                chosen.setdefault(folder, "changed: %s" % path)
            else:
                left_out.append("%s (not shipped by %s)" % (path, modules[folder][1]))
        elif inside == "pom.xml" and any(p == folder or folder == "." or p.startswith(folder + "/")
                                         for p in published):
            if path in accepted:
                left_out.append("%s (a parent, accepted as a change of the build only)" % path)
                continue
            raise PlanError("%s changed: the pom of a parent or a grouping pom changes every module below it, a "
                            "patch release cannot carry it: make it a full release (x.y.0)" % path)
        else:
            left_out.append("%s (%s is not published)" % (path, modules[folder][1]))

    suffixes = config.get("companions", [])
    pending = list(chosen)
    while pending:  # a companion's extension brings its other companions
        folder = pending.pop()
        artifact = modules[folder][1]
        related = [artifact + s for s in suffixes] + [artifact[:-len(s)] for s in suffixes if artifact.endswith(s)]
        for name in related:
            for companion in by_artifact.get(name, []):
                if companion not in chosen:
                    chosen[companion] = "companion of %s" % artifact
                    pending.append(companion)
    for folder in always:
        chosen.setdefault(folder, "always (%s)" % CONFIG)

    for folder in sorted(chosen):
        report.append("  %-60s %s" % (modules[folder][1], chosen[folder]))
    if left_out:
        report.append("left out:")
        report.extend("  " + line for line in left_out)
    return sorted(chosen), report


def main(argv):
    args, accepted = list(argv[1:]), []
    while len(args) >= 2 and args[0] == "--accept":
        accepted.append(args[1])
        args = args[2:]
    if len(args) not in (1, 2) or any(a.startswith("--") for a in args):
        print(__doc__, file=sys.stderr)
        return 2
    try:
        folders, report = plan(args[1] if len(args) == 2 else ".", args[0], accepted=accepted)
    except PlanError as e:
        print("patch-plan: " + str(e), file=sys.stderr)
        return 1
    print("patch release since %s: %d modules" % (args[0], len(folders)), file=sys.stderr)
    print("\n".join(report), file=sys.stderr)
    print("\n".join(folders))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
