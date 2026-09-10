#!/usr/bin/env python3
"""Obtain and install the official TCK for an already-ingested spec.

WHY THIS IS NOT A HARDCODED URL: the harness must work for the next spec, not
just this one. Measured on three specs, the distribution file names share no
pattern at all —

    persistence 3.2      jakarta-persistence-tck-3.2.1.zip
    bean-validation 3.1  validation-tck-dist-3.1.1.zip
    data 1.0             data-tck-1.0.0.zip

Any convention guessed from one of them fails on the other two. So nothing is
guessed: the spec page we already have in spec-meta.json links its own TCK, and
every jar inside the distribution carries its exact Maven coordinates in
META-INF/maven/<groupId>/<artifactId>/pom.properties. Discovery reads; it does
not infer.

The verdict is never this script's: it ends by calling tck-find.py, which counts
test classes in the installed jar. An install that reports success while nothing
is runnable is the failure this whole harness exists to catch.

Usage: scripts/tck-install.py <XXX> [--dry-run] [--keep]
"""
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
UA = {"User-Agent": "Mozilla/5.0 (mansart-harness)"}


def fetch(url, timeout=180):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=timeout) as r:
        return r.read()


def spec_pages(spec_url):
    """The download links live on the spec's landing page, not in the spec text.

    spec-meta.json stores the document url (.../3.2/jakarta-persistence-spec-3.2.html);
    the archives are listed one level up (.../persistence/3.2/). Try the landing
    page first, then the document itself.
    """
    pages = []
    if "/" in spec_url:
        landing = spec_url.rsplit("/", 1)[0] + "/"
        pages.append(landing)
    pages.append(spec_url)
    return pages


def tck_links(spec_url):
    """Every TCK archive the spec pages point at. Reading beats guessing."""
    html = ""
    for page in spec_pages(spec_url):
        try:
            html += fetch(page, timeout=60).decode("utf-8", "replace")
        except Exception:
            continue
    hrefs = re.findall(r'href="([^"]+)"', html)
    zips = [h for h in hrefs if "tck" in h.lower() and h.lower().endswith(".zip")]
    seen, out = set(), []
    for z in zips:
        if z not in seen:
            seen.add(z)
            out.append(z)
    return out


def verify_sha256(blob, url):
    """The spec page publishes a .sha256 next to the zip. Use it."""
    try:
        want = fetch(url + ".sha256", timeout=60).decode().split()[0].strip().lower()
    except Exception:
        return None  # no digest published — not a failure, but say so
    got = hashlib.sha256(blob).hexdigest()
    return got == want


def coordinates(jar_bytes):
    """groupId/artifactId/version, read from the jar itself."""
    import io
    with zipfile.ZipFile(io.BytesIO(jar_bytes)) as z:
        pp = [n for n in z.namelist() if n.endswith("META-INF/maven/") is False
              and n.endswith("pom.properties")]
        if not pp:
            return None
        props = dict(
            line.split("=", 1) for line in
            z.read(pp[0]).decode("utf-8", "replace").splitlines()
            if "=" in line and not line.startswith("#"))
    g, a, v = props.get("groupId"), props.get("artifactId"), props.get("version")
    return (g.strip(), a.strip(), v.strip()) if g and a and v else None


