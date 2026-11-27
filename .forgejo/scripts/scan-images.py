#!/usr/bin/env python3
"""Trivy over the jlink and Docker images of the runtime examples (Vidocq/vidocq#269).

    scan-images.py [--trivy <trivy binary>] [--work <dir>] <example folder>...

The nightly builds the examples with their dist profile: vidocq:jlink links the runtime image into target/dist, and
vidocq:docker writes target/Dockerfile, which copies that image onto a base image (distroless by default). The
SonarQube job's Trivy scans the sources only (secrets, Dockerfile misconfigurations); this one looks for known
vulnerabilities in what an image ships:
  - the jlink image, through the CycloneDX SBOM it carries (`trivy sbom`): the modules it links into lib/modules,
    a jimage file Trivy cannot read, so that `trivy rootfs` sees no package there (2026-11-27). An image without an
    SBOM is scanned with `trivy rootfs`. No Docker daemon;
  - the Docker image: when this machine has a Docker daemon, the image built from target/Dockerfile, with
    `trivy image`; otherwise, or when that build fails, the base image the Dockerfile names (its last stage), read
    from its registry. The built image is that base plus the jlink image: the two scans cover it.
A base image several examples share is scanned once.

Trivy is the release Vidocq/ci pins in sonar-analysis/security-scans.sh, downloaded from GitHub and checked against
its SHA-256 unless --trivy names a binary. Its vulnerability databases come from its own registries (mirror.gcr.io,
ghcr.io), never from Maven Central.

Report mode, like the scans of the SonarQube job: the report (Markdown) goes to stdout and, in CI, to the job summary,
a HIGH or CRITICAL vulnerability and a scan that could not run become warnings, and the exit code is 0. The nightly's
result is what a release requires (release-gate.py): a finding must not stop a release before the blocking mode is
decided (#269).
"""
import hashlib
import json
import os
import pathlib
import platform
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.error
import urllib.request

# The release of Vidocq/ci's sonar-analysis/security-scans.sh: change both together.
TRIVY_VERSION = "0.75.0"
TRIVY_ASSETS = {
    "x86_64": ("trivy_%s_Linux-64bit.tar.gz" % TRIVY_VERSION,
               "c6e65abddb348e25f10549df887045629cf28cc72453cd1c63acb717316b3f3f"),
    "aarch64": ("trivy_%s_Linux-ARM64.tar.gz" % TRIVY_VERSION,
                "a1ee9f6ffb7d112b64ff726a2a0717c21175c1114361391f4a132956751a13b3"),
}
TRIVY_ASSETS["amd64"] = TRIVY_ASSETS["x86_64"]
TRIVY_ASSETS["arm64"] = TRIVY_ASSETS["aarch64"]

SEVERITIES = ("CRITICAL", "HIGH", "MEDIUM", "LOW", "UNKNOWN")
SERIOUS = ("CRITICAL", "HIGH")


class ScanError(Exception):
    """The scan could not run."""


class Findings:
    """The vulnerabilities of one Trivy report: counts by severity, and the HIGH and CRITICAL ones."""

    def __init__(self, counts, serious):
        self.counts = counts
        self.serious = serious

    @property
    def total(self):
        return sum(self.counts.values())


class Report:
    """The Markdown report, the number of distinct HIGH or CRITICAL vulnerabilities, and what was not scanned."""

    def __init__(self, markdown, serious, not_scanned):
        self.markdown = markdown
        self.serious = serious
        self.not_scanned = not_scanned


class Docker:
    """Whether this machine has a Docker daemon, how to build an image (build(tag, context) -> bool) and to remove
    it once scanned (remove(tag))."""

    def __init__(self, available, build=None, remove=None):
        self.available = available
        self.build = build
        self.remove = remove or (lambda tag: None)


def base_image(dockerfile):
    """The base image of the last stage of a Dockerfile, through the stages it is built on; None without FROM."""
    stages, last = {}, None
    for line in dockerfile.splitlines():
        words = line.split()
        if not words or words[0].upper() != "FROM":
            continue
        words = [word for word in words[1:] if not word.startswith("--")]
        if not words:
            continue
        image = stages.get(words[0].lower(), words[0])
        if len(words) >= 3 and words[1].upper() == "AS":
            stages[words[2].lower()] = image
        last = image
    return last


