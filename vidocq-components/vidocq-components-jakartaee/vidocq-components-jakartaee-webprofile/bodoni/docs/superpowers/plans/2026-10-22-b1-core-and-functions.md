# Bodoni B1 — `fn:` functions and `c:` core tags — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the Jakarta Standard Tag Library 3.0 `fn:` functions and the whole `c:` core library (spec §4–§7, §15) as portable classic tag handlers with their TLDs, under both the `jakarta.tags.*` and the legacy `http://java.sun.com/jsp/jstl/*` URIs, and prove that real pages using them render on Ibarra.

**Architecture:** `bodoni-core` holds the handlers (`io.vidocq.bodoni.core.tags.core`), the static functions (`io.vidocq.bodoni.core.functions`) and non-exported support (`io.vidocq.bodoni.core.internal`); TLDs live in `META-INF/`. Handlers receive already-evaluated attribute values from the Pages container and only touch the JSTL, Pages, Servlet and EL APIs. Unit tests drive the classic tag lifecycle on hand-written doubles; a separate, unpublished `bodoni-it-ibarra` module precompiles real pages with Ibarra and renders them over HTTP.

**Tech Stack:** Java 25, Java Modules, Maven 3.9.16, JUnit 6 (Jupiter), `jakarta.servlet.jsp.jstl-api` 3.0.2 (compile), Pages 4.0 / Servlet 6.1 / EL 6.0 APIs (provided), `java.net.http` (JDK).

**Spec:** `PLAN.md` (design of record, milestone B1) and the Jakarta Standard Tag Library 3.0 specification, <https://jakarta.ee/specifications/tags/3.0/jakarta-tags-spec-3.0.html> — every task cites the sections it implements; read them before writing the test.

## Global Constraints

- Work in the worktree `bodoni/feat/b1-core-and-functions` (branch `feat/b1-core-and-functions`). One pull request for the whole of B1.
- **Clean room (provenance).** Write every handler, function and TLD from the spec text and the API Javadoc only. Do **not** open, copy or paraphrase the sources of the GlassFish/Eclipse JSTL implementation (`org.glassfish.web:jakarta.servlet.jsp.jstl`), Apache Taglibs, or Tomcat's `org.apache.jasper.tagplugins`. The workspace runs a code-similarity audit (`PROVENANCE-AUDIT.md`) against reference implementations.
- **Mandatory TDD**: each step that adds production code is preceded by a failing test that was run and seen failing.
- **Dependencies**: `bodoni-core` main scope stays `jakarta.servlet.jsp.jstl-api` (compile) + Pages / Servlet / EL APIs (provided). No Ibarra, Peano, Foy, Vauban, Mockito, or any other library in `bodoni-core`, at any scope except `junit-jupiter` (test).
- **No `java.beans`**, no `setAccessible`, no reflection in production code (EL resolution goes through `PageContext.getELContext().getELResolver()`). Tests may use reflection.
- **No `synchronized`, no `ThreadLocal`.** Handler state lives in fields and is reset in `release()` and at the start of each `doStartTag`.
- **Licence header** from `etc/license-header.txt` (with `${year}` = 2026) on every `.java` file; the Maven build checks it.
- **English** for code, Javadoc, comments, commit messages. Say "Java Modules", never "JPMS".
- **Commits**: Conventional Commits, `git commit -S -s`, trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`, cite the spec section (e.g. `feat(core): <c:forEach> (Tags 3.0 §6.2)`).
- **Build**: `./mvnw -ntp clean install` green at the end of every task (`sdk env` first).

## Decisions taken with the maintainer (2026-10-22)

| Topic | Decision |
|---|---|
| URIs | Ship `jakarta.tags.core` / `jakarta.tags.functions` **and** the JSTL 1.2 URIs `http://java.sun.com/jsp/jstl/core` / `http://java.sun.com/jsp/jstl/functions`, served by the same classes. The 1.0 URIs (`http://java.sun.com/jstl/core`, `…/core_rt`) are **not** shipped: their non-`rt` tags evaluate EL themselves. |
| Packages | `io.vidocq.bodoni.core.tags.core` (handlers), `io.vidocq.bodoni.core.functions` (`fn:`), `io.vidocq.bodoni.core.internal` (support, not exported). Later libraries follow as `…tags.fmt`, `…tags.xml`, `…tags.sql`. |
| `c:import` | `java.net.http.HttpClient` for `http`/`https`; `URLConnection` for other absolute schemes. No public client SPI: an internal seam (`ExternalResources`) exists only for tests. Revisit if someone needs a custom client — `HttpClient` already honours `ProxySelector.getDefault()` and `SSLContext.getDefault()`. |
| Scope | One PR for all of B1. |
| Ibarra IT | Unpublished module `bodoni-it-ibarra`, in the reactor **only under the `it-ibarra` profile**: Ibarra and Peano are not on Maven Central yet, so CI cannot resolve them; run locally after `install` of chappe, foy, peano and ibarra. |

