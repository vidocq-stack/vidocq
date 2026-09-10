---
description: Wire the TCK runner, run the suite, get a calibrated counter. Zero is a valid result.
agent: lead
---
ARGS: $ARGUMENTS   (<XXX>, e.g. JKP)

DONE WHEN, AND ONLY WHEN: ./scripts/verify-m0.sh $ARGUMENTS is PASS on every
M0 card. YOU write nothing in this command — no file, no STATUS row. Scripts
do the work; you run them and report their last lines.

1. ./scripts/steps/STEP030_detect_tck.sh $ARGUMENTS
   exit 1 -> ./scripts/steps/STEP040_install_tck.sh $ARGUMENTS
     exit 5: the spec page links no archive — WebFetch the official distribution
     url, REPORT it, STOP. exit 7: JavaTest-style TCK — STOP and say so.

2. ./scripts/steps/STEP070_wire_tck.sh $ARGUMENTS
   It calls the impl agent four times, one artifact each, then runs verify-m0.sh
   in fix rounds until every M0 card passes (it runs the TCK suite itself; count
   several minutes). It writes STATUS. Do not interrupt it, do not "help" it by
   creating files: a lead that did so ended with four invented types and an
   uncompilable module. Long runs are normal; do not re-run it in parallel.

3. REPORT — copied from the script's final verify output, nothing invented:
     M0:      <n> pass, <n> fail
     counter: Tests run: <n>, Failures: <n>, Errors: <n>, Skipped: <n>
     next:    "M0 complete — /next $ARGUMENTS" or the first FAIL line verbatim
   verify-m0 FAIL after the rounds = wiring, still this command's problem: report
   the FAIL line. The TCK's own counter (PASS=0, 989 errors) = conformance =
   /next's job. Zero is the baseline, not a problem.
