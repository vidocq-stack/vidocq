#!/usr/bin/env python3
"""Fetch a spec, turn it into text, split it into chapters. No model involved.

Deterministic work does not belong in an agent: it would cost tokens, vary between
runs, and die of compaction on a document this size. The agents only ever read the
chapter files this produces.

Prefers the HTML rendering of a spec over the PDF — jakarta.ee publishes both, and
HTML needs no external converter (stdlib only) while carrying the heading structure
we split on.

Usage:  scripts/spec-fetch.py <url> <XXX> [--force] [--tck-keyword K]
Writes: docs/spec-src/<XXX>.<ext>          the raw download (cached)
        docs/spec-src/<XXX>/ch-NN-slug.md  one file per chapter
        docs/spec-src/<XXX>/spec-meta.json  machine-readable: chapters + TCK
"""
import json
import os
import re
import sys
import urllib.request
from html.parser import HTMLParser

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
UA = {"User-Agent": "curl/8"}
SKIP_TAGS = {"script", "style", "head", "nav", "footer", "svg"}
BLOCK_TAGS = {"p", "div", "br", "li", "tr", "section", "article", "pre", "table"}
HEADINGS = {"h1", "h2"}


class SpecHTML(HTMLParser):
    """HTML -> plain text, remembering where headings are."""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.out = []
        self.skip = 0
        self.heading = None
        self.buf = []

    def handle_starttag(self, tag, attrs):
        if tag in SKIP_TAGS:
            self.skip += 1
        elif tag in HEADINGS:
            self.flush()
            self.heading = tag
        elif tag in BLOCK_TAGS:
            self.out.append("\n")

    def handle_endtag(self, tag):
        if tag in SKIP_TAGS:
            self.skip = max(0, self.skip - 1)
        elif tag in HEADINGS and self.heading:
            text = " ".join("".join(self.buf).split())
            self.buf = []
            self.heading = None
            if text:
                self.out.append(f"\n\n@@HEADING@@ {text}\n\n")
        elif tag in BLOCK_TAGS:
            self.out.append("\n")

    def handle_data(self, data):
        if self.skip:
            return
        (self.buf if self.heading else self.out).append(data)

    def flush(self):
        if self.buf:
            self.out.append("".join(self.buf))
            self.buf = []

    def text(self):
        self.flush()
        t = "".join(self.out)
        t = re.sub(r"[ \t]+", " ", t)
        t = re.sub(r"\n\s*\n\s*\n+", "\n\n", t)
        return t.strip()


def fetch(url, dest, force=False):
    if os.path.exists(dest) and not force and os.path.getsize(dest) > 0:
        print(f"cached: {dest} ({os.path.getsize(dest)} bytes)")
        return dest
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=120) as r, open(dest, "wb") as f:
        f.write(r.read())
    print(f"fetched: {dest} ({os.path.getsize(dest)} bytes)")
    return dest


def slug(s, n=40):
    s = re.sub(r"[^a-zA-Z0-9]+", "-", s.lower()).strip("-")
    return (s[:n].rstrip("-")) or "section"