## File Structure

```
bodoni-core/src/main/java/module-info.java                         (modify: exports, requires java.net.http)
bodoni-core/src/main/java/io/vidocq/bodoni/core/internal/
    Xml.java              escapeXml(String) — shared by c:out and fn:escapeXml
    Scopes.java           scope name → PageContext constant, validation
    Urls.java             absolute-URL test, context-relative resolution, parameter encoding
    ExternalResources.java  seam: open an absolute URL as a Reader (HttpClient / URLConnection)
bodoni-core/src/main/java/io/vidocq/bodoni/core/functions/Functions.java      all 16 fn: functions
bodoni-core/src/main/java/io/vidocq/bodoni/core/tags/core/
    OutTag SetTag RemoveTag CatchTag                     §4
    IfTag ChooseTag WhenTag OtherwiseTag                 §5
    ForEachTag ForTokensTag                              §6
    ImportTag UrlTag RedirectTag ParamTag ParamParent    §7 (ParamParent package-private)
bodoni-core/src/main/resources/META-INF/
    c.tld  c-1_2.tld  fn.tld  fn-1_2.tld
bodoni-core/src/test/java/io/vidocq/bodoni/core/testing/          doubles + TagHarness (Task 2)
bodoni-core/src/test/java/io/vidocq/bodoni/core/...               one test class per production class
bodoni-it-ibarra/                                                   Task 7
```

## Review Focus

1. **A handler instance reused across invocations** (Jasper/Open Liberty pool classic handlers; Ibarra does not): state from a previous use (a `var`, a `when` already taken, params of a previous `c:url`) leaking into the next. Each stateful handler's test runs it twice on the same instance with different attributes.
2. **`null` everywhere the spec says "do nothing"**: `c:forEach items="${null}"`, `c:out value="${null}"` with no default, `fn:length(null)`, `fn:join(null, …)`, `c:set value="${null}"`. Each has a test in the owning task.
3. **Nested scoped variables restored after the loop** (§2.2.2): `var`/`varStatus` of `c:forEach` must be removed from page scope when the loop ends, even when the body throws. Test in Task 4.
4. **Escaping by default**: `c:out` escapes unless `escapeXml="false"`, and it escapes the *default* value too. Test in Task 3.
5. **URL rewriting only for relative URLs** (§7.5): an absolute `c:url` must never pass through `encodeURL` (it would leak the session id). Test in Task 5.

---

### Task 1: `fn:` functions, shared XML escaping, and the two `fn` TLDs (§15)

**Files:**
- Create: `bodoni-core/src/main/java/io/vidocq/bodoni/core/internal/Xml.java`
- Create: `bodoni-core/src/main/java/io/vidocq/bodoni/core/functions/Functions.java`
- Create: `bodoni-core/src/main/resources/META-INF/fn.tld`, `bodoni-core/src/main/resources/META-INF/fn-1_2.tld`
- Modify: `bodoni-core/src/main/java/module-info.java`
- Test: `bodoni-core/src/test/java/io/vidocq/bodoni/core/internal/XmlTest.java`, `…/functions/FunctionsTest.java`, `…/functions/FunctionsTldTest.java`

