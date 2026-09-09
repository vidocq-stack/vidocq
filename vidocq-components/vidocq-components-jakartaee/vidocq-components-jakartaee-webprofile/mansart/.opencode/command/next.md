---
description: Do ONE card end to end (TDD, build, sonar, commit) then STOP
agent: lead
---
ARGS: $ARGUMENTS
Expected: <XXX>   e.g. JKP

ONE card. Then STOP. Do not start the next one.

1. PICK
   Read TASKS-$ARGUMENTS.md and STATUS-$ARGUMENTS.md.
   Take the first card not DONE. Say its id out loud.
   Nothing left? Say so and STOP.

2. LOCATE
   @recon: "card <id> says <one line>. Which files? 3 lines."

3. RED
   @tdd: "write the failing test for card <id>: <requirement>".
   No RED test? STOP. There is nothing to implement.

4. GREEN
   @impl: "make it pass. Do not touch tests."
   Failed twice? @thinker, then ONE more @impl. Still failed? Write it in
   STATUS-$ARGUMENTS.md as BLOCKED with the error, and STOP.

5. MEASURE
   @verify: build + tests on the touched module.
   Red? back to step 4.

6. SONAR
   @verify: ./scripts/sonar.sh <module>
   New issues? @impl fixes them — TESTS ARE READ-ONLY IN THIS PHASE. Max 2 tries.
   Then @verify re-scans. Still failing after 2? BLOCKED in STATUS, STOP.
   Sonar not configured? Say "sonar: skipped", continue.

7. COMMIT (never push)
   git add only the files impl and tdd touched. Never -A.
   Message, English, Conventional Commits:
     <type>(<scope>): <what> [$ARGUMENTS <card-id>]

     Co-Authored-By: <the model that wrote the code>
   Commit with: git commit -S -s
   -s fills Signed-off-by from git config. NEVER hardcode a name or email.
   Signature fails -> the card is NOT done. Report and STOP.

8. STATUS
   Update STATUS-$ARGUMENTS.md: card DONE, date, commit sha, the verify numbers.
   Numbers come from @verify. Never from your own impression.

9. STOP. Report 3 lines: card, numbers, sha.
   Say: "run /push $ARGUMENTS when you want it pushed".
