---
description: Wire the TCK runner, run the suite, get a calibrated counter. Zero is a valid result.
agent: lead
---
ARGS: $ARGUMENTS   (<XXX>, e.g. JKP)

DONE WHEN, AND ONLY WHEN:  ./scripts/verify-m0.sh $ARGUMENTS  is PASS on every
M0 card. It is the judge; you are the hands. AGENTS.md §1-3 apply: you type no
java/xml/pom/sh, no TASKS, no STATUS rows; delivered modules are frozen.

1. TCK INSTALLED?
   ./scripts/steps/STEP030_detect_tck.sh $ARGUMENTS
   exit 1 -> ./scripts/steps/STEP040_install_tck.sh $ARGUMENTS
             exit 5: no archive linked on the spec page — WebFetch the official
             distribution url, REPORT it, STOP. exit 7: JavaTest-style TCK — STOP,
             say so (mansart-transactions-tck is that family's runner).
   Never cat spec-meta.json; the step prints what you need.

2. WIRE — FOUR "Delegate to @impl:" CALLS, one per artifact, verify after each.
   Path: python3 scripts/tck-module.py $ARGUMENTS -> <parent>/<runner>. Verbatim.
   Runner pom already there? Go to 3.
   Tell @impl (not you) to read the references: pom.xml (root),
   mansart-jakarta-data/pom.xml, mansart-jakarta-data/mansart-data-tck/pom.xml
   and its run script. Copy, then ADAPT every trace of the other spec.

   @impl 1  PARENT: <parent>/pom.xml, packaging pom, parent
            io.vidocq.mansart:mansart-root, NO <modules>, NO <dependencies>.
            Add <module><parent></module> to the ROOT pom. Touch no other pom.
   @impl 2  RUNNER POM: <parent>/<runner>/pom.xml, standalone Model 4.0.0, NO
            <parent>, listed in NO <modules>. groupId io.vidocq.mansart, release 25.
            Depends on tck.recommended + jakarta.persistence API, and on NOTHING
            that does not exist yet. Surefire (tck-run profile) needs all of:
              <include>**/Client.class</include>      (TCK tests are named Client)
              <dependenciesToScan> the TCK artifact  (they live in the jar)
              systemPropertyVariables platform.mode=standalone,
                                      persistence.unit.name=JPATCK
   @impl 3  RESOURCES: src/test/resources/arquillian.xml and persistence.xml
            (version 3.2; units JPATCK and JPATCK2, RESOURCE_LOCAL; NO <provider>).
            WRITE NO JAVA — the tests come from the jar.
   @impl 4  RUN SCRIPT: <parent>/<runner>/run-official-tck-<spec>.sh, from the
            data runner's, adapted, chmod +x, IN the module only.

3. VERIFY AND FIX — this loop is the command
   ./scripts/verify-m0.sh $ARGUMENTS
   It checks each card against its own artifact, RUNS the suite when no counter
   exists (minutes), writes the Done rows, refreshes STATUS, and names what is
   missing on every FAIL line. FAIL -> that line is the next @impl instruction.
   Up to 6 rounds. M0 is a chain T001..T006: gaps show as ORDER VIOLATION.

4. STOP on the right failure. verify-m0 FAIL = wiring = yours, keep looping.
   The TCK's own counter (PASS=0, 989 errors) = conformance = /next's. Report:
     M0:      <n> pass, <n> fail
     counter: Tests run: <n>, Failures: <n>, Errors: <n>, Skipped: <n>
     next:    "M0 complete — /next $ARGUMENTS" or the first FAIL line verbatim
