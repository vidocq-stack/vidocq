---
description: Re-measure a spec and refresh TASKS/STATUS from what is actually true
agent: lead
---
ARGS: $ARGUMENTS   (<XXX>)

RUN EXACTLY:   ./scripts/verify-m0.sh $ARGUMENTS
It checks every M0 card against its own artifact, runs the suite if no counter
exists, and refreshes TASKS/STATUS itself. YOU WRITE NOTHING. Fix nothing here.

REPORT, numbers copied from its output:
    M0:     <n> pass, <n> fail
    cards:  <done>/<total>      <- the **Total:** line of STATUS (whole spec)
    order:  <"clean" or the ORDER VIOLATION lines>
    next:   <first FAIL line verbatim, or "M0 complete">
exit 1 is normal: something is not done, which is information.
