# HTTP sessions survive a dev swap: the Vidocq half

Status: approved by VIDOCQ on Yann's behalf, 2026-11-16, with the four calls kept. Date: 2026-11-16. Branch: `design/dev-session-handover`.

Companion: the Foy half, `foy` branch `feat/session-survives-swap`,
`docs/superpowers/specs/2026-11-16-session-survives-swap-design.md` (FOY, agreed). This document builds on its API and
does not repeat it. Related: the hot swap design, `docs/superpowers/specs/2026-11-16-dev-hotswap-design.md`
(`feat/dev-hotswap`), whose reload request this document extends.

## Intent

Yann decided (2026-11-16, item 6c of the Vaadin replay) that HTTP sessions survive an in-JVM swap of `vidocq:dev`.
Today each swap undeploys the servlet application, which invalidates every session. A Vaadin page open in the
browser then gets `sessionExpired` and starts over, losing the screen the developer was testing.

The hot swap fast path removes the swap for method-body changes. This work covers the other swaps: a new class, a
new lambda, an annotation, start-time code. Foy exports the live sessions while it undeploys and imports them into
the new deployment. Vidocq decides when that happens, keeps the bytes in the boot layer across the swap, and says
what it did.

## Goals

- On a swap, the sessions of the servlet application move to the new deployment. The browser keeps its
  `JSESSIONID`, and a Vaadin UI keeps its state.
- Vidocq refuses the move when the restored objects would be wrong, and says why in one line.
- A normal stop, a respawn, a packaged application, and a session without a hot swap session behave as today.

## Non-goals and anti-goals

- No sessions kept across a respawn of the child JVM, or across a restart of `vidocq:dev`. The bytes never leave
  the process.
- No change to Chappe's listeners. They close and reopen at each swap, as today. Keeping them open is a separate
  step (see "Push and the gap").
- Anti-goal: a restored session whose objects do not match the new code. That means a field the new code expects but
  the old instance never initialized, or a listener that runs another listener's code. A fresh session is better
  than that, as in the Foy half.

## Constraints from the code

- `VidocqDevReloadLoop.run` swaps with `bootstrap.shutdown()`, then `VidocqAppLayer.resetForReload()`, then a new
  `VidocqBootstrap`. The loop object outlives each boot, and it lives in the boot layer, with the core.
- `FoyExtension.begin` registers `FoyDeployments.closeAll` as a Chappe before-stop hook. `onStop` calls it again,
  as a no-op. So the deployments close while the listeners still serve, and the close covers both the auto-mount
  and a declarative `type=servlet` mount. There is at most one servlet deployment per boot
  (`FoyMountHandlerProvider` refuses a second one).
- A new deployment is built by `FoyDeployments.deploy`, registered, then mounted: `target.mount(...)` in
  `FoyExtension.start`, `return mounted.handler()` in `FoyMountHandlerProvider.create`. The import goes between the
  build and the mount (Foy half).
- The plugin and the runtime may be different versions. They talk only through the reload file. The A1 parser
  (`ReloadRequest.parse`) ignores any line after `swap <reason>`.
- Default Java serialization rebinds a serialized lambda by name. `SerializedLambda.readResolve` calls the capturing
  class's `$deserializeLambda$`, which matches on `implMethodName` and the signatures. javac names a serializable
  lambda `lambda$<method>$<hash>$<n>`. Two lambdas of one type passed to calls in one method share the hash, so `<n>`
  is their source order. A Vaadin listener (`ComponentEventListener` is `Serializable`) is exactly that case.
- With the default `serialVersionUID`, a gained or lost field, or a changed non-private method or constructor,
  changes the class's UID. Foy's import then fails and drops the session (`InvalidClassException`). A lambda method
  is private, so inserting one leaves the UID alone. A class that declares its `serialVersionUID` reads anyway, and a
  new field keeps its default value: `null` for a component the new constructor would have created.
- Measured with javac 25 (throwaway spike, 2026-11-16). A view registers `add(() -> "save")` then
  `add(() -> "cancel")` in its constructor, is serialized, and is read back by each new version of the class:

  | New version | Listeners read back |
  |---|---|
  | `() -> "reset"` inserted first | `[reset, save]`: both run another lambda's body, silently |
  | a lambda appended last | `[save, cancel]` |
  | the `save` body edited | the edited body, then `cancel` |
  | a private method added | `[save, cancel]` |
  | a public method added | `InvalidClassException` |
  | the last lambda removed | `InvalidObjectException`, from `$deserializeLambda$` |

  A lambda assigned to a field or a variable gets a hash of its own, so it keeps its name when another one is
  inserted.

## Design

### 1. Who decides: the mojo, in the reload request

The mojo already knows each changed class and its first shape difference (hot swap §2). A swap request gains a
third line:

```
<nanoTime>
swap MainView gained a method lambda$new$1
sessions keep
```

or `sessions drop <reason>`. With no `sessions` line, the sessions are dropped, as today. So an older plugin, or a
session with no start-time declarations (whose reload file stays `<nanoTime>\n`), never moves a session. Only a
plugin that ran the checks below vouches for the move. An older core ignores the line.

The mojo writes `sessions drop` with the first reason that applies:

1. `-Dvidocq.dev.keepSessions=false` (default `true`, a new parameter of `vidocq:dev`). Reason: `vidocq.dev.keepSessions
   is false`.
2. A changed class changed its serialized form: it gained, lost or retyped an instance field, or changed its
   superclass or its interfaces. Reason: `Order gained a field total, and the restored instances would not have
   it`. The classifier has these differences already (`ClassFingerprint.firstShapeDifference`). This rule needs
   every difference of the class, not only the first, so `ClassFingerprint` gains `instanceShapeDifference(previous)`.
   Without a declared `serialVersionUID`, Foy would drop these sessions anyway (`InvalidClassException`). The rule
   catches the classes that declare one, and says why in one line instead of one WARN per session.
3. A changed class gained or lost a lambda method while the code of a lambda it kept changed. Reason: `MainView's
   lambdas moved, and a restored listener could run another one's code`. This is the inserted-first row of the
   table above, the only case Foy cannot see. Lambdas appended after the existing ones, and lambda bodies edited in
   place, keep the sessions: a restored listener then runs its own, possibly edited, body. This is the developer's
   intent. Removing the last lambda also keeps them. A session holding the removed listener then fails to read with
   `InvalidObjectException` (measured), an `IOException`, and Foy drops that session alone.

Otherwise the mojo writes `sessions keep`. A redefinition never stops the application, so it has no `sessions` line
and touches no session.

`ReloadRequest.Swap` gains the verdict: `record Swap(String reason, Optional<String> dropSessions)`. Empty means
keep, so `Swap(String)` keeps reading as before for the callers of PR (a). `parse` reads the third line. A `swap`
with no `sessions` line parses as `dropSessions = Optional.of("")`, and the core logs nothing extra then.

### 2. The handover store: bytes in the boot layer

New in `vidocq-runtime-spi` (extensions depend on the SPI only):

```java
package io.vidocq.runtime.spi;

/**
 * State an extension hands to the next boot of the same JVM, during an in-JVM dev swap. Only vidocq:dev provides
 * it: an extension works unchanged when ExtensionContext.devHandover() is empty, as it always is in a packaged
 * application, a test or an embedding.
 */
public interface DevHandover {
    /** True while the stop in progress hands state over: a dev swap whose request said `sessions keep`. */
    boolean exporting();
    /** Only while exporting; a second put of a key replaces it. */
    void put(String key, byte[] value);
    /** The bytes the previous boot put under key, removed from the store. */
    Optional<byte[]> take(String key);
}
```

and on `ExtensionContext`: `default Optional<DevHandover> devHandover() { return Optional.empty(); }`. The SPI keeps
it for good, so its Javadoc says the same: only `vidocq:dev` provides it, and an extension must work unchanged when it
is empty.

In the core, `DevHandoverStore` implements it. `VidocqDevReloadLoop` creates one store for the JVM and passes it to
each `VidocqBootstrap` it creates (a package-private `devHandover(DevHandoverStore)` on the bootstrap), which hands it
to `ExtensionContextImpl`. Outside the loop (packaged application, tests, embedding) the context answers empty.

The loop's order on a swap signal:

1. read the request (`ReloadRequest.parse`), log the swap line with its reason (PR (a));
2. on `sessions keep`: `store.beginExport()`. On a drop with a non-empty reason: log `Hot reload - HTTP sessions not
   kept: <reason>`;
3. `bootstrap.shutdown()`, then `store.endExport()`, whatever happened;
4. `VidocqAppLayer.resetForReload()`, then the new boot;
5. once the new boot has started: `store.clear()`. Keys nobody took are dropped, with a DEBUG line naming them.

Keys are namespaced by the extension's id: `servlet-foy:sessions:<context path>`. Values are `byte[]` only, so no
object of the old layer is kept, and the old layer can be collected.

### 3. The Foy extension: export at the close, import before the mount

`FoyDeployments.closeAll(Optional<DevHandover> handover)` replaces `closeAll()`. When the handover is present and
`exporting()`, then for each live deployment:

- read `activeSessions()` for the count;
- call `mounted.closeExportingSessions()` in place of `close()`;
- `put` the bytes under the deployment's key.