def split_chapters(text, outdir, min_chars=400):
    """One file per top-level heading. Sections shorter than min_chars are folded
    into the previous one — a spec's front matter is full of one-line headings."""
    os.makedirs(outdir, exist_ok=True)
    parts = re.split(r"\n\n@@HEADING@@ (.+?)\n\n", "\n\n@@HEADING@@ PREAMBLE\n\n" + text)
    pairs = list(zip(parts[1::2], parts[2::2]))
    chapters, buf_title, buf_body = [], None, ""
    for title, body in pairs:
        body = body.strip()
        if buf_title is None:
            buf_title, buf_body = title, body
        elif len(buf_body) < min_chars:
            buf_body += f"\n\n## {title}\n\n{body}"
        else:
            chapters.append((buf_title, buf_body))
            buf_title, buf_body = title, body
    if buf_title is not None:
        chapters.append((buf_title, buf_body))

    # A chapter bigger than MAX_PART drowns a worker's system prompt: at 80 KB the
    # noter answered "what would you like me to do with this specification?".
    # Split on blank lines, never mid-paragraph.
    MAX_PART = 45_000
    exploded = []
    for title, body in chapters:
        if len(body) <= MAX_PART:
            exploded.append((title, body))
            continue
        paras, cur, n = body.split("\n\n"), [], 0
        parts = []
        for para in paras:
            if n + len(para) > MAX_PART and cur:
                parts.append("\n\n".join(cur))
                cur, n = [], 0
            cur.append(para)
            n += len(para) + 2
        if cur:
            parts.append("\n\n".join(cur))
        for j, part in enumerate(parts, 1):
            exploded.append((f"{title} (part {j}/{len(parts)})", part))

    written = []
    for i, (title, body) in enumerate(exploded, 1):
        path = os.path.join(outdir, f"ch-{i:02d}-{slug(title)}.md")
        with open(path, "w", encoding="utf-8") as f:
            f.write(f"# {title}\n\n{body}\n")
        written.append((path, len(body.splitlines())))
    return written


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    force = "--force" in sys.argv
    if len(args) != 2:
        print(__doc__)
        return 2
    url, code = args
    if not re.fullmatch(r"[A-Z]{3}", code):
        print(f"error: code must be 3 uppercase letters, got {code!r}")
        return 2

    srcdir = os.path.join(ROOT, "docs", "spec-src")
    os.makedirs(srcdir, exist_ok=True)

    # Prefer HTML: no external converter needed, and headings survive.
    if url.endswith(".pdf"):
        html_url = url[:-4] + ".html"
        try:
            urllib.request.urlopen(urllib.request.Request(html_url, method="HEAD", headers=UA), timeout=20)
            print(f"note: using the HTML rendering instead of the PDF\n      {html_url}")
            url = html_url
        except Exception:
            print("error: only a PDF is available and no converter is installed")
            print("       install one (markitdown / poppler) or pass an HTML url")
            return 3

    ext = "html" if url.endswith(".html") else url.rsplit(".", 1)[-1]
    raw = fetch(url, os.path.join(srcdir, f"{code}.{ext}"), force)

    with open(raw, "r", encoding="utf-8", errors="replace") as f:
        parser = SpecHTML()
        parser.feed(f.read())
        text = parser.text()

    flat = os.path.join(srcdir, f"{code}.md")
    with open(flat, "w", encoding="utf-8") as f:
        f.write(text)

    outdir = os.path.join(srcdir, code)
    chapters = split_chapters(text, outdir)

    # --- metadata, including the TCK: a spec with no running TCK has no metric --
    kw = None
    for i, a in enumerate(sys.argv):
        if a == "--tck-keyword" and i + 1 < len(sys.argv):
            kw = sys.argv[i + 1]
    if not kw:  # derive from the url: .../specifications/<name>/<version>/...
        m = re.search(r"/specifications/([a-z0-9-]+)/", url)
        kw = m.group(1) if m else code.lower()
    # The spec version pins the TCK version. Without it the richest jar wins, and
    # here that was jakarta.persistence:...-spec-tests:4.0.0-SNAPSHOT (321 test
    # classes) — the TCK of the NEXT spec. A 3.2 implementation would have been
    # measured against 4.0.
    mv = re.search(r"/specifications/[a-z0-9-]+/([0-9]+(?:\.[0-9]+)*)/", url)
    spec_version = mv.group(1) if mv else None
    tck = {"keyword": kw, "spec_version": spec_version, "found": False}
    try:
        import subprocess
        r = subprocess.run(
            [sys.executable, os.path.join(ROOT, "scripts", "tck-find.py"), kw, "--json"]
            + (["--spec-version", spec_version] if spec_version else []),
            capture_output=True, text=True, timeout=60)
        if r.stdout.strip():
            tck = json.loads(r.stdout)
    except Exception as e:
        tck["error"] = str(e)[:200]

    meta = {
        "code": code,
        "url": url,
        "chapters": [os.path.basename(p) for p, _ in chapters],
        "chapter_count": len(chapters),
        "tck": tck,
    }
    with open(os.path.join(outdir, "spec-meta.json"), "w", encoding="utf-8") as f:
        json.dump(meta, f, indent=2)
    total = sum(n for _, n in chapters)
    print(f"text: {flat} ({len(text)} chars)")
    print(f"chapters: {len(chapters)} files in docs/spec-src/{code}/ ({total} lines total)")
    for p, n in chapters[:5]:
        print(f"  {os.path.basename(p)} ({n} lines)")
    if len(chapters) > 5:
        print(f"  ... and {len(chapters) - 5} more")
    t = meta["tck"]
    if t.get("found") and t.get("recommended"):
        r = t["recommended"]
        print(f"tck: {r['groupId']}:{r['artifactId']}:{r['version']}")
        for run in t.get("repo_runners", [])[:2]:
            print(f"  runner to copy: {run['path']}")
    else:
        print(f"tck: none found for keyword '{t.get('keyword')}' — no progress metric available")
    print(f"meta: {os.path.join(outdir, 'spec-meta.json')}")
    if len(chapters) < 2:
        print("WARNING: split produced <2 chapters — headings were not found")
        return 4
    return 0


if __name__ == "__main__":
    sys.exit(main())
