# HTTP Sessions Survive a Dev Swap: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On a `vidocq:dev` swap, the servlet application's HTTP sessions move to the new deployment, unless the
plugin found a reason they would come back wrong.

**Architecture:** The mojo adds a `sessions keep|drop <reason>` line to a swap request. The core loop opens an
export window around the stop of the old boot, and keeps the exported bytes in a store in the boot layer. The Foy
extension exports through `closeExportingSessions()` while the window is open, and imports through `importSessions`
before the new deployment is mounted. Only `byte[]` crosses the swap.

**Tech Stack:** Java 25, Java Modules, JUnit 5, the Class-File API (`ClassFingerprint`), Foy
`0.4.0-20261116.193241-27` or later.

**Spec:** `docs/superpowers/specs/2026-11-16-dev-session-handover-design.md` (this branch). Its §n references below
are to that file.

## Global Constraints

- The branch is stacked on the hot swap listeners pull request (`feat/dev-hotswap-listeners`), which is stacked on
  #627. It is rebased as each of them merges.
- Foy: `FoyChappeBoot.Mounted.closeExportingSessions()` and `importSessions(byte[])` come from foy
  `0.4.0-20261116.193241-27` (foy main 108a6bd2). Build with `-U` on a scratch repository, so that a remote snapshot
  older than that one does not win.
- The SPI change is additive: `DevHandover` is new, and `ExtensionContext.devHandover()` is a `default` method that
  answers `Optional.empty()`. Extensions depend on the SPI only.
- Nothing but `byte[]` is kept across a swap. No object of the old layer survives in the store.
- Exact copy:
  - third line of a swap request: `sessions keep`, or `sessions drop <reason>`;
  - drop reasons:
    - `vidocq.dev.keepSessions is false`;
    - `<Simple> <difference>, so a restored instance would not match the new class`;
    - `<Simple>'s lambdas moved, and a restored listener could run another one's code`;
  - core log lines:
    - `Hot reload - HTTP sessions not kept: <reason>`;
    - `Hot reload - kept 1 HTTP session across the reload` (`kept N HTTP sessions` when N > 1);
    - `Hot reload - the HTTP sessions of the previous deployment could not be imported, starting without them:
      <exception>` (WARNING).
- Handover key: `servlet:sessions:<context path>`, with `/` for the root context.
- `vidocq.dev.keepSessions`: a `boolean` parameter of `vidocq:dev`, default `true`.
- Commits: `git commit -S -s`, Conventional Commits in English, trailer
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Before the push: `-Pquality,it verify` on the whole reactor and the local SonarQube analysis. Judge the new coverage
  file by file, for the files this PR changes; Sonar's `main` baseline may be stale.

## Review Focus

1. Chappe's before-stop hook calls `closeAll`, and `onStop` calls it again in the same stop. The second call must not
   replace the exported bytes with an empty export. Pinned in Task 5.
2. A declarative `vidocq.http.mount.<name>.type=servlet` mount builds its deployment in `FoyMountHandlerProvider.create`,
   not in `FoyExtension.start`. It must import too, before it returns its handler. Pinned in Task 5.
3. An unconfirmed request (`lost`: the child never acknowledged the previous one) is still a swap of a running child.
   Its verdict is computed against the last acknowledged output, like any swap's. Pinned in Task 2.
4. A context path that changed between two boots finds no bytes. The new deployment starts empty and serves, and the
   loop's `clear()` drops the stale key. Pinned in Task 5.
5. A swap with no session to move must stay quiet: an import of 0 logs nothing, and a silent drop (no `sessions`
   line, or `sessions drop` with no reason) logs nothing either. Pinned in Tasks 4 and 5.

---

### Task 1: The request's `sessions` line (core)

**Files:**
- Modify: `vidocq-runtime-core/src/main/java/io/vidocq/runtime/core/ReloadRequest.java`
- Test: `vidocq-runtime-core/src/test/java/io/vidocq/runtime/core/ReloadRequestTest.java`

**Interfaces:**
- Produces:
  - `record ReloadRequest.Swap(String reason, Optional<String> dropSessions)`. `Optional.empty()` means keep.
    `Optional.of("")` means drop and say nothing. `Optional.of(r)` means drop because of `r`.
  - `ReloadRequest.Swap(String reason)` builds `Swap(reason, Optional.of(""))`. A request no check vouched for never
    moves a session (spec §1), so the classifier's and `parse`'s fallbacks stay unchanged.

