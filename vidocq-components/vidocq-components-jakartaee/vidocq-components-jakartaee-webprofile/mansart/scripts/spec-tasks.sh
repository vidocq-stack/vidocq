#!/usr/bin/env bash
# Build TASKS-<XXX>.md from spec notes — one @planner call per milestone group,
# sequential, watchdog per call, deterministic assembly.
#
# Why this is a script and not the lead:
#   The lead tried three times to derive the whole TASKS file in one session.
#   Every attempt ended with exit 0, no error, and either no file or a file whose
#   M0 was five cards paraphrasing a JSON blob. It cannot hold a document.
#
#   So the model only ever produces ROWS for ONE group. This script owns
#   everything a model gets wrong: milestone order, card ids, M0, and STATUS.
#   M0 is generated from spec-meta.json with no model at all — a previous run
#   emitted `jakarta.tack:persistence-tck`, a typo in Maven coordinates that
#   would have failed hours later for no visible reason.
#
# Usage: scripts/spec-tasks.sh <XXX> [--timeout SECONDS] [--force]
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
CODE="${1:-}"; TIMEOUT=900; FORCE=0
shift || true
while [ $# -gt 0 ]; do
    case "$1" in
        --timeout) TIMEOUT="$2"; shift 2 ;;
        --force) FORCE=1; shift ;;
        *) shift ;;
    esac
done
printf '%s' "$CODE" | grep -qE '^[A-Z]{3}$' || { echo "usage: scripts/spec-tasks.sh <XXX>"; exit 2; }

NOTES="$ROOT/docs/spec-notes/$CODE"
META="$ROOT/docs/spec-src/$CODE/spec-meta.json"
PLAN="$ROOT/docs/spec-src/$CODE/milestones.tsv"
FRAG="$NOTES/.fragments"
OUT="$ROOT/TASKS-$CODE.md"
STATUS="$ROOT/STATUS-$CODE.md"
[ -d "$NOTES" ] || { echo "no notes at $NOTES — run scripts/spec-note.sh $CODE"; exit 3; }
[ -f "$META" ]  || { echo "no $META"; exit 3; }
[ -f "$PLAN" ]  || { echo "no $PLAN — declare '<milestone-name><TAB><note globs>', one per line, IN DEPENDENCY ORDER"; exit 3; }
mkdir -p "$FRAG"

# --- one planner call per group -------------------------------------------
n=0; wrote=0; failed=0; empty=0
start=$(date +%s)
while IFS=$'\t' read -r name globs; do
    [ -n "${name:-}" ] || continue
    case "$name" in \#*) continue ;; esac
    n=$((n + 1))
    frag="$FRAG/$name.md"
    files=""
    for g in $globs; do
        for f in "$NOTES"/$g; do [ -s "$f" ] && files="$files $f"; done
    done
    [ -n "$files" ] || { echo "  [$n] $name ... NO NOTES MATCHED ($globs)"; failed=$((failed+1)); continue; }
    if [ -s "$frag" ] && [ "$FORCE" -eq 0 ]; then
        wrote=$((wrote + 1)); printf '  [skip] %s (fragment exists)\n' "$name"; continue
    fi

    printf '  [%2d] %-22s ' "$n" "$name"
    if timeout "$TIMEOUT" opencode run --agent planner \
        "Read these note files:$files
         WRITE implementation card rows to $frag — ONLY lines of the form
         | <title> | <spec sections> | <done-when> |
         No id column, no header, no prose. Then ls the file and report." \
        </dev/null >"$FRAG/.$name.log" 2>&1   # else it eats the TSV on stdin
    then
        # Keep ONLY well-formed rows. Prose is discarded, not trusted.
        if [ -f "$frag" ]; then
            grep -E '^\|[^|]+\|[^|]+\|[^|]+\|[[:space:]]*$' "$frag" \
                | grep -vE '^\|[[:space:]]*(-+|Card|Title|title)[[:space:]]*\|' > "$frag.clean" || true
            mv "$frag.clean" "$frag"
        fi
        if [ -s "$frag" ]; then
            wrote=$((wrote + 1)); printf 'ok (%s cards)\n' "$(wc -l < "$frag" | tr -d ' ')"
        else
            empty=$((empty + 1)); printf 'REPORTED OK BUT NO USABLE ROWS\n'
        fi
    else
        rc=$?; failed=$((failed + 1))
        [ $rc -eq 124 ] && printf 'TIMEOUT after %ss\n' "$TIMEOUT" || printf 'FAILED (exit %s)\n' "$rc"
    fi
done < "$PLAN"

# --- assembly: ids, M0 and STATUS are the script's job, never a model's ----
python3 - "$CODE" "$META" "$PLAN" "$FRAG" "$OUT" "$STATUS" <<'PY'
import collections, json, os, re, sys
code, meta_p, plan_p, frag_d, out_p, status_p = sys.argv[1:7]
meta = json.load(open(meta_p))
tck  = meta.get("tck", {})
rec  = tck.get("recommended") or {}
coord = ("{}:{}:{}".format(rec.get("groupId","?"), rec.get("artifactId","?"), rec.get("version","?"))
         if rec and tck.get("runnable") else None)
