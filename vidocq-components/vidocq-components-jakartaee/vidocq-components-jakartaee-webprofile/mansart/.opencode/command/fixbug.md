---
description: Fix one bug from BUG.md, same engine as /next
agent: lead
---
ARGS: $ARGUMENTS
Expected: <XXX-Bnnn>   e.g. JKP-B001

1. READ the entry in BUG.md. grep -n, never cat (346 lines, guard denies it).
   Not found? STOP.

2. REPRODUCE FIRST
   @tdd: "write a test that reproduces $ARGUMENTS: <symptom>".
   Test does NOT fail? The bug is not reproducible as written.
   Say so, update BUG.md status, STOP. Do not fix what you cannot reproduce.

3. Then steps 4 to 8 of /next: @impl, @verify, sonar, commit, status.
   Commit message: fix(<scope>): <what> [$ARGUMENTS]

4. UPDATE BUG.md: status FIXED, date, commit sha, one line on the real cause.
   Keep the existing format (short id, date, symptom, repro, cause, status).

5. STOP. 3 lines.
