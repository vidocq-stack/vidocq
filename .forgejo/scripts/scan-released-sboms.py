#!/usr/bin/env python3
"""OSV-Scanner over the CycloneDX SBOMs of the released versions, as Maven Central holds them (Vidocq/vidocq#269).

    scan-released-sboms.py [--osv <osv-scanner binary>] [--work <dir>] [<version>...]

The pull request and nightly scans read the SBOM of the build at hand; a vulnerability published after a release is
only caught by scanning what that release shipped again. Which releases: the latest x.y.Z of the newest line, and the
latest of every line with a maintenance/x.y branch (git tags v<x>.<y>.<z> and `git ls-remote` of origin), or the
versions given. Which SBOMs, read from repo1.maven.org (no Maven resolution, no repository credentials):
  - the aggregate SBOM of the release, io.vidocq:vidocq-build:<version>:json:cyclonedx, when it has one (#796);
  - otherwise one SBOM per published module (0.4.0): every io.vidocq artifact that the released vidocq-runtime-bom
    manages at that version, the BOM itself, and the folders .forgejo/release-patch.json always publishes (the
    plugin, the CLI, the parents). A module without an SBOM on Central is listed in the report, and skipped.
OSV-Scanner is the release Vidocq/ci pins in sonar-analysis/security-scans.sh, downloaded from GitHub and checked
against its SHA-256 unless --osv names a binary. It queries osv.dev for the packages the SBOMs list.

The report (Markdown) goes to stdout and, in CI, to the job summary. Exit 0: no known vulnerability; 1: at least one
(the scheduled job turns red and Slack says so; nothing blocks a merge); 2: the scan could not run.
"""
import hashlib
import json
import os
import pathlib
import platform
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

CENTRAL = "https://repo1.maven.org/maven2"
AGGREGATE = ("io.vidocq", "vidocq-build")
BOM = ("io.vidocq.runtime", "vidocq-runtime-bom")
RELEASE_PATCH = ".forgejo/release-patch.json"
RELEASE_TAG = re.compile(r"^v(\d+)\.(\d+)\.(\d+)$")
MAINTENANCE = re.compile(r"^(?:refs/heads/)?maintenance/(\d+)\.(\d+)$")

# The release of Vidocq/ci's sonar-analysis/security-scans.sh: change both together.
OSV_VERSION = "2.6.0"
OSV_ASSETS = {
    "x86_64": ("osv-scanner_linux_amd64", "ca69b3d3cd08f889a49dc0a383122f71cc528b83803671df5fd874d97485b108"),
    "aarch64": ("osv-scanner_linux_arm64", "2c71403eb443d05891c4f268c3ad771cf4f16e5443463fd7851ef8f454d3c7e4"),
}
OSV_ASSETS["amd64"] = OSV_ASSETS["x86_64"]
OSV_ASSETS["arm64"] = OSV_ASSETS["aarch64"]


class ScanError(Exception):
    """The scan could not run, or saw nothing to scan."""


def select_releases(tags, branches):
    """The versions to scan: the latest release of the newest line, and of every line with a maintenance branch."""
    lines = {}
    for tag in tags:
        match = RELEASE_TAG.match(tag.strip())
        if match:
            version = tuple(int(part) for part in match.groups())
            line = version[:2]
            lines[line] = max(lines.get(line, version), version)
    if not lines:
        return []
    maintained = {max(lines)}
    for branch in branches:
        match = MAINTENANCE.match(branch.strip())
        if match and (int(match.group(1)), int(match.group(2))) in lines:
            maintained.add((int(match.group(1)), int(match.group(2))))
    return [".".join(map(str, lines[line])) for line in sorted(maintained)]


def artifact_url(group, artifact, version, suffix):
    return "%s/%s/%s/%s/%s-%s%s" % (CENTRAL, group.replace(".", "/"), artifact, version, artifact, version, suffix)


def sbom_url(group, artifact, version):
    return artifact_url(group, artifact, version, "-cyclonedx.json")


def _ns(root):
    return root.tag[1:root.tag.index("}")] if root.tag.startswith("{") else ""


def _q(ns, name):
    return "{%s}%s" % (ns, name) if ns else name


def _text(element, ns, name):
    found = element.find(_q(ns, name)) if element is not None else None
    return found.text.strip() if found is not None and found.text else None


def managed_modules(bom_pom, version):
    """(groupId, artifactId) of every io.vidocq artifact the BOM manages at version."""
    root = ET.fromstring(bom_pom)
    ns = _ns(root)
    result = set()
    for dependency in root.iter(_q(ns, "dependency")):
        group, artifact = _text(dependency, ns, "groupId"), _text(dependency, ns, "artifactId")
        if group and artifact and _text(dependency, ns, "version") == version and \
                (group == "io.vidocq" or group.startswith("io.vidocq.")):
            result.add((group, artifact))
    return result


