#!/usr/bin/env bash
# Verifies that every jar this reactor publishes is a named module: it carries a compiled module-info.class, so that
# no Vidocq artifact ever lands on an application's module path as an automatic module (Vidocq/vidocq#177; why it
# matters: Vidocq/grimm#15, where an automatic jar's package was split into the application module and its beans lost
# without a word).
#
# Run from the repository root, after `mvn install` (or `package`): it reads each module's built jar. It walks the
# reactor's <modules> from the root pom; a module whose own pom sets maven.deploy.skip to true is not published and is
# skipped with all of its modules (the examples, the integration tests); a pom or maven-plugin module is skipped too
# (whether Maven plugins must be named modules is decided apart from #177). Exits non-zero, naming every offender.
set -euo pipefail
cd "$(dirname "$0")"

JAR="${JAVA_HOME:+$JAVA_HOME/bin/}jar"

python3 - "$JAR" <<'PY'
import os, subprocess, sys, xml.etree.ElementTree as ET

jar_tool = sys.argv[1]

def read(pom):
    root = ET.parse(pom).getroot()
    ns = root.tag[1:].split("}", 1)[0] if root.tag.startswith("{") else ""
    q = (lambda name: f"{{{ns}}}{name}") if ns else (lambda name: name)
    def text(*path):
        node = root
        for name in path:
            node = node.find(q(name)) if node is not None else None
        return node.text.strip() if node is not None and node.text else None
    modules = [m.text.strip() for m in root.findall(f"{q('modules')}/{q('module')}") if m.text]
    return {
        "artifactId": text("artifactId"),
        "version": text("version") or text("parent", "version"),
        "packaging": text("packaging") or "jar",
        "skip": (text("properties", "maven.deploy.skip") or "").lower() == "true",
        "modules": modules,
    }

checked, offenders, missing = 0, [], []

def walk(directory):
    global checked
    pom = read(os.path.join(directory, "pom.xml"))
    if pom["skip"]:
        return
    for module in pom["modules"]:
        walk(os.path.normpath(os.path.join(directory, module)))
    if pom["packaging"] != "jar":
        return
    built = os.path.join(directory, "target", f"{pom['artifactId']}-{pom['version']}.jar")
    if not os.path.isfile(built):
        missing.append(built)
        return
    checked += 1
    described = subprocess.run([jar_tool, "--describe-module", "--file", built],
                               capture_output=True, text=True).stdout
    if "No module descriptor found" in described or "automatic" in described.lower():
        offenders.append(built)

walk(".")
for path in missing:
    print(f"check-named-modules: not built, run mvn install first: {path}", file=sys.stderr)
for path in offenders:
    print(f"check-named-modules: no module-info.class, an automatic module: {path}", file=sys.stderr)
if missing or offenders:
    sys.exit(1)
print(f"check-named-modules: {checked} published jars, every one a named module")
PY
