---
description: Ingest a spec (url) and derive TASKS-XXX.md + STATUS-XXX.md
agent: lead
---
ARGS: $ARGUMENTS
Expected: <url-to-spec> <XXX>   e.g. https://jakarta.ee/.../spec-3.2.pdf JKP

XXX is 3 uppercase letters. TASKS-XXX.md already exists? STOP, say so.

WHY A PIPELINE: a Jakarta spec is over a million characters. It does not fit your
context. YOU NEVER READ THE SPEC. You read notes about it. That is the whole point.

1. FETCH + SPLIT — one shell command, no model
   python3 scripts/spec-fetch.py <url> <XXX>
   It prefers the HTML rendering, converts to text, splits on headings, writes
   docs/spec-src/<XXX>/ch-NN-*.md, AND looks up the official TCK, recording
   everything in docs/spec-src/<XXX>/spec-meta.json. Cached and restartable.
   Fewer than 2 chapters, or non-zero exit? STOP and report. Do not improvise.

   Read the printed `tck:` line. You will need it in step 4.
   Wrong keyword guessed from the url? Re-run with --tck-keyword <k>.

2. LIST chapters
   ls docs/spec-src/<XXX>/
   SKIP chapters that are front matter: preamble, license, foreword, colophon,
   revision history, bibliography. They carry no requirement.

3. NOTE EVERY CHAPTER — one shell command, NOT an agent loop
   ./scripts/spec-note.sh <XXX>

   It walks the chapters SEQUENTIALLY, one @noter call at a time, with a hard
   timeout on each, and skips front matter and already-written notes by itself.

   DO NOT dispatch @noter yourself, and NEVER in parallel. The lead once fired 39
   calls at once at a server with max_concurrent_requests=2: 9 active, 7 queued,
   prompt processing down from 1730 to 170 tok/s, then one call hung and the run
   sat there for 51 MINUTES without writing a file, looking perfectly healthy.
   Dispatching N mechanical calls is a shell's job, not yours.

   Read the final line it prints. It is the truth:
     notes: <n>/<total> written, <n> skipped, <n> timed out, <n> reported-but-missing
   Non-zero timeouts or reported-but-missing? Run it again — it resumes where it
   stopped. Twice in a row without progress: STOP and report, do not loop.

4. DERIVE TASKS + STATUS — one shell command, NOT an agent
   ./scripts/spec-tasks.sh <XXX>

   YOU DO NOT WRITE TASKS-<XXX>.md. The lead tried three times and died silently
   every time — exit 0, no error, no file, and once an M0 that just paraphrased
   spec-meta.json (`jakarta.tack:persistence-tck`, a typo in Maven coordinates).
   The script owns milestone order, card ids, M0 and STATUS. M0 is generated from
   spec-meta.json with NO MODEL AT ALL.

   FIRST it needs docs/spec-src/<XXX>/milestones.tsv — that file is the ONE
   judgement the script cannot make. One line per milestone, IN DEPENDENCY ORDER
   (not chapter order):
       <milestone-name><TAB><note globs>
   e.g.  entities<TAB>ch-04*.md ch-05*.md
   Missing? Read `ls docs/spec-notes/<XXX>/` and write it. Nothing else.

   Then run the script. Read its last two lines: the assembled counts, and the
   shape report (cards bundling 4+ requirements, duplicate titles). REPORT BOTH.
   Do not "improve" the generated file by hand.

5. REPORT
   milestones, cards, and the shape numbers the script printed. If a group came
   back with 0 cards, say which and say the fix is
   `./scripts/spec-tasks.sh <XXX> --force`.
