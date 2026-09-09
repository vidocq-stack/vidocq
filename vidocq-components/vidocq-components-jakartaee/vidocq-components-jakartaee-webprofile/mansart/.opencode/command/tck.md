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

   tck.runnable == false? The TCK is NOT INSTALLED — which is not the same as
   "this spec has no TCK". Install it, do not shrug and do not invent a metric:
       python3 scripts/tck-install.py $ARGUMENTS
   It reads the archive link off the spec page (nothing is hardcoded: the file
   names share no pattern across specs), verifies the published sha256, reads
   each jar's own Maven coordinates, installs them, and ends by calling
   tck-find.py — an install is not a metric.
   Then: python3 scripts/spec-fetch.py $ARGUMENTS --refresh-tck

   Exit 5 means the spec page links no archive. THAT is where you use WebFetch:
   find the official distribution for this spec version, report the url, and
   STOP. Never fabricate a coordinate or a runner.

2. IS THE RUNNER WIRED?
   THE PATH IS NOT YOURS TO CHOOSE:
       python3 scripts/tck-module.py $ARGUMENTS
   It prints the module path, read from docs/spec-src/$ARGUMENTS/module.conf
   (derived from the runners this repo already has). Build EXACTLY there.
   An agent once invented ee/jakarta/tck/persistence/mansart-jkp-tck — the TCK's
   own Java package path, with the harness's 3-letter code as a module name.
   Directory exists with a pom.xml? The runner is wired; go to step 3.

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
   Append ONE ROW PER FINISHED CARD to the "Done cards" table:
       | M0-T00n | <date> | <the command and what it printed> |
   NEVER edit the milestone counts — they are computed from those rows by
   scripts/spec-tasks.sh. A count with no rows behind it says "4 done" without
   saying which four, which is how a card gets marked done with nothing to show.
   Evidence means a build log path, an exit code, a TCK counter. Not a sentence.

5. STOP. Report the 4 lines. Do NOT start fixing failures — that is /next.