def findings(answer):
    """Findings of a Trivy JSON report; a vulnerability of a package counts once, whatever the targets naming it."""
    counts = {severity: 0 for severity in SEVERITIES}
    serious, seen = [], set()
    for result in json.loads(answer).get("Results") or []:
        for found in result.get("Vulnerabilities") or []:
            key = (found.get("VulnerabilityID"), found.get("PkgName"), found.get("InstalledVersion"))
            if key in seen:
                continue
            seen.add(key)
            severity = found.get("Severity", "UNKNOWN").upper()
            severity = severity if severity in counts else "UNKNOWN"
            counts[severity] += 1
            if severity in SERIOUS:
                serious.append(dict(found, Severity=severity, Target=result.get("Target", "")))
    serious.sort(key=lambda row: (SEVERITIES.index(row["Severity"]), row.get("VulnerabilityID", "")))
    return Findings(counts, serious)


def trivy_command(binary, mode, target, output, cache):
    command = [str(binary)] + mode + [
        "--scanners", "vuln", "--format", "json", "--output", str(output), "--cache-dir", str(cache),
        "--exit-code", "0", "--quiet", "--timeout", "15m", str(target)]
    return command


def run_trivy(binary, mode, target, work):
    """findings of one Trivy run, or the exit code of a run that failed."""
    output = pathlib.Path(tempfile.mkstemp(suffix=".json", dir=work)[1])
    completed = subprocess.run(trivy_command(binary, mode, target, output, work / "cache"), check=False)
    if completed.returncode != 0 or output.stat().st_size == 0:
        return completed.returncode or 1
    return findings(output.read_text(encoding="utf-8"))


def scan(examples, binary, work, docker):
    """Scans the images of the examples; returns the report."""
    rows, serious, not_scanned, bases = [], [], [], {}

    def record(example, what, result, failure):
        if isinstance(result, int):
            not_scanned.append("%s: Trivy failed on %s (exit %d)" % (example, failure, result))
            return
        rows.append((example, what, result))
        serious.extend(dict(row, Example=example) for row in result.serious)

    for folder in examples:
        folder = pathlib.Path(folder)
        name, target = folder.name, folder / "target"
        dist = target / "dist"
        if dist.is_dir():
            sboms = sorted(dist.glob("*-cyclonedx.json"))
            if sboms:
                record(name, "jlink image (its SBOM)", run_trivy(binary, ["sbom"], sboms[0], work), "the jlink image")
            else:
                record(name, "jlink image (rootfs)", run_trivy(binary, ["rootfs"], dist, work), "the jlink image")
        else:
            not_scanned.append("%s: no jlink image in target/dist" % name)
        dockerfile = target / "Dockerfile"
        if not dockerfile.is_file():
            not_scanned.append("%s: no target/Dockerfile (its dist profile has no vidocq:docker)" % name)
            continue
        note = ""
        if docker.available:
            tag = "vidocq-image-scan/%s:nightly" % name
            if docker.build(tag, target):
                record(name, "Docker image " + tag,
                       run_trivy(binary, ["image", "--image-src", "docker"], tag, work), "the Docker image")
                docker.remove(tag)
                continue
            note = ", docker build failed"
        base = base_image(dockerfile.read_text(encoding="utf-8"))
        if base is None:
            not_scanned.append("%s: no FROM in target/Dockerfile" % name)
            continue
        if base not in bases:
            bases[base] = run_trivy(binary, ["image", "--image-src", "remote"], base, work)
        where = "registry" + (note or ", no Docker daemon")
        record(name, "base image %s (%s)" % (base, where), bases[base], "the base image " + base)

    distinct = {(row.get("VulnerabilityID"), row.get("PkgName"), row.get("InstalledVersion")) for row in serious}
    return Report(markdown(rows, serious, not_scanned), len(distinct), not_scanned)


