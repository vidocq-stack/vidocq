---
description: Ingest a spec (URL to PDF) and derive TASKS-XXX.md + STATUS-XXX.md
agent: lead
---
ARGS: $ARGUMENTS
Expected: <url-to-spec-pdf> <XXX>   e.g. https://.../persistence-3.2.pdf JKP

XXX is a 3-letter code. If TASKS-XXX.md already exists, STOP and say so.

WHY A PIPELINE: a Jakarta spec is hundreds of pages. It does not fit your context.
You will NEVER read the PDF. You read notes.

STEPS:

1. FETCH
   mkdir -p docs/spec-src docs/spec-notes/XXX
   Download the PDF to docs/spec-src/XXX.pdf (curl -sSL -o).
   Already there? Skip.

2. CONVERT
   POST the file to oMLX markitdown, or use its /v1 document endpoint, to get text
   at docs/spec-src/XXX.md. Limit: 25 MB.
   Cannot convert? STOP and say why. Do not invent chapters.

3. SPLIT (no model — plain shell)
   Split docs/spec-src/XXX.md on chapter headings into docs/spec-src/XXX/ch-NN.md.
   Report how many chapters you got. If it is 1, the split failed — STOP.

4. NOTE EACH CHAPTER
   For EACH chapter, one @recon call: "read <file>, write docs/spec-notes/XXX/ch-NN.md,
   max 200 lines: normative requirements only, each with its spec section number".
   Chapter whose note already exists -> SKIP it (restartable).
   Do NOT read the chapters yourself.

5. DERIVE TASKS
   Read ONLY docs/spec-notes/XXX/*.md.
   Write TASKS-XXX.md:
     - Milestones M1..Mx, ordered by dependency, not by chapter order.
     - Cards M1-T001, M1-T002, ... One card = one behaviour, testable.
     - Each card: title, the spec section it comes from, done-when (measurable).
   NORMAL ENGLISH here. This file is a contract a human reads.

6. INIT STATUS
   Write STATUS-XXX.md: counters at zero, no card in progress, date.

7. STOP. Report: chapters, milestones, cards. 3 lines.