- [ ] **Step 1: Write the failing tests**
  - `aKeptSwapRoundTrips`:
    `parse(new Swap("MainView gained a method lambda$new$1", Optional.empty()).format(7))` equals that request, and
    `format(7)` equals `"7\nswap MainView gained a method lambda$new$1\nsessions keep\n"`.
  - `aDroppedSwapRoundTripsWithItsReason`: the same with `Optional.of("vidocq.dev.keepSessions is false")`, and the
    third line is `sessions drop vidocq.dev.keepSessions is false`.
  - `aSwapWithoutASessionsLineDropsSilently`:
    - `parse("7\nswap A changed\n")` equals `new Swap("A changed", Optional.of(""))`;
    - `parse("7\n")` and `parse("7\nswap\n")` give `dropSessions` equal to `Optional.of("")`;
    - `new Swap("x").format(7)` has no third line.
  - `anUnknownThirdLineDropsSilently`: `parse("7\nswap x\nsessions maybe\n").dropSessions()` is `Optional.of("")`.
  - `aRedefinitionHasNoSessionsLine`: an existing `Redefine` round trip is unchanged (no `sessions` line in `format`).
- [ ] **Step 2: Run them and see them fail.** Run `./mvnw -ntp -pl vidocq-runtime-core test -Dtest=ReloadRequestTest`
  (through the session's wrapper script, which adds the scratch repository; add `install -pl <upstream> -am` first
  when a task changes an upstream module). Expected: compilation error, because `Swap(String, Optional)` does not
  exist yet.
- [ ] **Step 3: Implement.** `parse` reads `lines[2]` for a swap: `sessions keep` gives empty, and
  `sessions drop` followed by the rest, stripped, gives `Optional.of(rest)`. Anything else gives `Optional.of("")`.
  `format` writes the third line only when `dropSessions` is empty or not blank.
- [ ] **Step 4: Run the tests and see them pass.** Then run the whole `vidocq-runtime-core` test suite: the existing
  `Swap(String)` callers compile unchanged.
- [ ] **Step 5: Commit** `feat(core): a swap request says whether the HTTP sessions move`.

### Task 2: The verdict (plugin)

**Files:**
- Modify: `vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven/dev/ClassFingerprint.java`
- Create: `vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven/dev/SessionVerdict.java`
- Modify: `vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven/dev/HotSwapSession.java` (`open`,
  `next`)
- Modify: `vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven/dev/VidocqDevMojo.java` (parameter, the
  `open` call)
- Test: `ClassFingerprintTest`, new `SessionVerdictTest`, `HotSwapSessionTest`, `VidocqDevMojoTest` (same package,
  `src/test/java`)

**Interfaces:**
- Consumes: Task 1's `Swap(String, Optional<String>)`.
- Produces:
  - `Optional<String> ClassFingerprint.instanceShapeDifference(ClassFingerprint previous)`. It returns the first of:
    `changed its superclass`, `changed its interfaces`, `gained a field <name>`, `lost a field <name>`,
    `changed the type of field <name>`. Only non-static fields count, `transient` ones included: a restored instance
    leaves them at their default too.
  - `boolean ClassFingerprint.lambdasMoved(ClassFingerprint previous)`. It is true when the set of methods named
    `lambda$…` (by name and descriptor) differs, and one present in both changed its code.
  - `static Optional<String> SessionVerdict.dropReason(ClassesSnapshot base, ClassesSnapshot next, boolean
    keepSessions)`. Empty means keep. The rules come in the spec's §1 order. Only the classes present in both
    snapshots with a different SHA-256 are checked; a new or deleted class decides nothing here.
  - `HotSwapSession.open(classRoots, projectSourceRoots, dependencyJars, redefinitionOff, keepSessions, info,
    warn)`. `boolean keepSessions` comes after `redefinitionOff`.

