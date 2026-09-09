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
   - TWO PIECES, and the repo shows both: a PARENT module in the reactor
     (mansart-jakarta-data is in the root pom's <modules>) and the runner
     INSIDE it but OUT of the reactor (mansart-data-tck is NOT in the parent's
     <modules>). Missing parent = a module Maven never sees. Read the root
     pom.xml and mansart-jakarta-data/pom.xml before writing either.
   - Depend on tck.recommended coordinates.
   - DO NOT depend on implementation modules that do not exist yet. An agent
     declared six (-core, -cdi, -dialect-h2, ...) copied from the data runner;
     none existed, and the build could never go green. M1 wires them later.
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
   AND: write the row ONLY if the card's own done-when command exited 0. An
   agent once wrote "M0-T001 ... Build fails with exit 1 (expected)" and counted
   it done — the evidence contradicted the card in the same sentence. Blocked?
   Say so in the Log. A blocked card is not a done card.

   M0 IS A CHAIN — T001, then T002, then T003, then T004, then T005. No skipping.
   Each card is the ground the next stands on: no parent module, no runner; no
   runner, no POM; no POM, no TCK dependency; no dependency, nothing for the
   wiring to assemble against. NEVER close a card whose predecessor is open —
   scripts/spec-tasks.sh reports it as an ORDER VIOLATION and it will be visible.
   (M1..Mx are NOT chains: independent behaviours, any order.)

5. STOP. Report the 4 lines. Do NOT start fixing failures — that is /next.
