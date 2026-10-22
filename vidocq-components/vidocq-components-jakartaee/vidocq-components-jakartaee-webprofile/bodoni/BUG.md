# BUG.md — Bodoni

Reproducible bugs (internal issue, regression, incorrect behavior not yet fixed).
Entry format: `short id · date · symptom · minimal repro · cause hypothesis · status`.

## BOD-001 · 2026-10-22 · `varStatus` and `Map.Entry` properties are unreadable in Ibarra pages with Peano reflection denied

- Symptom: a page precompiled by Ibarra and run on Foy with `-Dpeano.reflection=deny`
  answers HTTP 500 as soon as it reads a property of the `c:forEach` (or `c:forTokens`)
  status variable:
  `jakarta.el.ELException: Reflection fallback denied for jakarta.servlet.jsp.jstl.core.LoopTagSupport$1Status; register a generated accessor provider or a custom resolver`
  (thrown by `io.vidocq.peano.core.internal.evaluation.ReflectionGuard`). Extended
  2026-10-22 (B1 final review): iterating a `Map` fails the same way as soon as the page
  reads `${e.key}` or `${e.value}` of the `Map.Entry` item (Tags 3.0 §6.1.2):
  `Reflection fallback denied for java.util.Collections$UnmodifiableMap$UnmodifiableEntrySet$UnmodifiableEntry`
  for `items="${param}"`. The same pages
  render correctly with `peano.reflection=allow`, under both the `jakarta.tags.*` and the
  `http://java.sun.com/jsp/jstl/*` URIs: TLD discovery, handler construction, functions
  and the tag lifecycle work.
- Minimal repro:
  ```jsp
  <%@ taglib prefix="c" uri="jakarta.tags.core" %>
  <c:forEach items="${['a','b']}" var="x" varStatus="s">${s.index}:${x}</c:forEach>
  ```
  ```jsp
  <c:forEach items="${param}" var="e">${e.key}=${e.value};</c:forEach>
  ```
  In `bodoni-it-ibarra`, `status.jsp` with `CorePagesIT.forEachExposesVarStatusProperties`
  and `entries.jsp` with `CorePagesIT.forEachExposesMapEntryKeyAndValue` (both disabled
  with a reference to this entry until the fix; both pass when the host runs with `allow`).
  `CorePagesIT.denyModeIsActive` (enabled) proves the host really runs under `deny`.
  Every other check of `./mvnw -ntp -Pit-ibarra -pl bodoni-it-ibarra -am verify` runs
  under `deny` and passes.
- Cause hypothesis: the status object is the anonymous `LoopTagStatus` implementation
  of the JSTL API's `LoopTagSupport` (Tags 3.0 §6.1, `varStatus` of type
  `jakarta.servlet.jsp.jstl.core.LoopTagStatus`), and the `Map.Entry` items are the JDK's
  entry classes. Peano resolves bean properties without reflection only through registered
  `AccessorProvider` tables; nothing registers one for `LoopTagStatus` or `Map.Entry`. `bodoni-core` must not depend on Peano (AGENTS.md constraint 1). Not a
  Bodoni handler defect.
- Candidate owners (the maintainer decides): (1) Peano, with built-in accessors for
  Jakarta API types such as `LoopTagStatus` and JDK types such as `Map.Entry`; (2) the Ibarra precompiler; (3) an optional
  Bodoni adapter module depending on Peano (engine-adapter exception of the brick/extension
  rule) that registers the `LoopTagStatus` and `Map.Entry` accessor tables; (4) the Bodoni Vidocq runtime
  extension. Cross-repo item in `tasks/todo.md` and `PLAN.md`.
- Status: fixed (2026-10-22), in two parts. (1) `Vidocq/peano`, branch
  `feat/accessor-supertype-lookup`: Peano resolves an accessor table by the runtime class, then
  its superclasses, then its interfaces, and ships a built-in `java.util.Map.Entry` table
  (`${e.key}`, `${e.value}`). (2) `bodoni-peano`, the optional engine adapter (engine-adapter
  exception of the brick/extension rule; `bodoni-core` stays free of Peano): it provides the
  read-only `LoopTagStatus` table (`current`, `index`, `count`, `first`, `last`, `begin`, `end`,
  `step`) through an `AccessorProvider`. `CorePagesIT.forEachExposesVarStatusProperties` and
  `forEachExposesMapEntryKeyAndValue` are enabled and pass under `deny`.