def sbom_sources(version, get, extras):
    """("aggregate" | "per module", [(groupId:artifactId, SBOM URL)]) of a release; get(url) -> bytes | None (404)."""
    aggregate = sbom_url(*AGGREGATE, version)
    if get(aggregate) is not None:
        return "aggregate", [("%s:%s" % AGGREGATE, aggregate)]
    bom = get(artifact_url(*BOM, version, ".pom"))
    if bom is None:
        raise ScanError("%s: neither %s:%s nor %s:%s on Maven Central" % (version, *AGGREGATE, *BOM))
    modules = managed_modules(bom, version) | {BOM} | set(extras)
    return "per module", [("%s:%s" % m, sbom_url(*m, version)) for m in sorted(modules, key=lambda m: "%s:%s" % m)]


def always_published(root):
    """(groupId, artifactId) of the folders .forgejo/release-patch.json always publishes, as the tree names them."""
    config = json.loads((root / RELEASE_PATCH).read_text(encoding="utf-8"))
    result = []
    for folder in config.get("always", []):
        pom = root / folder / "pom.xml"
        if not pom.is_file():
            continue
        project = ET.parse(pom).getroot()
        ns = _ns(project)
        group = _text(project, ns, "groupId") or _text(project.find(_q(ns, "parent")), ns, "groupId")
        artifact = _text(project, ns, "artifactId")
        if group and artifact:
            result.append((group, artifact))
    return result


def http_get(address):
    """The body at address, None on a 404; retried on a network error or a 5xx."""
    request = urllib.request.Request(address, headers={"User-Agent": "vidocq-ci-sbom-scan"})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                return response.read()
        except urllib.error.HTTPError as error:
            if error.code == 404:
                return None
            if error.code < 500 or attempt == 3:
                raise ScanError("%s: HTTP %d" % (address, error.code)) from error
        except (urllib.error.URLError, TimeoutError) as error:
            if attempt == 3:
                raise ScanError("%s: %s" % (address, error)) from error
        time.sleep(5 * (attempt + 1))
    raise ScanError(address)  # not reached


def osv_binary(directory):
    """Downloads the pinned OSV-Scanner release of this machine's architecture and checks its SHA-256."""
    machine = platform.machine().lower()
    if machine not in OSV_ASSETS:
        raise ScanError("no pinned OSV-Scanner release for the architecture " + machine)
    asset, digest = OSV_ASSETS[machine]
    address = "https://github.com/google/osv-scanner/releases/download/v%s/%s" % (OSV_VERSION, asset)
    body = http_get(address)
    if body is None or hashlib.sha256(body).hexdigest() != digest:
        raise ScanError("OSV-Scanner %s: cannot download %s, or its SHA-256 does not match" % (OSV_VERSION, address))
    binary = directory / "osv-scanner"
    binary.write_bytes(body)
    binary.chmod(0o755)
    return binary


def osv_runner(binary):
    """osv(files) -> (exit code, JSON answer) of OSV-Scanner over the given CycloneDX files."""
    def run(files):
        command = [str(binary), "scan", "source", "--format", "json", "--all-packages"]
        for file in files:
            command += ["-L", str(file)]
        completed = subprocess.run(command, capture_output=True, text=True, check=False)
        sys.stderr.write(completed.stderr)
        return completed.returncode, completed.stdout
    return run


def components(document):
    """(name, version) -> groupId:artifactId, from the components of a CycloneDX SBOM: OSV-Scanner names a Maven
    package by its artifactId alone."""
    names = {}
    for component in json.loads(document).get("components") or []:
        if component.get("name") and component.get("version"):
            full = "%s:%s" % (component["group"], component["name"]) if component.get("group") else component["name"]
            names[(component["name"], component["version"])] = full
    return names


def findings(answer, modules, names):
    """[(package, version, vulnerability, severity, module)] of an OSV-Scanner answer; modules: file name -> module."""
    result = []
    for source in answer.get("results") or []:
        module = modules.get(pathlib.Path(source["source"]["path"]).name, source["source"]["path"])
        for package in source.get("packages") or []:
            name, version = package["package"]["name"], package["package"].get("version", "")
            scores = {i: g.get("max_severity", "") for g in package.get("groups") or [] for i in g.get("ids", [])}
            for vulnerability in package.get("vulnerabilities") or []:
                cves = [a for a in vulnerability.get("aliases") or [] if a.startswith("CVE-")]
                label = vulnerability["id"] + (" (%s)" % ", ".join(cves) if cves else "")
                severity = " ".join(filter(None, [(vulnerability.get("database_specific") or {}).get("severity"),
                                                  scores.get(vulnerability["id"])]))
                result.append((names.get((name, version), name), version, label, severity or "?", module))
    return result


def plural(count, one, many):
    return "%d %s" % (count, one if count == 1 else many)


