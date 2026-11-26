#!/usr/bin/env python3
"""The compatibility of a patch release: each module it publishes against the release it replaces.

    patch-compat.py <folder>...          run from the reactor root, after `mvn -pl <folders> package -DskipTests`

A patch release publishes only the modules that changed; the others stay at the version of their last release and
were compiled against the previous one of each module of the patch. So a module of the patch must stay binary and
source compatible with the jar it replaces (an abstract method added to an interface is binary compatible, and
still breaks an implementation compiled against the previous jar when it is called). Each folder's jar (target/<artifactId>-<version>.jar, its version not moved yet)
is compared with the same coordinates on Maven Central by japicmp (a build tool, never shipped:
com.github.siom79.japicmp:japicmp), and any binary or source incompatibility fails the script. A folder without a jar (a pom,
the BOM) is skipped, and so is a module Central does not have yet (new in this patch).
"""
import pathlib
import re
import subprocess
import sys
import tempfile

JAPICMP = "com.github.siom79.japicmp:japicmp:0.23.1:jar:jar-with-dependencies"


def coordinates(folder):
    """(groupId, artifactId, version, packaging) of the project in folder, inherited from its parent when absent."""
    text = re.sub(r"<!--.*?-->", "", (pathlib.Path(folder) / "pom.xml").read_text(encoding="utf-8"), flags=re.S)
    parent = re.search(r"<parent>(.*?)</parent>", text, re.S)
    body = text[parent.end():] if parent else text
    head = re.split(r"<(?:properties|dependencyManagement|dependencies|build|profiles|modules)\b", body, maxsplit=1)[0]

    def element(name, scope):
        m = re.search(r"<%s>\s*([^<\s]+)\s*</%s>" % (name, name), scope)
        return m.group(1) if m else None

    group = element("groupId", head) or (parent and element("groupId", parent.group(1)))
    version = element("version", head) or (parent and element("version", parent.group(1)))
    return group, element("artifactId", head), version, element("packaging", head) or "jar"


def mvn_copy(artifact, into):
    """Copies artifact (g:a:v[:type[:classifier]]) from the remote repositories into the folder; False when absent.

    Through a local repository of its own: the runner's may hold a jar of the same version built from the
    maintenance branch, which is not the released one.
    """
    repository = into.parent / "m2"
    result = subprocess.run(["./mvnw", "-B", "-q", "-ntp", "-N", "-Dgib.disable=true",
                             "-Dmaven.repo.local=" + str(repository),
                             "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy",
                             "-Dartifact=" + artifact, "-DoutputDirectory=" + str(into),
                             "-Dmdep.useBaseVersion=true"], capture_output=True, text=True)
    return result.returncode == 0


def main(argv):
    folders = [f.rstrip("/") for f in argv[1:] if f.strip()]
    if not folders:
        print(__doc__, file=sys.stderr)
        return 2
    work = pathlib.Path(tempfile.mkdtemp(prefix="patch-compat-"))
    if not mvn_copy(JAPICMP, work / "tool"):
        print("patch-compat: cannot fetch japicmp (%s)" % JAPICMP, file=sys.stderr)
        return 1
    tool = next((work / "tool").glob("japicmp-*.jar"))
    broken, compared = [], 0
    for folder in folders:
        group, artifact, version, packaging = coordinates(folder)
        if packaging == "pom":
            continue
        new = pathlib.Path(folder) / "target" / ("%s-%s.jar" % (artifact, version))
        if not new.is_file():
            print("patch-compat: %s has no %s; package the patch modules first" % (folder, new.name), file=sys.stderr)
            return 1
        old_dir = work / artifact
        if not mvn_copy("%s:%s:%s" % (group, artifact, version), old_dir):
            print("  %-55s new in this patch, nothing to compare" % artifact)
            continue
        old = next(old_dir.glob("*.jar"))
        result = subprocess.run(["java", "-jar", str(tool), "-o", str(old), "-n", str(new), "--only-modified",
                                 "--ignore-missing-classes", "--error-on-binary-incompatibility",
                                 "--error-on-source-incompatibility"],
                                capture_output=True, text=True)
        compared += 1
        if result.returncode != 0:
            broken.append(artifact)
            print("  %-55s INCOMPATIBLE with %s" % (artifact, version))
            print("\n".join("      " + line for line in (result.stdout + result.stderr).splitlines()[-40:]))
        else:
            changes = [line for line in result.stdout.splitlines() if line.startswith(("***", "+++", "---"))]
            print("  %-55s compatible with %s (%d changed types)" % (artifact, version, len(changes)))
    if broken:
        print("patch-compat: %d module(s) break the compatibility of their release: %s. A patch release "
              "cannot publish them: make it a full release (x.y.0)." % (len(broken), ", ".join(broken)),
              file=sys.stderr)
        return 1
    print("patch-compat: %d jar(s) compared, all compatible" % compared)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
