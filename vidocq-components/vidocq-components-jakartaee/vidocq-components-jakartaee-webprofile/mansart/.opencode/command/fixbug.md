---
description: Fix one bug from BUG.md, same engine as /next
agent: lead
---
ARGS: $ARGUMENTS   (<XXX-Bnnn>)

1. grep -n the entry in BUG.md (never cat it). Not found? STOP.
2. REPRODUCE FIRST. @tdd: "test that reproduces $ARGUMENTS: <symptom>".
   Does not fail? Not reproducible as written: say so, update BUG.md, STOP.
3. Steps 4-8 of /next. Commit: fix(<scope>): <what> [$ARGUMENTS]
4. BUG.md: status FIXED, date, sha, one line on the real cause. Keep the format.
5. STOP. 3 lines.
