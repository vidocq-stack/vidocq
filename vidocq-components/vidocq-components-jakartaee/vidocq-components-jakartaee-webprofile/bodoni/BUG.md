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
  `./mvnw -ntp -Pit-ibarra -pl bodoni-it-ibarra -am verify` — `CorePagesIT` 6/7 failing
  (every `core.jsp` / `legacy.jsp` check; `functions.jsp` passes).
- Cause hypothesis: the status object is the anonymous `LoopTagStatus` implementation
  of the JSTL API's `LoopTagSupport` (Tags 3.0 §6.1, `varStatus` of type
  `jakarta.servlet.jsp.jstl.core.LoopTagStatus`). Peano resolves bean properties without
  reflection only through registered `AccessorProvider` tables; nothing registers one for
  `LoopTagStatus`. `bodoni-core` must not depend on Peano (AGENTS.md constraint 1), so the
  table has to come from the Pages/EL side or from the Vidocq runtime extension that
  assembles Bodoni with Ibarra and Peano. Not a Bodoni handler defect.
- Status: open — Ibarra (cross-repo item in `tasks/todo.md`; owner to decide between
  Ibarra, Peano and the Vidocq runtime extension). `CorePagesIT` keeps asserting the
  correct rendering under `deny`.