def markdown(rows, serious, not_scanned):
    lines = ["## Image scan (Trivy %s)" % TRIVY_VERSION, "",
             "Known vulnerabilities in the jlink and Docker images of the runtime examples. Report mode: nothing "
             "fails on a finding (Vidocq/vidocq#269).", ""]
    if rows:
        lines += ["| Example | Image | Critical | High | Medium | Low |", "|---|---|---|---|---|---|"]
        for example, what, found in rows:
            counts = found.counts
            lines.append("| %s | %s | %d | %d | %d | %d |" % (
                example, what, counts["CRITICAL"], counts["HIGH"], counts["MEDIUM"], counts["LOW"]))
        lines.append("")
    if serious:
        lines += ["### HIGH and CRITICAL", "", "| Example | Severity | Vulnerability | Package | Installed | Fixed in |",
                  "|---|---|---|---|---|---|"]
        for row in serious:
            lines.append("| %s | %s | %s | %s | %s | %s |" % (
                row["Example"], row["Severity"], row.get("VulnerabilityID", ""), row.get("PkgName", ""),
                row.get("InstalledVersion", ""), row.get("FixedVersion") or "no fix yet"))
        lines.append("")
    elif rows:
        lines += ["No HIGH or CRITICAL vulnerability.", ""]
    if not_scanned:
        lines += ["### Not scanned", ""] + ["- " + reason for reason in not_scanned] + [""]
    return "\n".join(lines)


def annotations(report):
    """The warnings of the workflow log: report mode."""
    warnings = []
    if report.serious:
        warnings.append("::warning::Image scan: %d HIGH or CRITICAL vulnerabilities, see the job summary"
                        % report.serious)
    warnings += ["::warning::Image scan: " + reason for reason in report.not_scanned]
    return warnings


def http_get(address):
    for attempt in range(3):
        try:
            with urllib.request.urlopen(address, timeout=120) as response:
                return response.read()
        except (urllib.error.URLError, OSError):
            time.sleep(5 * (attempt + 1))
    return None


def trivy_binary(directory):
    """Downloads the pinned Trivy release of this machine's architecture and checks its SHA-256."""
    machine = platform.machine().lower()
    if machine not in TRIVY_ASSETS:
        raise ScanError("no pinned Trivy release for the architecture " + machine)
    asset, digest = TRIVY_ASSETS[machine]
    address = "https://github.com/aquasecurity/trivy/releases/download/v%s/%s" % (TRIVY_VERSION, asset)
    body = http_get(address)
    if body is None or hashlib.sha256(body).hexdigest() != digest:
        raise ScanError("Trivy %s: cannot download %s, or its SHA-256 does not match" % (TRIVY_VERSION, address))
    archive = directory / asset
    archive.write_bytes(body)
    with tarfile.open(archive) as tar:
        tar.extract("trivy", directory, filter="data")
    binary = directory / "trivy"
    binary.chmod(0o755)
    return binary


def docker_here():
    """The Docker daemon of this machine, when `docker info` answers."""
    try:
        available = subprocess.run(["docker", "info"], capture_output=True, check=False, timeout=60).returncode == 0
    except (OSError, subprocess.TimeoutExpired):
        available = False

    def build(tag, context):
        completed = subprocess.run(["docker", "build", "-t", tag, "-f", str(context / "Dockerfile"), str(context)],
                                   check=False)
        return completed.returncode == 0

    def remove(tag):
        subprocess.run(["docker", "image", "rm", "--force", tag], capture_output=True, check=False)

    return Docker(available, build, remove)


def main(argv):
    arguments = list(argv[1:])
    trivy, work = None, None
    while arguments and arguments[0] in ("--trivy", "--work"):
        option, value = arguments.pop(0), arguments.pop(0)
        if option == "--trivy":
            trivy = pathlib.Path(value)
        else:
            work = pathlib.Path(value)
    if not arguments:
        print(__doc__, file=sys.stderr)
        return 2
    work = work or pathlib.Path(os.environ.get("RUNNER_TEMP") or tempfile.gettempdir()) / "vidocq-image-scan"
    work.mkdir(parents=True, exist_ok=True)
    try:
        binary = trivy or trivy_binary(work)
        report = scan(arguments, binary, work, docker_here())
    except ScanError as error:
        report = Report("## Image scan\n\nThe scan could not run: %s\n" % error, 0, [str(error)])
    print(report.markdown)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as fh:
            fh.write(report.markdown + "\n")
    for warning in annotations(report):
        print(warning)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
