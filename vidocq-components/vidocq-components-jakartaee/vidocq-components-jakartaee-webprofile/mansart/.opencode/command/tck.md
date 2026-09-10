---
description: Wire the TCK runner, run the suite, get a calibrated counter. Zero is a valid result.
agent: lead
---
ARGS: $ARGUMENTS   (<XXX>, e.g. JKP)

DONE WHEN, AND ONLY WHEN: ./scripts/verify-m0.sh $ARGUMENTS is PASS on every
M0 card. YOU write nothing in this command — no file, no STATUS row. Scripts do
the work; you run them and report their last lines.

1. ./scripts/steps/STEP030_detect_tck.sh $ARGUMENTS
   exit 0 -> step 2.   exit 1 -> ./scripts/steps/STEP040_install_tck.sh $ARGUMENTS
     exit 5: no archive linked on the spec page — WebFetch the official
     distribution url, REPORT it, STOP. exit 7: JavaTest-style TCK — STOP, say so.
     exit 8: Maven could not install — report the log path, STOP.

2. ./scripts/steps/STEP070_wire_tck.sh $ARGUMENTS
   Returns at once: the wiring runs detached (four impl calls, then verify-and-
   fix rounds that run the TCK suite — 5 to 15 minutes). Your tool's timeout
   once killed it mid-round and left an impl editing the pom while you verified
   over it. That is why it detaches now.

3. ./scripts/steps/STEP071_wait_tck.sh $ARGUMENTS
   Waits up to 90 s and prints progress. exit 3 = RUNNING: call step 3 AGAIN.
   Keep calling it until it exits 0 (M0 COMPLETE) or 1 (finished, not complete).
   While it says RUNNING you run NOTHING else — not verify-m0.sh, not a build,
   not the suite. verify-m0.sh refuses anyway while the lock is held.

4. REPORT — copied from STEP071's final output, nothing invented:
     M0:      <n> pass, <n> fail
     counter: Tests run: <n>, Failures: <n>, Errors: <n>, Skipped: <n>
     next:    "M0 complete — /next $ARGUMENTS" or the first FAIL line verbatim
   A FAIL after the rounds is a wiring problem: report it, do not fix it by hand.
   The TCK's own counter (PASS=0, 989 errors) is conformance = /next's job.
