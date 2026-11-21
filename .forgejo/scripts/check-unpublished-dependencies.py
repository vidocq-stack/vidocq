#!/usr/bin/env python3
"""Fails when a module of the reactor depends on a module whose SNAPSHOT is never deployed.

A pull request build (gitflow-incremental-builder) rebuilds the modules a change reaches and resolves the others from
maven.vidocq.dev. A module that is never deployed is then found nowhere when a module that depends on it is rebuilt
without it (vidocq#723: vauban-producer-it-bean-b rebuilt, vauban-producer-it-lib-a not). Such a module keeps
maven.deploy.skip=true, so that release.sh (Vidocq/ci) leaves it out of a release, and deploys its SNAPSHOT with an
explicit maven-deploy-plugin <skip>false</skip> (vidocq#724).

Reads the POMs as release.sh does: the reactor is the <modules> of the root pom, walked down (modules of a profile are
not in it); maven.deploy.skip comes from a module's own <properties>, else from its <parent> when that is a reactor
module. Dependencies inside a <profile> or <dependencyManagement> are not counted.

Run from the repository root: python3 .forgejo/scripts/check-unpublished-dependencies.py
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def parse(pom):
    text = pom.read_text(encoding="utf-8", errors="replace")
    match = re.search(r'<project\b[^>]*\bxmlns="([^"]+)"', text)
    if not match:
        return None, None
    try:
        return ET.fromstring(text), match.group(1)
    except ET.ParseError:
        return None, None


modules = {}  # (groupId, artifactId) -> dict
order = []
seen = set()


def walk(pom):
    resolved = pom.resolve()
    if resolved in seen or not pom.exists():
        return
    seen.add(resolved)
    root, ns = parse(pom)
    if root is None:
        return

    def q(tag):
        return "{%s}%s" % (ns, tag)

    def text(element, tag):
        return (element.findtext(q(tag)) or "").strip() if element is not None else ""

    parent = root.find(q("parent"))
    group = text(root, "groupId") or text(parent, "groupId")
    artifact = text(root, "artifactId")
    props = root.find(q("properties"))
    own_skip = None
    if props is not None and props.find(q("maven.deploy.skip")) is not None:
        own_skip = text(props, "maven.deploy.skip").lower() == "true"
    deploys_anyway = False
    build = root.find(q("build"))
    plugins = build.find(q("plugins")) if build is not None else None
    for plugin in plugins.findall(q("plugin")) if plugins is not None else []:
        if text(plugin, "artifactId") == "maven-deploy-plugin":
            deploys_anyway = text(plugin.find(q("configuration")), "skip").lower() == "false"
    deps = []
    dependencies = root.find(q("dependencies"))
    for dep in dependencies.findall(q("dependency")) if dependencies is not None else []:
        deps.append((text(dep, "groupId"), text(dep, "artifactId")))
    key = (group, artifact)
    modules[key] = {
        "pom": pom,
        "own_skip": own_skip,
        "parent": (text(parent, "groupId"), text(parent, "artifactId")) if parent is not None else None,
        "deploys_anyway": deploys_anyway,
        "deps": deps,
    }
    order.append(key)
    module_list = root.find(q("modules"))
    for module in module_list.findall(q("module")) if module_list is not None else []:
        if (module.text or "").strip():
            walk(pom.parent / module.text.strip() / "pom.xml")


def deploy_skip(key, depth=0):
    module = modules[key]
    if module["own_skip"] is not None:
        return module["own_skip"]
    if module["parent"] in modules and depth < 30:
        return deploy_skip(module["parent"], depth + 1)
    return False


def main():
    walk(Path("pom.xml"))
    never_deployed = {key for key in order if deploy_skip(key) and not modules[key]["deploys_anyway"]}
    problems = []
    for key in order:
        for dep in modules[key]["deps"]:
            dep_key = dep if dep[0] and not dep[0].startswith("${") else (key[0], dep[1])
            if dep_key in never_deployed and dep_key != key:
                problems.append((dep_key, key))
    if not problems:
        print("check-unpublished-dependencies: %d modules, every module another one depends on deploys its SNAPSHOT"
              % len(order))
        return 0
    for (dep, user) in problems:
        print("::error file=%s::%s depends on %s, which never deploys its SNAPSHOT: a pull request build that rebuilds "
              "%s without it cannot resolve it. Keep maven.deploy.skip=true and add maven-deploy-plugin "
              "<skip>false</skip> to %s (vidocq#724)."
              % (modules[dep]["pom"], user[1], dep[1], user[1], dep[1]))
    return 1


if __name__ == "__main__":
    sys.exit(main())
