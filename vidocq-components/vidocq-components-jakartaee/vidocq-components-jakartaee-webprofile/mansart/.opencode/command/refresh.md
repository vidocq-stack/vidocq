---
description: Re-measure a spec and refresh TASKS/STATUS from what is actually true
agent: lead
---
ARGS: $ARGUMENTS
Expected: <XXX>   e.g. JKP

WHY THIS EXISTS: everything generated goes stale the moment anything changes,
and staying current used to depend on a human remembering which script to run.
Nobody remembers. This command measures, records, and reports — one step.

YOU RUN EXACTLY THIS, and nothing else:

    ./scripts/verify-m0.sh $ARGUMENTS

That single script does all of it: it checks every M0 card against the card's
own artifact, then refreshes TASKS-$ARGUMENTS.md and STATUS-$ARGUMENTS.md from
what it just measured. Card rows are not written by you or by any agent — a row
exists because the checker printed PASS.

YOU WRITE NOTHING. Not STATUS, not TASKS. The guard refuses TASKS anyway.

THEN REPORT exactly this shape, all numbers copied from the output:

    M0:     <n> pass, <n> fail
    cards:  <done>/<total>     <- the **Total:** line of STATUS-$ARGUMENTS.md,
                               the WHOLE spec (249 for JKP), not just M0
    order:  <"clean" or the ORDER VIOLATION lines>
    next:   <the first FAIL line, verbatim — or "M0 complete">

RULES:
- A FAIL line is the useful part. Copy it whole; it names what is missing and
  usually how to fix it. Never summarise it into "some checks failed".
- Do NOT fix anything here. This command measures. /tck wires, /next implements.
- exit 1 is a normal outcome: it means something is not done, which is
  information. Report it flatly.