**Interfaces:**
- Produces: `static String Xml.escape(String text)` (null → `""`), used by Task 3 (`c:out`).
- Produces: `public final class Functions` with the 16 `public static` methods of §15, signatures exactly as the spec's function table (`boolean contains(String, String)`, `int length(Object)`, `String[] split(String, String)`, `String join(String[], String)`, …).
- Produces: a test helper `TldReader` (in `src/test/java/io/vidocq/bodoni/core/testing/`) that parses a TLD from the class path with `javax.xml.parsers` and exposes `uri()`, `shortName()`, `functions()` (name, class, signature) and `tags()` (name, class, body-content, attributes: name/required/rtexprvalue/deferred). Task 6 reuses it.

- [ ] **Step 1: Write the failing tests**
  - `XmlTest.escapesTheFiveCharacters`: `Xml.escape("<a href=\"x\">'&'</a>")` equals `"&lt;a href=&#034;x&#034;&gt;&#039;&amp;&#039;&lt;/a&gt;"` (table of spec §4.2 / §11.3).
  - `XmlTest.nullIsEmpty`: `Xml.escape(null)` equals `""`.
  - `FunctionsTest`: one test per function with the spec's example or rule, plus the null rules of §15 — at least: `length(null) == 0`, `length("abc") == 3`, `length(List.of(1,2)) == 2`, `length(new int[3]) == 3`, `length(Map.of("a",1)) == 1`; `substring("foobar", 2, 4) == "ob"`, `substring("foobar", -1, 2) == "fo"`, `substring("foobar", 3, -1) == "bar"`, `substring("foobar", 4, 1) == ""`; `split("a,b,,c", ",")` equals `{"a","b","c"}` (tokenizer semantics, empty tokens dropped) and `split("", ",")` equals `{""}` and `split("a,b", "")` equals `{"a,b"}` (§15.10); `join(null, ",") == ""`; `indexOf("abc", "") == 0`; `replace("aXbX", "X", "")` equals `"ab"`; `containsIgnoreCase("ABC", "b")`; `substringAfter("foo-bar", "-") == "bar"`, `substringBefore("foo", "x") == ""`; `escapeXml` delegates to `Xml.escape`; every `String` parameter given `null` behaves as `""`.
  - `FunctionsTldTest`: for both `META-INF/fn.tld` (uri `jakarta.tags.functions`) and `META-INF/fn-1_2.tld` (uri `http://java.sun.com/jsp/jstl/functions`): short-name `fn`; exactly the 16 function names of §15; each `function-class` is `io.vidocq.bodoni.core.functions.Functions` and each `function-signature` resolves, by reflection in the test, to a public static method; and the two files are identical apart from the `<uri>` element.

- [ ] **Step 2: Run them to verify they fail**
  Run: `./mvnw -ntp -pl bodoni-core test`
  Expected: compilation failure (`Xml`, `Functions` missing).

- [ ] **Step 3: Implement `Xml.escape`, `Functions` and the two TLDs**
  TLD header: the newest `web-jsptaglibrary_*.xsd` shipped in `jakarta.servlet.jsp-api` 4.0.0 (look inside the jar for the schema and its `version`); `tlib-version` 3.0. `module-info.java`: `exports io.vidocq.bodoni.core.functions;` (unqualified — generated pages call the functions directly).

- [ ] **Step 4: Run them to verify they pass**
  Run: `./mvnw -ntp -pl bodoni-core test`
  Expected: PASS.

- [ ] **Step 5: Commit** — `feat(functions): fn: functions and TLDs (Tags 3.0 §15)`

---

### Task 2: Tag test harness and servlet doubles

**Files:**
- Create in `bodoni-core/src/test/java/io/vidocq/bodoni/core/testing/`: `TestServletContext`, `TestHttpSession`, `TestHttpServletRequest`, `TestHttpServletResponse`, `TestJspWriter`, `TestBodyContent`, `TestELContext`, `TestPageContext`, `TagHarness`, `Body`
- Create: `bodoni-core/src/main/java/io/vidocq/bodoni/core/internal/Scopes.java`
- Test: `…/testing/TagHarnessTest.java`, `…/internal/ScopesTest.java`

