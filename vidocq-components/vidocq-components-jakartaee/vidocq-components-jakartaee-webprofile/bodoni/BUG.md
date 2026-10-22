# BUG.md — Bodoni

Reproducible bugs (internal issue, regression, incorrect behavior not yet fixed).
Entry format: `short id · date · symptom · minimal repro · cause hypothesis · status`.

## BOD-001 · 2026-10-22 · `varStatus` properties are unreadable in Ibarra pages with Peano reflection denied

- Symptom: a page precompiled by Ibarra and run on Foy with `-Dpeano.reflection=deny`
  answers HTTP 500 as soon as it reads a property of the `c:forEach` (or `c:forTokens`)
  status variable:
  `jakarta.el.ELException: Reflection fallback denied for jakarta.servlet.jsp.jstl.core.LoopTagSupport$1Status; register a generated accessor provider or a custom resolver`
  (thrown by `io.vidocq.peano.core.internal.evaluation.ReflectionGuard`). The same pages
  render correctly with `peano.reflection=allow`, under both the `jakarta.tags.*` and the
  `http://java.sun.com/jsp/jstl/*` URIs: TLD discovery, handler construction, functions
  and the tag lifecycle work.
- Minimal repro:
  ```jsp
  <%@ taglib prefix="c" uri="jakarta.tags.core" %>
  <c:forEach items="${['a','b']}" var="x" varStatus="s">${s.index}:${x}</c:forEach>
  ```
  In `bodoni-it-ibarra`, `status.jsp` and `CorePagesIT.forEachExposesVarStatusProperties`
  (disabled with a reference to this entry; it passes when the host runs with `allow`).
  Every other check of `./mvnw -ntp -Pit-ibarra -pl bodoni-it-ibarra -am verify` runs
  under `deny` and passes.
- Cause hypothesis: the status object is the anonymous `LoopTagStatus` implementation
  of the JSTL API's `LoopTagSupport` (Tags 3.0 §6.1, `varStatus` of type
  `jakarta.servlet.jsp.jstl.core.LoopTagStatus`). Peano resolves bean properties without
  reflection only through registered `AccessorProvider` tables; nothing registers one for
  `LoopTagStatus`. `bodoni-core` must not depend on Peano (AGENTS.md constraint 1). Not a
  Bodoni handler defect.
- Candidate owners (the maintainer decides): (1) Peano, with built-in accessors for
  Jakarta API types such as `LoopTagStatus`; (2) the Ibarra precompiler; (3) an optional
  Bodoni adapter module depending on Peano (engine-adapter exception of the brick/extension
  rule) that registers the `LoopTagStatus` accessor table; (4) the Bodoni Vidocq runtime
  extension. Cross-repo item in `tasks/todo.md` and `PLAN.md`.
- Status: open.
