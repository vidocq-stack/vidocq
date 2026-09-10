---
description: Wire the TCK runner, run the suite, get a calibrated counter. Zero is a valid result.
agent: lead
---
ARGS: $ARGUMENTS
Expected: <XXX>   e.g. JKP

The TCK is the ONLY progress metric. A card marked DONE without it means nothing.
A run that scores 0 is a SUCCESS for this command: the instrument now exists.

THIS COMMAND IS DONE WHEN, AND ONLY WHEN:
    ./scripts/verify-m0.sh $ARGUMENTS
prints PASS on every M0 card. Not when you think the module looks right. Not
when a build somewhere exits 0. That script is the judge; you are the hands.

0. WHAT YOU WRITE — almost nothing
   - TASKS-$ARGUMENTS.md: NEVER. Generated; the guard refuses it.
   - STATUS-$ARGUMENTS.md: NEVER, not even a row. verify-m0.sh writes the M0
     rows itself and refreshes the counts. An agent once wrote "PENDING" in a row
     and the counter read it as done. You write no rows.
   - Java, xml, pom, sh: NEVER. That is @impl, one call per artifact (step 2).
   - Your brief for a card, if you want one: the path comes from
       ./scripts/task-file.sh $ARGUMENTS M0-T001
     Never compose it — an agent told "tasks/<XXX>/<CARD>.md" wrote tasks/001/CARD.md.

   SCOPE: this command creates ONE new parent module and touches ONE line of the
   root pom.xml. It never edits mansart-jakarta-data, mansart-transactions,
   mansart-pool or anything under them. A run "fixed" a junit version in two
   delivered, TCK-passing modules on the way. The guard now refuses it; do not
   try.

1. IS THE TCK INSTALLED?
   ./scripts/steps/STEP030_detect_tck.sh $ARGUMENTS
   exit 0  -> installed, go to step 2.
   exit 1  -> NOT INSTALLED, which is not "this spec has no TCK". Install it:
              ./scripts/steps/STEP040_install_tck.sh $ARGUMENTS
              It reads the archive link off the spec page, verifies sha256,
              reads each jar's own coordinates, installs, then re-checks.
              exit 5 = the page links no archive: use WebFetch to find the
              official distribution url, REPORT IT, and STOP. Never fabricate a
              coordinate.
              exit 7 = a JavaTest-style TCK, not consumed via Maven: STOP and
              say so; mansart-transactions-tck is the runner of that family.
   Never `cat` spec-meta.json to "check" — the step prints what you need.

2. WIRE THE RUNNER — FOUR DELEGATIONS. YOU TYPE NOTHING.

   You issue four @impl calls, one per artifact, in this order, and after EACH
   one you run verify-m0.sh to see what moved. Your context is re-sent every
   step and @impl's is thrown away: typing it yourself costs you the context you
   need to notice what went wrong.

   THE PATH IS NOT YOURS TO CHOOSE:
       python3 scripts/tck-module.py $ARGUMENTS
   It prints <parent>/<runner>, read from module.conf. Use it verbatim. An agent
   once invented ee/jakarta/tck/persistence/mansart-jkp-tck.
   <parent>/<runner>/pom.xml already exists? Skip to step 3.

   Each call below starts with: "Delegate to @impl:" and tells @impl to READ,
   not you: pom.xml (root), mansart-jakarta-data/pom.xml,
   mansart-jakarta-data/mansart-data-tck/pom.xml and its run script. They are
   the working reference (74/74). @impl copies, then ADAPTS. Copying is right;
   copying unchanged is the trap.

   @impl 1 — THE PARENT, and its registration
     Create <parent>/pom.xml: packaging pom, parent io.vidocq.mansart:mansart-root,
     NO <modules>, NO <dependencies>. Add <module><parent></module> to the ROOT
     pom.xml <modules>. Touch NO other pom.
     TWO PIECES: the parent is IN the reactor, the runner is NOT. A run built the
     runner with no parent — a directory Maven never sees. Another registered
     BOTH in the root pom, putting the runner in the reactor.

   @impl 2 — THE RUNNER POM
     <parent>/<runner>/pom.xml: standalone Model 4.0.0, NO <parent>, NOT listed
     in any <modules>. groupId io.vidocq.mansart, java release 25.
     Depend on tck.recommended (spec-meta.json) and on the jakarta.persistence
     API. ON NOTHING THAT DOES NOT EXIST YET — a run declared six implementation
     modules copied from the data runner; none existed.
     Surefire, tck-run profile, needs ALL of these or the suite selects nothing
     and still exits 0:
       <include>**/Client.class</include>          Jakarta TCK tests are named Client
       <dependenciesToScan>the TCK artifact</...>   they live in the jar
       <systemPropertyVariables>
         platform.mode=standalone                   else every test asks for a container
         persistence.unit.name=JPATCK

   @impl 3 — THE RESOURCES
     src/test/resources/arquillian.xml and src/test/resources/persistence.xml.
     persistence.xml: version 3.2, units JPATCK and JPATCK2, RESOURCE_LOCAL,
     NO <provider> — a run shipped EclipseLink's, which measures EclipseLink.
     WRITE NO JAVA. None. The tests come from the TCK jar. The run that reached
     6/6 shipped zero .java files; the run that wrote four invented ShrinkWrap
     types and could not compile.

   @impl 4 — THE RUN SCRIPT
     <parent>/<runner>/run-official-tck-<spec>.sh, copied from the data runner's
     and adapted, chmod +x. IN THE MODULE — a run left a copy in scripts/ too.
     Replace every trace of the other spec: suite name, packages, artifactIds.

3. VERIFY, AND FIX UNTIL GREEN — this loop IS the command
   ./scripts/verify-m0.sh $ARGUMENTS
   It checks each card against the card's own artifact, RUNS THE SUITE itself
   when no counter exists yet (a few minutes), writes the Done rows, refreshes
   STATUS. Every FAIL line names what is missing and usually how to fix it.
   FAIL? Send the FAIL line to @impl as the instruction. Re-run. Up to 6 rounds.
   Do not paste build logs; the FAIL line is the whole message.

   M0 IS A CHAIN: T001, T002, T003, T004, T005, T006, in order. Each card is the
   ground the next stands on. A gap is reported as ORDER VIOLATION.

4. STOP — on the right kind of failure
   WIRING failure = verify-m0.sh says FAIL. That is THIS command's job. Loop.
   Only after 6 rounds do you stop, and you report the remaining FAIL verbatim.
   CONFORMANCE failure = the TCK's own counter: PASS=0, 989 errors. That is
   /next's job. Stopping is right.

   REPORT, all copied from verify-m0.sh output:
     M0:      <n> pass, <n> fail
     counter: Tests run: <n>, Failures: <n>, Errors: <n>, Skipped: <n>
     next:    "M0 complete — /next $ARGUMENTS" or the first FAIL line verbatim