- [ ] **Step 1: Write the failing tests.** They use real javac output, through the classifier tests' existing
  `HotSwapProject` / `HotSwapFixtures` helpers:
  - `ClassFingerprintTest`:
    - `aGainedInstanceFieldIsAnInstanceShapeDifference`: `Optional.of("gained a field total")`;
    - `aGainedStaticFieldIsNot`: empty;
    - `aRetypedFieldIs`: `changed the type of field total`;
    - `aNewSuperclassIs`;
    - `aLambdaInsertedBeforeAnotherMovesTheLambdas`: two `add(() -> "save")`, `add(() -> "cancel")` calls, then
      `() -> "reset"` inserted first. The result is `true`.
    - `aLambdaAppendedDoesNotMoveThem`: `false`.
    - `aLambdaBodyEditedInPlaceDoesNotMoveThem`: `false`.
  - `SessionVerdictTest`:
    - `keepSessionsFalseDrops`: `Optional.of("vidocq.dev.keepSessions is false")`, whatever changed.
    - `aFieldAddedDropsWithOrWithoutADeclaredSerialVersionUid`: both give
      `Optional.of("Order gained a field total, so a restored instance would not match the new class")`.
    - `aLambdaInsertedFirstDrops`: `Optional.of("MainView's lambdas moved, and a restored listener could run another
      one's code")`.
    - `aLambdaAppendedKeeps`: `Optional.empty()`.
    - `aNewClassKeeps`: `Optional.empty()`.
  - `HotSwapSessionTest`:
    - `aSwapCarriesTheVerdict`: an appended lambda gives a `Swap` whose `dropSessions()` is empty.
    - `aRedefinitionCarriesNone`: still a `Redefine`.
    - `anUnconfirmedRequestStillChecksAgainstTheAcknowledgedOutput` (Review Focus 3): call `lost()`, then
      recompile with a field added. The `Swap(UNCONFIRMED, …)` drops with the field reason.
  - `VidocqDevMojoTest`, `keepSessionsDefaultsToTrue`: `PluginDescriptorCompletenessTest` already lists every
    parameter, so add the new one there.
- [ ] **Step 2: Run them and see them fail**: `-pl vidocq-runtime-maven-plugin -Dtest=ClassFingerprintTest,
  SessionVerdictTest,HotSwapSessionTest,VidocqDevMojoTest,PluginDescriptorCompletenessTest`, after installing
  Task 1's core.
- [ ] **Step 3: Implement.**
  - `next()` maps every `Swap` it returns, the `lost` one included, to
    `new Swap(swap.reason(), SessionVerdict.dropReason(base, snapshot, keepSessions))`.
  - The mojo's `@Parameter(property = "vidocq.dev.keepSessions", defaultValue = "true") boolean keepSessions` is
    passed to `open`.
- [ ] **Step 4: Run the tests and see them pass**, then the whole plugin suite.
- [ ] **Step 5: Commit** `feat(dev): the mojo vouches for the HTTP sessions of a swap, or says why not`.

### Task 3: The handover store (SPI and core)

**Files:**
- Create: `vidocq-runtime-spi/src/main/java/io/vidocq/runtime/spi/DevHandover.java` (the interface, as in spec §2,
  Javadoc included)
- Modify: `vidocq-runtime-spi/src/main/java/io/vidocq/runtime/spi/ExtensionContext.java`
- Create: `vidocq-runtime-core/src/main/java/io/vidocq/runtime/core/DevHandoverStore.java`
- Modify: `vidocq-runtime-core/src/main/java/io/vidocq/runtime/core/ExtensionContextImpl.java`, `VidocqBootstrap.java`
  (`extensionContext()`, new setter)
- Test: new `vidocq-runtime-core/src/test/java/io/vidocq/runtime/core/DevHandoverStoreTest.java`,
  `DevHandoverContextTest.java`

**Interfaces:**
- Produces:
  - `DevHandover`: `boolean exporting()`, `void put(String key, byte[] value)`, `Optional<byte[]> take(String key)`.
  - `default Optional<DevHandover> ExtensionContext.devHandover()`, which answers `Optional.empty()`.
  - `final class DevHandoverStore implements DevHandover`, thread-safe (`synchronized`), with:
    - `void beginExport()`;
    - `void endExport()`;
    - `Set<String> clear()`, which returns the keys it dropped;
    - `put` outside an export throws `IllegalStateException`;
    - `put` copies the array, and `take` removes the key.
  - `VidocqBootstrap devHandover(DevHandoverStore store)`, package-private, returns `this`.
    `extensionContext()` passes `Optional.ofNullable(store)` to `ExtensionContextImpl`, which gains that component;
    its existing constructor passes `Optional.empty()`.

- [ ] **Step 1: Write the failing tests.**
  - `DevHandoverStoreTest`:
    - `putOutsideAnExportThrows`;
    - `takeRemovesTheKey`: a second `take` is empty;
    - `clearDropsAndNamesTheRest`;
    - `exportingIsTrueOnlyBetweenBeginAndEnd`;
    - `putCopiesTheBytes`: changing the caller's array afterwards changes nothing.
  - `DevHandoverContextTest`:
    - `aBootOutsideTheLoopHasNoHandover`: `VidocqBootstrap.create().extensionContext().devHandover()` is empty.
    - `aBootOfTheLoopHandsTheStore`: with `devHandover(store)`, the context answers that same store.
