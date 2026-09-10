---
description: Read-only summary of a spec's progress
agent: lead
---
ARGS: $ARGUMENTS   (<XXX>)

READ ONLY. No subagent, no build, no write. grep -n STATUS-$ARGUMENTS.md.

REPORT:
  milestones: <the Milestones table rows with done > 0, or "none started">
  total: <the **Total:** line>
  last row: <last line of "Done cards">
  order: <"clean" or ORDER VIOLATION lines>
  open bugs: <count of $ARGUMENTS-B* not FIXED in BUG.md>
Numbers only. No opinion.