runners = [r["path"] for r in tck.get("repo_runners", [])]

# Where the runner goes is read from module.conf, never invented. An agent once
# created ee/jakarta/tck/persistence/mansart-jkp-tck — the TCK's Java package
# path, with the harness's internal 3-letter code as a Maven module name.
import subprocess as _sp
_m = _sp.run([sys.executable, os.path.join(os.path.dirname(os.path.abspath(__file__)) if "__file__" in dir() else ".", "scripts", "tck-module.py"), code],
             capture_output=True, text=True)
module = (_m.stdout or "").strip() or "(run scripts/tck-module.py {})".format(code)
parent_module = module.split("/")[0] if "/" in module else None

L = ["# TASKS-{} — {}".format(code, meta.get("url","")), "",
     "One card = one behaviour = one failing test. Cards are numbered by the",
     "generator; do not renumber by hand.", "", "---", ""]

L += ["## M0 — the TCK", "",
      "No card below M0 can be called done without a counter to check it against.", ""]
if coord:
    L += ["TCK: `{}`".format(coord), "",
          "| Card | Title | Spec sections | Done-when |", "|---|---|---|---|",
          "| M0-T001 | Create the parent module `{}` (packaging pom) and register it in the ROOT pom's `<modules>` | — | `<module>{}</module>` is in the root pom and `./scripts/build.sh -N validate` exits 0. Mirror `mansart-jakarta-data`: the parent aggregates implementation modules, **never the -tck one**. |".format(parent_module or module, parent_module or module),
          "| M0-T002 | Create the TCK runner at **`{}`** — standalone POM, OUT of the reactor | — | That exact directory holds a pom.xml, it is NOT listed in the parent's `<modules>`, and `./scripts/build.sh test-compile` exits 0 there. **The path is not yours to choose**: it comes from `docs/spec-src/{}/module.conf`. |".format(module, code),
          "| M0-T003 | Depend on `{}` — **and on nothing that does not exist yet** | — | `./scripts/build.sh dependency:resolve` lists the TCK jar and resolves everything. Declaring implementation modules before M1 creates them makes M0 unbuildable — an agent declared six and the build failed. **Removing the TCK dependency to go green is not a fix either.** |".format(coord),
          "| M0-T004 | Arquillian container + ArchiveAppender + run script | — | The run script starts the suite and writes a log, exit code recorded |",
          "| M0-T005 | First run | — | **The TCK produces a counter, ANY counter. PASS=0 is success: the instrument exists.** The implementation is wired in later, by M1. |", ""]
    if runners:
        L += ["Copy the layout from a runner that already passes here: " +
              ", ".join("`{}`".format(r) for r in runners), ""]
elif tck.get("keyword") and tck.get("spec_version"):
    # Nothing runnable in the M2 — but the spec and its version are known, so the
    # TCK is *not installed*, which is not the same as *does not exist*. The gap
    # between those two is where a project quietly invents a substitute metric.
    L += ["**The official TCK is not in the local M2 for this spec version.**", "",
          "`{}`".format(tck.get("reason") or
                        "no TCK artifact matching '{}' installed".format(tck.get("keyword"))), "",
          "If this spec genuinely has no TCK, replace M0-T001 with a decision on",
          "the progress metric — and say so in STATUS. Do not skip it silently.", "",
          "Installing it is M0's first card. Do not invent a substitute metric",
          "while the real one is one download away.", "",
          "| Card | Title | Spec sections | Done-when |", "|---|---|---|---|",
          "| M0-T001 | Obtain the official TCK for {} {} and install it into the local M2 | — | `scripts/tck-find.py {} --spec-version {}` exits 0 and reports a jar with >0 test classes |".format(
              tck.get("keyword", code), tck.get("spec_version") or "?", tck.get("keyword", code), tck.get("spec_version") or "?"),
          "| M0-T002 | Create the TCK runner module (standalone POM, out of reactor) | — | `./scripts/build.sh` builds the module, exit 0 |",
          "| M0-T003 | Depend on the TCK jar | — | `./scripts/build.sh dependency:resolve` lists it. **Removing the dependency to make the build green is not a fix.** |",
          "| M0-T004 | Arquillian container + ArchiveAppender injecting our implementation | — | A deployment archive is produced |",
          "| M0-T005 | Run script + config template, then first run | — | **The TCK produces a counter, ANY counter. PASS=0 is success: the instrument exists.** |", ""]
    if runners:
        L += ["Copy the layout from a runner that already passes here: " +
              ", ".join("`{}`".format(r) for r in runners), ""]