def is_related(coord, keyword):
    """Is this jar part of the TCK, or a library it happens to ship?

    Measured: the Bean Validation archive holds 42 jars of which 8 carry no
    coordinates at all, and the Transactions archive holds 13 of which the only
    one with coordinates is `jaxen:jaxen:1.1.6` — a third-party XPath library.
    Installing every jar that has a pom.properties would pollute the M2 with
    other projects' artifacts and still miss the TCK.
    """
    g, a, _ = coord
    hay = (g + ":" + a).lower()
    kw = (keyword or "").lower()
    return "tck" in hay or (kw and kw in hay) or hay.startswith("jakarta.")


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    dry = "--dry-run" in sys.argv
    keep = "--keep" in sys.argv
    if len(args) != 1 or not re.fullmatch(r"[A-Z]{3}", args[0]):
        print(__doc__)
        return 2
    code = args[0]

    meta_p = os.path.join(ROOT, "docs", "spec-src", code, "spec-meta.json")
    if not os.path.isfile(meta_p):
        print(f"no {meta_p} — run the full ingestion first")
        return 3
    meta = json.load(open(meta_p, encoding="utf-8"))
    spec_url, tck = meta.get("url", ""), meta.get("tck") or {}
    kw, ver = tck.get("keyword"), tck.get("spec_version")
    print(f"spec: {kw} {ver}\npage: {spec_url}")

    try:
        links = tck_links(spec_url)
    except Exception as e:
        print(f"cannot read the spec page: {e}")
        return 4
    if not links:
        print("NO TCK ARCHIVE LINKED from the spec page.")
        print("This is where a human or an agent with web access takes over:")
        print("  find the official TCK distribution for this spec version,")
        print("  then re-run this script once the zip url is known.")
        return 5
    print(f"tck archives linked: {len(links)}")
    for l in links:
        print(f"  {l}")
    url = links[0]
    print(f"\ndownloading {url}" + ("  (--dry-run: classify only)" if dry else ""))
    blob = fetch(url)
    ok = verify_sha256(blob, url)
    print(f"sha256: {'verified' if ok else ('MISMATCH' if ok is False else 'no digest published')}")
    if ok is False:
        print("refusing to install an archive whose digest does not match")
        return 6

    tmp = tempfile.mkdtemp(prefix="tck-")
    try:
        import io
        z = zipfile.ZipFile(io.BytesIO(blob))
        jars = [n for n in z.namelist()
                if n.endswith(".jar") and "-sources" not in n and "-javadoc" not in n]
        print(f"archive: {len(blob) / 1e6:.1f} MB, {len(jars)} jars to install")
        installed, skipped, foreign, nometa = 0, [], [], []
        for j in jars:
            data = z.read(j)
            co = coordinates(data)
            if not co:
                nometa.append(os.path.basename(j))
                continue
            if not is_related(co, kw):
                foreign.append("{}:{}:{}".format(*co))
                continue
            g, a, v = co
            path = os.path.join(tmp, os.path.basename(j))
            open(path, "wb").write(data)
            if dry:
                installed += 1
                print(f"  would install {g}:{a}:{v}")
                continue
            r = subprocess.run(
                [os.path.join(ROOT, "scripts", "build.sh"),
                 "install:install-file", f"-Dfile={path}",
                 f"-DgroupId={g}", f"-DartifactId={a}", f"-Dversion={v}",
                 "-Dpackaging=jar"],
                # cwd is the TEMP dir on purpose: run from the repo root, Maven
                # loads the whole reactor first — and its parent chain reaches
                # org.sonatype.oss:oss-parent:11, which was unresolvable one
                # morning. Three installs failed for a reason unrelated to the
                # TCK, and the command reading exit 7 gave up. install-file
                # needs no project at all.
                capture_output=True, text=True, cwd=tmp, timeout=600)
            if r.returncode == 0:
                installed += 1
                print(f"  installed {g}:{a}:{v}")
            else:
                skipped.append(f"{g}:{a}:{v} (mvn exit {r.returncode})")
        for s in skipped:
            print(f"  FAILED  {s}")
        if foreign:
            print(f"  left alone ({len(foreign)} third-party jars shipped with the "
                  f"TCK, not ours to install): " + ", ".join(foreign[:4])
                  + (" ..." if len(foreign) > 4 else ""))
        if nometa:
            print(f"  no Maven metadata ({len(nometa)}): " + ", ".join(nometa[:4])
                  + (" ..." if len(nometa) > 4 else ""))
        print(f"\ninstalled {installed} of {len(jars)} jars in the archive")

        if installed == 0 and skipped:
            # Coordinates were found and every install FAILED: that is a Maven
            # problem, not a JavaTest distribution. A first version reported
            # exit 7 ("not a Maven TCK") for three mvn failures, and the command
            # reading it gave up on a perfectly consumable suite.
            print("\nINSTALL FAILED for every jar (mvn exit != 0). The archive IS Maven-consumable;")
            print("the install step is broken. See target/build-logs/ for the mvn error.")
            return 8
        if installed == 0:
            # A whole family of TCKs is not consumed through Maven at all:
            # Transactions 2.0 ships lib/jtatck.jar with javatest, tsharness and
            # sigtest, driven by an Ant harness and a ts.jte file. Saying
            # "installed 0" and stopping is right; pretending otherwise is not.
            print("\nNO MAVEN-CONSUMABLE TCK IN THIS ARCHIVE.")
            print("It looks like a JavaTest/TSharness distribution (lib/*.jar + an")
            print("Ant harness), not a set of Maven artifacts. That TCK is run, not")
            print("depended upon. This repo already has a runner of that family to")
            print("copy: mansart-transactions/mansart-transactions-tck.")
            return 7
    finally:
        if not keep:
            shutil.rmtree(tmp, ignore_errors=True)

    if dry:
        print("\n--dry-run: nothing was installed")
        return 0

    # The verdict belongs to the checker, never to the installer.
    print("\nverifying with tck-find.py — an install is not a metric:")
    rc = subprocess.run(
        [sys.executable, os.path.join(ROOT, "scripts", "tck-find.py"), kw]
        + (["--spec-version", ver] if ver else []),
        cwd=ROOT).returncode
    if rc == 0:
        print("\nnext: python3 scripts/spec-fetch.py " + code + " --refresh-tck"
              " && ./scripts/spec-tasks.sh " + code)
    return rc


if __name__ == "__main__":
    sys.exit(main())