**Interfaces:**
- Produces: `TestPageContext(TestHttpServletRequest, TestHttpServletResponse, TestServletContext)` — real four-scope attribute maps (page map, request/session/application delegated to the doubles), `findAttribute` in page→request→session→application order, `removeAttribute(name)` across all scopes, `pushBody()`/`popBody()` stacking `TestBodyContent`, `getOut()`, `getELContext()` returning a `TestELContext`.
- Produces: `TestELContext` — `CompositeELResolver` of `MapELResolver`, `ListELResolver`, `ArrayELResolver`, `BeanELResolver` (all from `jakarta.el-api`) and a working `VariableMapper` (a `HashMap`).
- Produces: `TestHttpServletResponse` records `encodeURL` / `encodeRedirectURL` calls (it appends `;jsessionid=T` so tests can see rewriting), `sendRedirect` target, status and character encoding (default `ISO-8859-1`); `TestHttpServletRequest` has a context path (default `/app`), a `getRequestDispatcher(path)` returning a dispatcher that writes a configured string per path (and can be told to set a status or throw).
- Produces: `@FunctionalInterface interface Body { void render(PageContext pc) throws Exception; }` and `TagHarness` with `static String run(Tag tag, TestPageContext pc, Body body)` (returns the text written to the page's `JspWriter`) and `static String run(Tag tag, TestPageContext pc, Tag parent, Body body)`. It applies the JSP classic lifecycle exactly: `setPageContext`, `setParent`, `doStartTag`; on `EVAL_BODY_BUFFERED` for a `BodyTag`: `pushBody`, `setBodyContent`, `doInitBody`; render the body, then `doAfterBody` while it returns `EVAL_BODY_AGAIN`; `popBody`; `doEndTag`; for `TryCatchFinally`: route any `Throwable` from the steps above to `doCatch` and always call `doFinally`. `Body.EMPTY` renders nothing.
- Produces: `static int Scopes.of(String scope)` — `null` → `PageContext.PAGE_SCOPE`; `"page"|"request"|"session"|"application"` → the constant; anything else → `IllegalArgumentException` (handlers rethrow it as `JspTagException`, §2.7 runtime case).

- [ ] **Step 1: Write the failing tests**
  - `TagHarnessTest` with tiny inline handlers (an `IterationTag` that repeats its body twice, a `BodyTag` that upper-cases its buffered body, a `TryCatchFinally` that records calls): assert output `"xx"`, `"HELLO"`, and that `doCatch` then `doFinally` ran when the body throws.
  - `ScopesTest`: the four names and `null` map to the constants; `"global"` throws `IllegalArgumentException`.

- [ ] **Step 2: Run them to verify they fail** — `./mvnw -ntp -pl bodoni-core test`, expected: compilation failure.

- [ ] **Step 3: Implement the doubles, `TagHarness` and `Scopes`** — unimplemented servlet methods throw `UnsupportedOperationException("not needed by Bodoni tests")`.

- [ ] **Step 4: Run them to verify they pass** — expected: PASS.

- [ ] **Step 5: Commit** — `test(core): classic tag lifecycle harness and servlet doubles`

---

### Task 3: General-purpose actions `c:out`, `c:set`, `c:remove`, `c:catch` (§4, §2.2)

**Files:**
- Create: `bodoni-core/src/main/java/io/vidocq/bodoni/core/tags/core/{OutTag,SetTag,RemoveTag,CatchTag}.java`
- Modify: `bodoni-core/src/main/java/module-info.java` (`exports io.vidocq.bodoni.core.tags.core;`)
- Test: `…/tags/core/{OutTagTest,SetTagTest,RemoveTagTest,CatchTagTest}.java`

**Interfaces:**
- Consumes: `Xml.escape`, `Scopes.of`, `TagHarness`, doubles.
- Produces: JavaBean setters named after the spec attributes (`setValue(Object)`, `setEscapeXml(boolean)`, `setDefault(Object)`, `setVar(String)`, `setScope(String)`, `setTarget(Object)`, `setProperty(String)`) — Task 6's TLD references them.

- [ ] **Step 1: Write the failing tests**
  - `OutTag`: value `"<b>"` → `&lt;b&gt;`; `escapeXml=false` → `<b>`; value `null` + default `"<none>"` → `&lt;none&gt;`; value `null`, no default, body `"fallback"` → body text (trimmed per §4.2); value `null`, no default, no body → `""`; value a `java.io.StringReader("a<b")` → `a&lt;b` (streamed); same handler reused with different value → no leak.
  - `SetTag`: `var="x" value="v"` → page attribute `x == "v"`; `scope="request"` → request attribute; `value=null` with `scope` → attribute removed from that scope only; `value=null` without `scope` → removed from all scopes; body content `"  t  "` (no value) → `"t"` (trimmed); `target` a `HashMap` + `property="k"` → `map.get("k") == "v"`, `value=null` → key removed; `target` a JavaBean (public class with `setName`) → property set through the EL resolver; `target` null → `JspTagException`; `target` a `String` → `JspTagException`; a `ValueExpression` value with `var` (syntax 5, deferred) → `pc.getELContext().getVariableMapper().resolveVariable("x")` is that expression and no scoped attribute is created.
  - `RemoveTag`: without `scope` removes from every scope; with `scope="session"` removes from the session only.
  - `CatchTag`: body throws `IllegalStateException("boom")` → page output stops at the throw point, the exception is not propagated, `var` holds it; no throw → `var` removed; no `var` → exception swallowed.

- [ ] **Step 2: Run them to verify they fail** — `./mvnw -ntp -pl bodoni-core test`, expected: compilation failure.

- [ ] **Step 3: Implement the four handlers** — `OutTag`/`SetTag` extend `jakarta.servlet.jsp.tagext.BodyTagSupport`, `RemoveTag` extends `TagSupport`, `CatchTag` extends `TagSupport implements TryCatchFinally`. Bean/Map targets go through `pageContext.getELContext().getELResolver().setValue(elContext, target, property, value)`; a `PropertyNotFoundException` or a target the resolver cannot handle becomes a `JspTagException`.

- [ ] **Step 4: Run them to verify they pass** — expected: PASS.

- [ ] **Step 5: Commit** — `feat(core): <c:out>, <c:set>, <c:remove>, <c:catch> (Tags 3.0 §4)`

---

### Task 4: Conditionals and iterators — `c:if`, `c:choose`/`c:when`/`c:otherwise`, `c:forEach`, `c:forTokens` (§5, §6)

**Files:**
- Create: `…/tags/core/{IfTag,ChooseTag,WhenTag,OtherwiseTag,ForEachTag,ForTokensTag}.java`
- Test: `…/tags/core/{IfTagTest,ChooseTagTest,ForEachTagTest,ForTokensTagTest}.java`

**Interfaces:**
- Consumes: the API base classes `jakarta.servlet.jsp.jstl.core.ConditionalTagSupport` (for `IfTag`, `WhenTag`), `LoopTagSupport` (for `ForEachTag`, `ForTokensTag` — it already implements `begin`/`end`/`step`, `var`, `varStatus` and `LoopTagStatus`; implement only `prepare()`, `hasNext()`, `next()` and the attribute setters), `IteratedExpression`, `IteratedValueExpression`, `IndexedValueExpression`.
- Produces: `IfTag.setTest(boolean)`, `WhenTag.setTest(boolean)`, `ForEachTag.setItems(Object)`, `setBegin(int)`, `setEnd(int)`, `setStep(int)`, `ForTokensTag.setItems(String)`, `setDelims(String)` (+ inherited `setVar`, `setVarStatus`).

- [ ] **Step 1: Write the failing tests**
  - `IfTag`: `test=true` renders the body; `false` does not; `var="r" scope="request"` exports `Boolean`.
  - `ChooseTag`: only the first true `when` renders; `otherwise` renders when none matched; `when`/`otherwise` whose parent is not a `ChooseTag` → `JspTagException`; a reused `ChooseTag` resets its "already taken" flag.
  - `ForEachTag`: items `List.of("a","b","c")` with body writing `${var}` (the body reads `pc.getAttribute("x")`) → `"abc"`; `int[]{1,2}` → wrapped `Integer`s; `Map` → `Map.Entry` items; `Iterator`, `Enumeration`; `String` `"a,b"` → `"a"`, `"b"` (§6.1.1, deprecated but supported); `items=null` → no iteration, no error; `begin=1 end=2` over 4 items → items 1–2; `step=2`; no `items`, `begin=1 end=3` → `1,2,3` as `Integer`; `begin=-1` → `JspTagException`; `step=0` → `JspTagException`; `end < begin` → no iteration; `varStatus` exposes `index`, `count`, `first`, `last`; after the loop `var` and `varStatus` are gone from page scope, including when the body throws (Review Focus 3); an unsupported `items` type (e.g. `Object`) → `JspTagException`; deferred `items` (a `ValueExpression` double returning a `List`) → inside the body `VariableMapper` maps `var` to an `IndexedValueExpression` (for lists/arrays) or `IteratedValueExpression` (otherwise).
  - `ForTokensTag`: `items="a|b;c" delims="|;"` → `a`, `b`, `c`; `items=null` → no iteration; `begin`/`end`/`step` apply to tokens.

- [ ] **Step 2: Run them to verify they fail** — expected: compilation failure.

- [ ] **Step 3: Implement the six handlers.** Deferred-items rule (§6.2 "Deferred Values"): when `setItems` receives a `ValueExpression`, evaluate it for iteration and, per item, map `var` in the `VariableMapper` to `new IndexedValueExpression(itemsExpression, index)` for arrays and `List`s, else to `new IteratedValueExpression(new IteratedExpression(itemsExpression, delims), index)`; restore the previous mapping at the end.

- [ ] **Step 4: Run them to verify they pass** — expected: PASS.

- [ ] **Step 5: Commit** — `feat(core): conditionals and iterators (Tags 3.0 §5, §6)`

---

### Task 5: URL actions — `c:url`, `c:param`, `c:redirect`, `c:import` (§7)

**Files:**
- Create: `bodoni-core/src/main/java/io/vidocq/bodoni/core/internal/{Urls,ExternalResources}.java`
- Create: `…/tags/core/{UrlTag,ParamTag,RedirectTag,ImportTag,ParamParent}.java`
- Modify: `module-info.java` (`requires java.net.http;`)
- Test: `…/internal/UrlsTest.java`, `…/tags/core/{UrlTagTest,ParamTagTest,RedirectTagTest,ImportTagTest}.java`

**Interfaces:**
- Produces: `static boolean Urls.isAbsolute(String url)` (§7.1: has a scheme per RFC 3986 before the first `/`, `?` or `#`); `static String Urls.resolve(String url, String context, HttpServletRequest request)` (context-relative and foreign-context rules of §7.5; `context` must start with `/`, else `JspTagException`); `static String Urls.appendParameters(String url, List<Map.Entry<String,String>> params, String encoding)` (names and values URL-encoded with the response character encoding, inserted before any `#fragment`).
- Produces: `interface ExternalResources { Reader open(String absoluteUrl, String charEncoding) throws IOException; static ExternalResources jdk(); }` — `jdk()` uses one shared `HttpClient` (`followRedirects(NORMAL)`) for `http`/`https` (non-2xx → `IOException` with the status) and `URL.openConnection()` otherwise; charset from `charEncoding`, else the response `Content-Type`, else `ISO-8859-1` (§7.4). `ImportTag` has a package-private constructor taking an `ExternalResources` for tests; the public no-arg constructor uses `jdk()`.
- Produces: package-private `interface ParamParent { void addParameter(String name, String value); }`, implemented by `UrlTag`, `RedirectTag`, `ImportTag`. `ParamTag` finds it with `findAncestorWithClass`; no such ancestor → `JspTagException`.

- [ ] **Step 1: Write the failing tests**
  - `UrlsTest`: `isAbsolute("http://x")`, `isAbsolute("mailto:a@b")` true; `"/p"`, `"p?q=a:b"` false.
  - `UrlTag`: `value="/p"` with context path `/app` → `/app/p;jsessionid=T` (rewritten); `value="p"` → `p;jsessionid=T`; `value="http://e.org/p"` → unchanged, and `encodeURL` **not called** (Review Focus 5); `context="/other" value="/p"` → `/other/p`; `context="other"` → `JspTagException`; params `a=1`, `b=é` with response encoding UTF-8 → `?a=1&b=%C3%A9`; `var` exports instead of writing; a reused instance carries no parameter from the previous use.
  - `ParamTag`: value from body when no `value` attribute; outside a `ParamParent` → `JspTagException`.
  - `RedirectTag`: calls `sendRedirect` with the `encodeRedirectURL` form of the resolved URL and `doEndTag` returns `SKIP_PAGE`.
  - `ImportTag`: relative `"/inc"` → text written by the request dispatcher double (with params added to the dispatched path); `var` exports a `String`; `varReader` exports a `Reader` available only inside the body and closed afterwards; dispatcher sets status 404 → `JspTagException` mentioning the path and status; dispatcher missing → `JspTagException` with the path; absolute URL → content from a fake `ExternalResources`; `url` null or empty → `JspTagException`.

- [ ] **Step 2: Run them to verify they fail** — expected: compilation failure.

- [ ] **Step 3: Implement `Urls`, `ExternalResources` and the five types.** Relative imports wrap the response so that `getWriter()`/`getOutputStream()` capture into a buffer decoded with `charEncoding` (or the response encoding) — §7.4 "Relative URL" rules.

- [ ] **Step 4: Run them to verify they pass** — expected: PASS.

- [ ] **Step 5: Commit** — `feat(core): URL actions (Tags 3.0 §7)`

---

### Task 6: Core TLDs — `c.tld` and `c-1_2.tld`

**Files:**
- Create: `bodoni-core/src/main/resources/META-INF/c.tld`, `bodoni-core/src/main/resources/META-INF/c-1_2.tld`
- Test: `bodoni-core/src/test/java/io/vidocq/bodoni/core/tags/core/CoreTldTest.java`

**Interfaces:**
- Consumes: `TldReader` (Task 1); every handler of Tasks 3–5.

- [ ] **Step 1: Write the failing test** `CoreTldTest`, for both files:
  - uri `jakarta.tags.core` / `http://java.sun.com/jsp/jstl/core`, short-name `c`;
  - exactly these 14 tags: `out set remove catch if choose when otherwise forEach forTokens import url redirect param`;
  - each `tag-class` exists, is public, has a public no-arg constructor, and has a public setter for every declared attribute (checked by reflection in the test);
  - attributes, `required`, `rtexprvalue` and `body-content` match the attribute tables of §4–§7 (for each tag, a `Map` literal in the test copied from the spec table — the "Dyn" column is `rtexprvalue`); `c:set value` and `c:forEach items` additionally declare `deferred-value` of type `java.lang.Object`;
  - the two files are identical apart from `<uri>`.

- [ ] **Step 2: Run it to verify it fails** — expected: FAIL (TLD missing).

- [ ] **Step 3: Write `c.tld` and copy it to `c-1_2.tld` changing only `<uri>`.** No `<validator>` (the spec's core-library validation is a translation-time concern of the container in this milestone).

- [ ] **Step 4: Run it to verify it passes** — expected: PASS.

- [ ] **Step 5: Commit** — `feat(core): core TLDs for jakarta.tags.core and the JSTL 1.2 URI`

---

### Task 7: `bodoni-it-ibarra` — real pages rendered by Ibarra

**Files:**
- Modify: `pom.xml` (profile `it-ibarra` adding `<module>bodoni-it-ibarra</module>`; versions `ibarra.version`, `peano.version`, `foy.version`, `chappe.version` = `0.4.0-SNAPSHOT`)
- Create: `bodoni-it-ibarra/pom.xml` (`maven.deploy.skip=true`, `maven.install.skip=true`)
- Create: `bodoni-it-ibarra/src/main/java/module-info.java` (`module io.vidocq.bodoni.it.ibarra { requires io.vidocq.ibarra.api; requires io.vidocq.peano.api; requires io.vidocq.bodoni.core; }`)
- Create: `bodoni-it-ibarra/src/main/webapp/{core.jsp,legacy.jsp,functions.jsp,WEB-INF/web.xml}`
- Create: `bodoni-it-ibarra/src/test/java/io/vidocq/bodoni/it/ibarra/CorePagesIT.java` and a forked host launcher under `src/test/resources/host/` modelled on Ibarra's `ibarra-tck/src/test/resources/host/host/Main.java` (same project family; copying is allowed)

**Interfaces:**
- Consumes: `ibarra-maven-plugin:precompile` (as in `ibarra/examples/precompiled-pages/pom.xml`); test scope `ibarra-foy`, `foy-core`, `peano-core`, `chappe-core` (as in `ibarra/ibarra-tck/pom.xml`).

- [ ] **Step 1: Write the pages and the failing IT**
  - `core.jsp` (`<%@ taglib prefix="c" uri="jakarta.tags.core" %>`): `c:set` a list built with `fn:split("a,b,c", ",")`, `c:forEach` with `varStatus` printing `${s.index}:${x}`, `c:if`, `c:choose`, `c:out` of `"<b>"`, `c:url value="/x"` with a `c:param`, `c:catch` around a failing `c:set target`.
  - `legacy.jsp`: the same `c:forEach` and `fn:length` under `http://java.sun.com/jsp/jstl/core` / `…/functions`.
  - `CorePagesIT`: forks the host on a free port, GETs each page, asserts exact fragments: `0:a1:b2:c`, `&lt;b&gt;`, the context path prefix in the URL, the caught exception's class name, and that `legacy.jsp` renders the same loop.
  Uses only `String`, `List`, `Map` values so that Peano's `-Dpeano.reflection=deny` passes without accessors.

- [ ] **Step 2: Run it to verify it fails**
  Run: `./mvnw -ntp -Pit-ibarra -pl bodoni-it-ibarra -am verify`
  Expected: FAIL — first on whatever Ibarra rejects; if Ibarra lacks a feature the tags need (e.g. deferred attributes, TLD discovery from a library module), stop and record it as a cross-repo item in `tasks/todo.md` and `BUG.md` rather than working around it in Bodoni.

- [ ] **Step 3: Make it pass** by fixing Bodoni-side defects only.

- [ ] **Step 4: Run it to verify it passes** — same command, expected: `BUILD SUCCESS`, `CorePagesIT` green; plus `./mvnw -ntp clean install` (without the profile) still green.

- [ ] **Step 5: Commit** — `test(it): render core and functions pages on Ibarra`

---

### Task 8: Documentation and plan status

**Files:**
- Modify: `PLAN.md` (status B1 done; open question 2 closed with the URI decision; `c:import` validated), `README.md`, `AGENTS.md` (packages now fixed; `it-ibarra` profile command), `docs/en/modules/ROOT/pages/{reference,usage,getting-started,tck}.adoc`, `tasks/todo.md`.

- [ ] **Step 1:** `reference.adoc` lists the two libraries, both URIs per library, every tag and function, the `c:import` client rule and that Bodoni ships no naming implementation. `usage.adoc` gets one page example per library. Nothing claims TCK results.
- [ ] **Step 2:** `./mvnw -ntp clean install` green.
- [ ] **Step 3: Commit** — `docs: B1 — core and functions libraries`

---

## After the last task

- Run the review agents on the branch: `java-modules-guardian` (`module-info.java`), `dependency-gatekeeper` (`pom.xml` files), `virtual-threads-reviewer` (`ExternalResources`).
- Open the pull request `feat: B1 — fn: functions and c: core tags (Vidocq/vidocq#266)`.
