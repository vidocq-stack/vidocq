---
description: Wire the TCK if needed, run it, record the counter. Zero is a valid result.
agent: lead
---
ARGS: $ARGUMENTS
Expected: <XXX>   e.g. JKP

The TCK is the ONLY progress metric. A card marked DONE without it means nothing.
A run that scores 0 is a SUCCESS for this command: the instrument now exists.

1. READ the metadata — never guess coordinates
   cat docs/spec-src/$ARGUMENTS/spec-meta.json
   No file? Run /spec-add first. STOP.
   tck.found == false? Say the spec has no local TCK, say the keyword tried,
   and STOP. Do not invent a runner.

2. IS THE RUNNER WIRED?
   Look for the module named in STATUS-$ARGUMENTS.md, or a *-tck module matching
   the spec. Missing? Then this is the M0 card: build it, do not run it.

   TO WIRE IT — copy, do not invent:
   - Copy the layout of an existing runner from tck.repo_runners (they work:
     mansart-data-tck scores 74/74).
   - Standalone POM, OUT of the reactor (workspace CLAUDE.md requires it).
   - Depend on tck.recommended coordinates.
   - Arquillian + an ArchiveAppender that injects our implementation.
   - A run-official-tck-<spec>.sh next to the POM.
   Delegate the writing to @impl, one piece at a time. You do not type it.

3. RUN
   @tck-runner: "run <module>/run-official-tck-*.sh, report the 4 lines"
   It cannot write. It cannot fix. It reports.

4. RECORD in STATUS-$ARGUMENTS.md
   Line: <date> | TCK <version> | PASS=<n> FAIL=<n> ERROR=<n> | <sha>
   This counter is the progress of the whole spec. Never write a number the
   runner did not give you.

5. STOP. Report the 4 lines. Do NOT start fixing failures — that is /next.