It logs `Hot reload - handing 3 HTTP sessions to the next deployment`. Otherwise it closes and logs as today (#620).
`FoyExtension` keeps the `ExtensionContext`'s handover from `onStart` for the before-stop hook and `onStop`.

At start, `FoyExtension.start` and `FoyMountHandlerProvider.create` call `importSessions` after `register` and before
the mount. For that they `take` the key of the new deployment's context path, and when bytes are there call
`mounted.importSessions(bytes)` and log `Hot reload - kept 3 HTTP sessions across the reload`. Foy's own WARN lines
name the sessions dropped at export or import. A context path that changed between the two boots finds no bytes: its
sessions are dropped by `store.clear()`, as a new application's would be.

### 4. Push and the gap

In `vidocq:dev` the whole server stops during a swap, so Foy's 503 window does not apply. A request sent during the
swap gets a refused connection, and the push WebSocket is closed with 1001 before the export. What Vaadin's client
does then is to be measured in the replay (R-c below), not assumed:

- how long until the UI answers again;
- the same `JSESSIONID`;
- no `sessionExpired`;
- the UI state kept, and the push reconnected.

If the client waits too long (a reconnect interval of seconds), a later step can keep Chappe's listeners open across
a dev swap, answering 503 with `Retry-After` until the new handler is mounted. Not in this work.

### 5. With the hot swap

- Redefinition: nothing stops, so the sessions are untouched.
- A swap: this work.
- A lost request (hot swap §2: the next request is a swap) is still a swap from a running child, so its sessions
  move under the same rules.
- A respawn: a new JVM, so nothing moves.

## Tests (TDD, written first)

- `ReloadRequestTest`:
  - the `sessions keep` and `sessions drop <reason>` lines round-trip;
  - a swap with no `sessions` line drops;
  - a redefinition has none.
- Plugin, `HotSwapClassifierTest` and `HotSwapSessionTest`:
  - each drop rule, with real javac output: an instance field added to a class with and without a declared
    `serialVersionUID`, a superclass change, a lambda inserted before another one, a lambda appended (keep), a lambda
    body edited (keep);
  - `keepSessions=false`;
  - no declarations, so no line.
- Core, `DevHandoverStoreTest`:
  - `put` outside an export throws;
  - `take` removes the key;
  - `clear` drops the rest;
  - `endExport` runs after a shutdown that failed.
- Core, a boot outside the reload loop (`VidocqBootstrap.create()...start()`, as a packaged application starts):
  `devHandover()` answers empty in every extension's context.
- Core, `VidocqDevReloadLoopTest`: the order of §2 with a recording extension:
  - `exporting()` is true during its stop and false at its start;
  - the bytes it put are the bytes the next boot takes;
  - a `drop` request logs the reason, and the extension sees `exporting()` false.
- Foy extension, `FoyExtensionTest`, on a real Foy deployment through Chappe:
  - a session attribute survives `closeAll` with a handover, then a new `start`;
  - the same `JSESSIONID` finds it, and the two log lines are written;
  - without a handover, today's invalidation line.
- Replay R-c, on vaadin-on-vidocq with `vidocq-runtime-vaadin-extension`: a counter view.
  - Add a lambda after the existing ones: a swap, the counter's value kept, the same `JSESSIONID`, and the measured
    time until the UI answers.
  - Insert a lambda before them: a swap with `sessions drop`, and a fresh UI.
  - Add a field: a swap with `sessions drop`, and a fresh UI.
  - A FORM-login session: after a swap with `sessions keep`, still logged in when the Principal is serializable. A
    re-login is forced only when it is not (Foy half).

## Delivery

After hot swap PR (a), which brings `ReloadRequest` and the classifier, and after Foy PR 1's snapshot (Foy half).
One Vidocq PR holds:
- the SPI `DevHandover` and the `ExtensionContext` default;
- the core store and loop;
- the request's `sessions` line;
- the two mojo rules and `vidocq.dev.keepSessions`;
- the Foy extension calls;
- the docs: `usage.adoc` (dev reload), `vaadin.adoc` (what a swap keeps and when it drops, and that CDI state waits
  for the Vauban companion of the Foy half), `whats-new.adoc`.

Then replay R-c, and a vaadin-on-vidocq README note.

## Calls made here (VAADIN), for review

1. The mojo decides and writes the verdict, rather than the core. Only the mojo knows the changed classes and their
   shape differences. A missing line means drop, so an older plugin never moves a session.
2. The two drop rules (serialized form, moved lambdas) are conservative on purpose. A fresh session costs one page
   reload, and a wrongly restored one costs the developer's trust in the dev mode.
3. `vidocq.dev.keepSessions` defaults to `true`, so `false` gives today's behaviour on every swap.
4. Chappe's listeners keep closing at a swap. Whether to keep them open waits for the replay's measurement.
