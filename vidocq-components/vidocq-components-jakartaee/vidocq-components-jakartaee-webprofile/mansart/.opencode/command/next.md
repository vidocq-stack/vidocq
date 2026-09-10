---
description: Do ONE card end to end (TDD, build, sonar, commit) then STOP
agent: lead
---
ARGS: $ARGUMENTS   (<XXX>)

ONE card, then STOP. AGENTS.md §1-4 apply.

1. PICK   ./scripts/verify-m0.sh $ARGUMENTS must be 6 pass — else STOP: run /tck.
          First M1+ card in TASKS-$ARGUMENTS.md with no row in STATUS "Done
          cards". Say its id. None left? STOP.
2. LOCATE @recon: "card <id>: <one line>. Which files? 3 lines."
3. RED    @tdd: "failing test for card <id>: <requirement>". No RED? STOP.
4. GREEN  @impl: "make it pass, do not touch tests". Failed twice -> @thinker,
          then ONE more @impl. Still red -> note BLOCKED + error in the STATUS
          Log section, STOP.
5. MEASURE @verify: build + tests on the module. Red -> step 4.
6. SONAR  @verify: ./scripts/sonar.sh <module>. New issues -> @impl fixes, tests
          READ-ONLY, max 2 tries, @verify re-scans. Not configured -> "skipped".
7. COMMIT git add ONLY the files tdd and impl touched (never -A).
          <type>(<scope>): <what> [$ARGUMENTS <card-id>]  +  Co-Authored-By: <model>
          git commit -S -s. Signature fails -> not done, STOP.
8. STATUS append ONE row to "Done cards":
          | <card> | <YYYY-MM-DD HH:MM> | verify: run=<n> failures=<n> errors=<n>; sha <short> |
          Numbers from @verify only. Counts are computed: never touch them.
          ./scripts/verify-m0.sh $ARGUMENTS refreshes them and keeps your row.
9. STOP.  3 lines: card, numbers, sha. "run /push $ARGUMENTS when you want it pushed".