- [ ] **Step 2: Run them and see them fail** (`vidocq-runtime-core`, with `install` for the SPI).
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them and see them pass**, then the SPI's and the core's suites, and
  `vidocq-runtime-architecture-tests`.
- [ ] **Step 5: Commit** `feat(spi): an extension can hand bytes to the next boot of a dev swap`.

### Task 4: The loop's order (core)

**Files:**
- Modify: `vidocq-runtime-core/src/main/java/io/vidocq/runtime/core/VidocqDevReloadLoop.java` (`run`, `serve`)
- Test: `vidocq-runtime-core/src/test/java/io/vidocq/runtime/core/VidocqDevReloadLoopTest.java`

**Interfaces:**
- Consumes: Task 1's `Swap.dropSessions()`, and Task 3's store and `VidocqBootstrap.devHandover`.
- Produces: `static void stopForSwap(ReloadRequest.Swap swap, DevHandoverStore store, Runnable shutdown,
  Consumer<String> info)`. It runs spec §2 steps 2 and 3:
  - begin the export on keep, or log the drop line when the reason is not blank;
  - run `shutdown`, then `endExport` in a `finally`.

- [ ] **Step 1: Write the failing tests** (the extension the spec describes is recorded by the `shutdown` runnable):
  - `aKeptSwapExportsDuringTheStop`: inside `shutdown`, `store.exporting()` is true and a `put` succeeds; afterwards
    `exporting()` is false and `take` gives the same bytes.
  - `aDroppedSwapSaysWhyAndDoesNotExport`: `info` gets
    `Hot reload - HTTP sessions not kept: vidocq.dev.keepSessions is false`, and inside `shutdown` `exporting()` is
    false.
  - `aSilentDropSaysNothing` (Review Focus 5): `new Swap("x")` gives no `info` line.
  - `theExportEndsWhenTheStopFails`: `shutdown` throws, the exception propagates, and `exporting()` is false
    afterwards.
- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Implement.**
  - `run` creates one `DevHandoverStore`, and `serve` passes it to each `VidocqBootstrap.create().devHandover(store)`.
  - After `bootstrap.start()`, `serve` calls `store.clear()` and logs the dropped keys at DEBUG
    (`Hot reload - nothing took the handover of <keys>`).
  - The swap branch keeps the parsed request, and calls `stopForSwap(swap, store, bootstrap::shutdown, info)` in
    place of the bare `bootstrap.shutdown()`. The `resetForReload` and the timing line stay as they are.
- [ ] **Step 4: Run the core suite and see it pass.**
- [ ] **Step 5: Commit** `feat(core): the reload loop keeps the handed-over bytes across a swap`.

### Task 5: Export and import (Foy extension)

**Files:**
- Modify: `vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-foy-servlet-extension/src/main/java/io/vidocq/runtime/extensions/jakartaee/web/foy/FoyDeployments.java`,
  `FoyExtension.java`, `FoyMountHandlerProvider.java`
- Test: the module's `FoyExtensionTest`, `FoyMountHandlerProviderTest` and `FoyDeploymentsTest` (create the latter if
  it does not exist)

**Interfaces:**
- Consumes: Task 3's `DevHandover` and `ExtensionContext.devHandover()`.
- Produces:
  - `int FoyDeployments.closeAll(Optional<DevHandover> handover)` replaces `closeAll()`. It returns the number of
    sessions invalidated, 0 when it exported.
  - `static String FoyDeployments.handoverKey(String contextPath)`.
  - `static void FoyDeployments.importSessions(FoyChappeBoot.Mounted mounted, Optional<DevHandover> handover)`. It
    takes the key and imports. It logs `kept N` when N > 0, and catches `RuntimeException` with the WARN line of the
    Global Constraints.
  - `FoyExtension.begin(Object bootKey, Optional<DevHandover> handover)`.