else:
    L += ["**No TCK artifact matching this spec exists locally or is known.** M0 is",
          "a single card: decide the progress metric and write it down. Never",
          "pretend a metric exists.", "",
          "| Card | Title | Spec sections | Done-when |", "|---|---|---|---|",
          "| M0-T001 | Choose and document the progress metric | — | The metric is written in STATUS-{}.md |".format(code), ""]

m0_cards = sum(1 for l in L if l.startswith("| M0-T"))  # count, never assume
groups, total = [], 0
order = [l.split("\t")[0].strip() for l in open(plan_p) if l.strip() and not l.startswith("#")]
for i, name in enumerate(order, start=1):
    f = os.path.join(frag_d, name + ".md")
    rows = [r.rstrip("\n") for r in open(f)] if os.path.exists(f) else []
    if not rows:
        L += ["## M{} — {}".format(i, name.replace("-", " ")), "",
              "*(no cards generated — rerun `scripts/spec-tasks.sh {} --force`)*".format(code), ""]
        groups.append((i, name, 0)); continue
    L += ["## M{} — {}".format(i, name.replace("-", " ")), "",
          "| Card | Title | Spec sections | Done-when |", "|---|---|---|---|"]
    for j, row in enumerate(rows, start=1):
        L.append("| M{}-T{:03d} {}".format(i, j, row.lstrip("|").lstrip()))
    L.append("")
    groups.append((i, name, len(rows))); total += len(rows)

open(out_p, "w").write("\n".join(L) + "\n")

# Shape report. The script cannot judge wording, but it CAN count the two
# failure modes seen for real: a done-when bundling several requirements, and
# the same heading emitted as a card by two different groups.
import collections, glob as _g, re as _re
titles, bundled = [], 0
for f in sorted(_g.glob(os.path.join(frag_d, "*.md"))):
    for row in open(f):
        cols = [c.strip() for c in row.strip().strip("|").split("|")]
        if len(cols) < 3:
            continue
        titles.append(cols[0].lower())
        if cols[2].count(",") >= 3:
            bundled += 1
dupes = [t for t, n in collections.Counter(titles).items() if n > 1]
if bundled or dupes:
    print("shape: {} cards bundle 4+ requirements in one done-when, {} duplicate titles"
          .format(bundled, len(dupes)))

# Progress is NEVER regenerated: read back the done cards and keep them. A count
# on its own is unverifiable — an agent wrote "M0: 4 done" without saying which
# four. Ids and evidence are the record; the counts are derived from them.
done_rows, in_done = [], False
if os.path.exists(status_p):
    for line in open(status_p, encoding="utf-8"):
        if line.startswith("## Done cards"):
            in_done = True
            continue
        if in_done and line.startswith("## "):
            in_done = False
        if in_done and re.match(r"^\|\s*M\d+-T\d{3}\s*\|", line):
            done_rows.append(line.rstrip("\n"))
done_ids = [re.match(r"^\|\s*(M\d+-T\d{3})", r).group(1) for r in done_rows]
done_by_ms = collections.Counter(i.split("-")[0] for i in done_ids)

S = ["# STATUS-{}".format(code), "",
     "Counters only. A line here is written by a tool, never by an agent.", "",
     "## TCK", "",
     "```", "tck:    {}".format(coord or "none found"),
     "module: (not created yet — M0-T001)",
     "result: PASS=? FAIL=? ERROR=? SKIP=?   (never run)", "```", "",
     "## Milestones", "", "| Milestone | Cards | Done |", "|---|---|---|",
     "| M0 — the TCK | {} | {} |".format(m0_cards, done_by_ms.get("M0", 0))]
for i, name, c in groups:
    S.append("| M{} — {} | {} | {} |".format(i, name.replace("-", " "), c,
                                             done_by_ms.get("M{}".format(i), 0)))
S += ["", "**Total: {} cards, {} done.**".format(total + m0_cards, len(done_ids)), "",
      "## Done cards", "",
      "One line per finished card, WITH the evidence that closed it: a build log,",
      "a TCK counter, a command and its exit code. A card with no evidence line is",
      "not done, whatever an agent reported.", "",
      "**A row goes here ONLY when the card's own done-when command exited 0.**",
      "An agent once wrote `M0-T001 ... Build fails with exit 1 (expected)` and",
      "counted it done: the evidence contradicted the card in the same sentence.",
      "If the done-when cannot pass yet, the card is not done — say what blocks it",
      "in the Log, not here.", "",
      "| Card | Date | Evidence |", "|---|---|---|"]
S += done_rows or ["| — | — | *(none yet)* |"]
S += [""]
open(status_p, "w").write("\n".join(S) + "\n")
print("\nassembled: {} milestones, {} cards -> {}".format(len(groups) + 1, total + m0_cards, os.path.basename(out_p)))
PY

elapsed=$(( $(date +%s) - start ))
printf 'groups: %d/%d written, %d failed, %d reported-but-empty (%ds)\n' "$wrote" "$n" "$failed" "$empty" "$elapsed"
[ "$wrote" -eq "$n" ]
