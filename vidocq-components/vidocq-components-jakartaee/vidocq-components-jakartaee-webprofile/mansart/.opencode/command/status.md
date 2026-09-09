---
description: Read-only summary of a spec's progress
agent: lead
---
ARGS: $ARGUMENTS
Expected: <XXX>

READ ONLY. Change nothing. Call no subagent. Run no build.

grep the counters out of STATUS-$ARGUMENTS.md and TASKS-$ARGUMENTS.md.

REPORT:
  milestone: <current M> — <n done>/<n total> cards
  last card: <id> <date> <sha>
  blocked: <ids, or none>
  open bugs: <count of $ARGUMENTS-B* not FIXED in BUG.md>

Numbers only. No opinion on whether it is going well.