- [ ] **Step 1: Write the failing tests**, on a real Foy deployment through Chappe, as the existing
  `FoyExtensionTest` does:
  - `aSessionAttributeSurvivesASwap`:
    - set an attribute in a request;
    - run `closeAll` with an exporting handover;
    - start a new extension, which takes it;
    - the same `JSESSIONID` cookie reads the attribute;
    - the log has `Hot reload - kept 1 HTTP session across the reload`.
  - `withoutAHandoverTheCloseInvalidatesAsToday`: the #620 line, and `put` is never called.
  - `aSecondCloseInTheSameStopKeepsTheExport` (Review Focus 1): `closeAll` twice with the same exporting handover;
    the bytes taken still import the session.
  - `refusedBytesStartWithoutThem`: put `new byte[] {1, 2, 3}` under the key. One WARN line, starting with
    `Hot reload - the HTTP sessions of the previous deployment could not be imported`, and the deployment is mounted
    and answers 200.
  - `aDeclarativeServletMountImportsBeforeItReturnsItsHandler` (Review Focus 2), in `FoyMountHandlerProviderTest`.
  - `aChangedContextPathStartsEmpty` (Review Focus 4): bytes under `servlet:sessions:/old`, a deployment at `/new`.
    Nothing is taken, nothing is logged, and it serves.
  - `anEmptyExportLogsNothing` (Review Focus 5): no session before the swap gives no `kept` line.
- [ ] **Step 2: Run them and see them fail** (with `install`, so that the module resolves foy
  `0.4.0-20261116.193241-27`; check in the log that it is that snapshot, or a later one).
- [ ] **Step 3: Implement.**
  - `onStart` passes `context.devHandover()` to `begin`.
  - `start` and `FoyMountHandlerProvider.create` call `importSessions` after `register` and before the mount, or
    before they return the handler.
  - `closeAll` uses `closeExportingSessions()` and `put` only while `handover.exporting()`.
- [ ] **Step 4: Run the module's suite and see it pass.**
- [ ] **Step 5: Commit** `feat(servlet): HTTP sessions move across a dev swap`.

### Task 6: Docs

**Files:**
- Modify:
  - `docs/en/modules/ROOT/pages/usage.adoc` (the dev reload section, next to `#dev-reload-reasons`): what a swap keeps,
    the two drop rules, `vidocq.dev.keepSessions`.
  - `docs/en/modules/ROOT/pages/vaadin.adoc` (the `dev-reload-session` paragraph): a swap keeps the UI unless a rule
    drops it. Spec §3a: a session holding a `@SessionScoped` instance, the `@Inject HttpSession` bean or a CDI client
    proxy stays behind until the Vauban companion and Foy PR 2. Push reconnects after the swap (the time measured in
    Task 7).
  - `docs/en/modules/ROOT/pages/modules/vidocq-runtime-maven-plugin.adoc`: the parameter row.
  - `docs/en/modules/ROOT/pages/whats-new.adoc`.

- [ ] **Step 1: Write the pages**, with the exact log lines of the Global Constraints.
- [ ] **Step 2: Check the xrefs.** Grep each `xref:…#anchor` you add against its target page.
- [ ] **Step 3: Commit** `docs(dev): HTTP sessions across a swap, and what drops them`.

### Task 7: Gates and the replay

- [ ] **Step 1: Run the gates.** `-Pquality,it verify` on the whole reactor, then the SonarQube branch analysis
  (workspace `AGENTS.md`, Code quality): `BUILD SUCCESS`, quality gate
  OK, and no new issue. Read the new coverage per file in Chrome for every file Tasks 1 to 5 touch: each is at least
  80 %.
- [ ] **Step 2: Replay R-c** on vaadin-on-vidocq, with this branch installed in a scratch repository, offline, on
  port 18081. Open the page, type a name and click, so the field and the greeting hold state. Then:
  - Add a value change listener after the existing lambda. The log shows a swap with no `not kept` line, then
    `Hot reload - kept 1 HTTP session across the reload`. In the browser, the field and the greeting are still
    there, no `Session Expired`, and the next click answers. Record the time from the swap line until the click
    answers.
  - Insert a lambda before the button's. The log shows `Hot reload - HTTP sessions not kept: MainView's lambdas
    moved, …`, and the UI is fresh.
  - Add an instance field to `MainView`. The log shows `not kept: MainView gained a field …`, and the UI is fresh.
  - On `vidocq-runtime-foy-servlet-example` (FORM login in its `web.xml`): log in, add a class, and the session is
    still logged in after the swap.
- [ ] **Step 3: Write the PR body.** Summary, test plan, the replay's lines and the measured time. Then push and open
  the PR (stacked on (c) until it merges).
- [ ] **Step 4: Open the vaadin-on-vidocq README note** as a pull request on VidocqTools/vaadin-on-vidocq (Yann
  merges it): under `vidocq:dev`, a swap keeps the open page's session, except in the cases the Vaadin guide lists.