def count_packages(answer):
    return len({(p["package"]["name"], p["package"].get("version")) for s in answer.get("results") or []
                for p in s.get("packages") or []})


def scan_release(version, get, osv, work, extras):
    """(Markdown section, number of vulnerabilities) of one release."""
    kind, sources = sbom_sources(version, get, extras)
    folder = work / version
    folder.mkdir(parents=True, exist_ok=True)
    files, modules, names, missing = [], {}, {}, []
    for module, address in sources:
        document = get(address)
        if document is None:
            missing.append(module)
            continue
        # OSV-Scanner recognises a CycloneDX SBOM by its file name (*.cdx.json), and silently scans nothing of
        # <artifactId>-<version>-cyclonedx.json.
        file = folder / (module.replace(":", "_") + ".cdx.json")
        file.write_bytes(document)
        files.append(file)
        modules[file.name] = module
        names.update(components(document))
    if not files:
        raise ScanError("%s: no SBOM on Maven Central" % version)
    code, output = osv(files)
    if code not in (0, 1):
        raise ScanError("%s: OSV-Scanner failed (exit %d)" % (version, code))
    try:
        answer = json.loads(output)
    except ValueError as error:
        raise ScanError("%s: cannot read the answer of OSV-Scanner" % version) from error
    found = findings(answer, modules, names)
    lines = ["### %s: %s (%s), %s" % (version, plural(len(files), "SBOM", "SBOMs"), kind,
                                       plural(count_packages(answer), "package", "packages")), ""]
    if found:
        vulnerabilities = {(f[0], f[1], f[2]) for f in found}
        packages = {(f[0], f[1]) for f in found}
        lines += ["**%s in %s.**" % (plural(len(vulnerabilities), "vulnerability", "vulnerabilities"),
                                     plural(len(packages), "package", "packages")), "",
            "| Package | Version | Vulnerability | Severity | Shipped by |", "|---|---|---|---|---|"]
        shipped = {}
        for package, package_version, label, severity, module in found:
            shipped.setdefault((package, package_version, label, severity), set()).add(module)
        for (package, package_version, label, severity), owners in sorted(shipped.items()):
            lines.append("| %s | %s | %s | %s | %s |" % (package, package_version, label, severity,
                                                         ", ".join(sorted(owners))))
    else:
        lines.append("OSV-Scanner reports no known vulnerability.")
    if missing:
        lines += ["", "No SBOM on Maven Central for %s: %s." % (
            plural(len(missing), "module", "modules"), ", ".join(missing))]
    return "\n".join(lines) + "\n", len({(f[0], f[1], f[2]) for f in found})


def scan(versions, get, osv, work, extras):
    """(exit code, Markdown report) of the scan of the given releases."""
    if not versions:
        raise ScanError("no release to scan")
    sections, total = [], 0
    for version in versions:
        section, count = scan_release(version, get, osv, work, extras)
        sections.append(section)
        total += count
    head = "## OSV-Scanner %s on the released SBOMs (%s)\n\n" % (OSV_VERSION, ", ".join(versions))
    return (1 if total else 0), head + "\n".join(sections)


def write_summary(report, environment):
    path = environment.get("GITHUB_STEP_SUMMARY")
    if path:
        with open(path, "a", encoding="utf-8") as summary:
            summary.write(report)


def git_lines(*arguments):
    completed = subprocess.run(["git", *arguments], capture_output=True, text=True, check=True)
    return [line for line in completed.stdout.splitlines() if line.strip()]


def main(argv):
    arguments = list(argv[1:])
    options = {}
    while arguments and arguments[0] in ("--osv", "--work"):
        option = arguments.pop(0)
        if not arguments:
            print("::error title=Released SBOM scan::%s needs a value" % option)
            return 2
        options[option] = arguments.pop(0)
    root = pathlib.Path.cwd()
    try:
        if arguments:
            versions = arguments
        else:
            tags = git_lines("tag", "--list", "v*")
            branches = [line.split()[1] for line in git_lines("ls-remote", "--heads", "origin", "maintenance/*")]
            versions = select_releases(tags, branches)
        with tempfile.TemporaryDirectory() as scratch:
            work = pathlib.Path(options.get("--work") or scratch)
            work.mkdir(parents=True, exist_ok=True)
            binary = pathlib.Path(options["--osv"]) if "--osv" in options else osv_binary(pathlib.Path(scratch))
            code, report = scan(versions, http_get, osv_runner(binary), work, always_published(root))
    except (ScanError, subprocess.CalledProcessError, OSError) as error:
        print("::error title=Released SBOM scan::%s" % error)
        return 2
    print(report)
    write_summary(report, os.environ)
    if code:
        print("::error title=Released SBOM scan::known vulnerabilities in a released version, see the summary")
    return code


if __name__ == "__main__":
    sys.exit(main(sys.argv))
