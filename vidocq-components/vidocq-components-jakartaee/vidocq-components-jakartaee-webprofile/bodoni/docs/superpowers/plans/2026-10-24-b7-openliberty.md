# B7: Open Liberty portability proof, implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove that the unchanged `bodoni-core` jar, bundled in a WAR, is the JSTL implementation Open Liberty runs for all five libraries. Then document what an application declares, in the Reference page's "Deploying on an application server" `WARNING` block.

**Architecture:**

- **The modules:** a new aggregator `bodoni-it-other-containers` (packaging `pom`) with one WAR module, `bodoni-it-openliberty`. They follow `champollion/main/champollion-it-other-containers/champollion-it-openliberty`: the Liberty Maven plugin creates and starts a server in `pre-integration-test`, and Failsafe runs `OpenLibertyPortabilityIT` over HTTP.
- **Telling Bodoni from Liberty:** the application has a small spy tag, `<probe:parent/>`, that prints the class of its enclosing handler. The IT asserts that this class is Bodoni's.
- **Each library is checked:** `c:`, the legacy URI, `fmt:`, `fn:`, `x:` and `sql:`. So are a translation-time validator and an application resource bundle.

**Tech Stack:** Java 25, Maven 3.9.16, `liberty-maven-plugin` 3.12.3, Open Liberty 26.0.0.10 (`io.openliberty:openliberty-webProfile11` zip), JUnit 6 (Failsafe 3.5.5), `java.net.http.HttpClient`, H2 (WAR runtime dependency of the test application).

**Spec:**
- `PLAN.md`: the "Portability is tested" paragraph, milestone row B7 and open question 5.
- The B7 spike of 2026-10-24, summarised in "Spike facts" below. Its notes were not committed.

## Spike facts (2026-10-24, Open Liberty 26.0.0.10, `pages-4.0`)

| Run | What the WAR holds, and the class loader delegation | Which JSTL runs |
|---|---|---|
| control | no Bodoni, `parentFirst` | Liberty's Apache taglibs: `org.apache.taglibs.standard.tag.rt.core.ForEachTag`; `fn:` maps to `org.apache.taglibs.standard.functions.Functions` |
| a | `bodoni-core`, JSTL API excluded, `parentFirst` | Bodoni |
| b | `bodoni-core` and the JSTL API jar (the default), `parentFirst` | Bodoni; the WAR's API copy is never loaded, Liberty's `io.openliberty.jakarta.tags.3.0` is |
| c | `parentLast`, with or without the API jar | Bodoni; with the API jar, the JVM holds a second copy of `jakarta.servlet.jsp.jstl.*` |

Evidence and other facts:

- **Which handlers run:** the spy tag reported `io.vidocq.bodoni.core.tags.core.ForEachTag`, loaded from `WEB-INF/lib/bodoni-core-*.jar`. In the generated sources (`<jspEngine keepGenerated="true"/>`), `fmt:` used Bodoni's handlers and `fn:length` mapped to `io.vidocq.bodoni.core.functions.Functions`.
- **The legacy URI:** `http://java.sun.com/jsp/jstl/core` also resolved to Bodoni.
- **The validator:** a `c:choose` with `c:when` after `c:otherwise` failed translation with HTTP 500 and Bodoni's message `c:when after the otherwise action: every when must come before it (Tags 3.0 §5.4)`. Liberty's own message, from the control run, is `Illegal "c:when" after "c:otherwise"`.
- **No switch exists:** Liberty documents no way to turn off its bundled JSTL (`jspEngine`, `pages-4.0`). Nothing needs turning off.
- **`LoopTagStatus` cannot tell them apart:** its class is the API's `LoopTagSupport$1Status` in every run.
- **The runtime zip:** the spike ran on `openliberty-jakartaee11` (137 MB). This plan uses `openliberty-webProfile11` (110 MB). Pages and JSTL are in the Web Profile, but Task 1 must check that `pages-4.0` resolves from it.

## Global Constraints

- **Code style:**
  - English everywhere: code, comments, Markdown and AsciiDoc.
  - Licence header from `etc/license-header.txt` on every `.java` file.
  - Say "Java Modules", never "JPMS".
- **Commits:** `git commit -S -s`, Conventional Commits, ending with the trailers `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01JrpAgoGhwva5krc8qVqBqh`.
- **TDD:** red, then green, then refactor.
- **Dependencies:**
  - `bodoni-core` is not touched, and no dependency is added to it.
  - The IT modules are test vehicles. They are never released: `maven.deploy.skip`, `maven.javadoc.skip`, `maven.source.skip`, `gpg.skip`, and `central-publishing-maven-plugin` `skipPublishing` in the aggregator (copy Champollion's aggregator). Add `sonar.skip=true` in the WAR module, with Champollion's comment.
  - The WAR declares exactly what the Reference block tells users to declare:
    - `io.vidocq.bodoni:bodoni-core:${project.version}`, with an `<exclusion>` of `jakarta.servlet.jsp.jstl:jakarta.servlet.jsp.jstl-api`;
    - `jakarta.servlet.jsp.jstl-api` at `provided` scope, for compiling the probe tag;
    - the Servlet and Pages APIs at `provided` scope (scope from the dependencyManagement);
    - `com.h2database:h2` at `runtime` scope, the test application's own database driver, as any application ships it;
    - `junit-jupiter` at `test` scope.

    Not `bodoni-peano`: it is a Peano adapter, and Liberty runs its own EL.
- **Server configuration:** `server.xml` enables only `pages-4.0` (it brings servlet-6.1 and expressionLanguage-6.0) and keeps the default `parentFirst` delegation. No `<classloader>` element.
- **Deployment:** deploy the packaged WAR: `<deployPackages>project</deployPackages>` and `<looseApplication>false</looseApplication>`, as in Champollion.
- **HTTP port:** a free one per build, through `build-helper-maven-plugin` `reserve-network-port`, passed as bootstrap property `http.port`. Use `httpsPort="-1"`.
- **Base URL:** Failsafe passes `bodoni.it.base=http://localhost:${liberty.http.port}/bodoni`, with `contextRoot="/bodoni"`.
- **Reactor:** the aggregator is a module of the default reactor in the root `pom.xml`, after `bodoni-peano`, as Champollion does. `./mvnw -ntp clean install` therefore starts Liberty, and so does `pr-validate`, through `mvn install`. The Maven repository is cached by `Vidocq/ci/setup-maven`, so the Liberty zip is downloaded once per cache.

## Review Focus

1. **Bundle lookup.** An application bundle in `WEB-INF/classes` must be found by `fmt:message` through Liberty's thread context class loader. Bodoni's lookup depends on it (BOD-002): the test is `fmtMessageReadsTheApplicationBundle` (Task 2).
2. **JDBC driver lookup.** H2 in `WEB-INF/lib` must be found by `sql:setDataSource` with a JDBC parameter string, through the `ServiceLoader` of the context class loader: the test is `sqlQueryRunsOnTheApplicationDriver` (Task 3).
3. **The legacy URI.** A page with `http://java.sun.com/jsp/jstl/core` must also get Bodoni and not Liberty's built-in: the test is `legacyCoreUriIsBodoni` (Task 1).
4. **The validators at real translation time.** Liberty translates on first request, so a bad page answers 500 with Bodoni's message, not Liberty's: the test is `chooseConstraintIsReportedByBodoni` (Task 4).
5. **`x:` on the server's JAXP.** `x:parse` and `x:forEach` must work with the JDK's `java.xml` inside Liberty, through Bodoni's same-origin resolver: the test is `xmlTagsAreBodoni` (Task 3).

---

### Task 1: Module scaffold, probe tag and the core library on Liberty

**Files:**
- Modify: `pom.xml` (root): add `<module>bodoni-it-other-containers</module>` after `bodoni-peano`.
- Create: `bodoni-it-other-containers/pom.xml` (aggregator, `<name>Bodoni :: IT :: Other containers</name>`, copied from Champollion's aggregator with Bodoni names).
- Create: `bodoni-it-other-containers/bodoni-it-openliberty/pom.xml` (`war`, `<name>Bodoni :: IT :: Open Liberty</name>`, `finalName` `${project.artifactId}`, server name `bodoniIt`, runtime `io.openliberty:openliberty-webProfile11:26.0.0.10` zip, with the plugin and port wiring from Champollion's WAR module).
- Create: `bodoni-it-other-containers/bodoni-it-openliberty/src/main/liberty/config/server.xml`. A comment explains why nothing is switched off and why delegation stays `parentFirst`, citing the spike facts.
- Create: `.../src/main/java/io/vidocq/bodoni/it/openliberty/ParentHandlerTag.java`, a `TagSupport`.
- Create: `.../src/main/webapp/WEB-INF/probe.tld`: URI `urn:bodoni:it:probe`, tag `parent`, body `empty`.
- Create: `.../src/main/webapp/core.jsp` and `.../src/main/webapp/core-legacy.jsp`.
- Test: `.../src/test/java/io/vidocq/bodoni/it/openliberty/OpenLibertyPortabilityIT.java`

**Interfaces:**
- Produces:
  - `ParentHandlerTag`: `doStartTag()` prints `[parent=<FQN of getParent().getClass()>]`, or `[parent=none]` when it has no parent, and returns `SKIP_BODY`.
  - Pages put `<probe:parent/>` directly inside the action under test.
  - `OpenLibertyPortabilityIT` has `private static String get(String path)`: an HTTP GET of `BASE + path` that asserts status 200 and returns the body. It also has `private static HttpResponse<String> getRaw(String path)`. Later tasks add tests to this class.

- [ ] **Step 1: Write the failing tests** in `OpenLibertyPortabilityIT`:

```java
@Test
void coreTagsAreBodoni() throws Exception {
    String body = get("/core.jsp");
    assertTrue(body.contains("[parent=io.vidocq.bodoni.core.tags.core.ForEachTag]"), body);
    assertTrue(body.contains("[parent=io.vidocq.bodoni.core.tags.core.IfTag]"), body);
    assertTrue(body.contains("ITEMS=a,b,c"), body);
}

@Test
void legacyCoreUriIsBodoni() throws Exception {
    String body = get("/core-legacy.jsp");
    assertTrue(body.contains("[parent=io.vidocq.bodoni.core.tags.core.ForEachTag]"), body);
}
```

  The pages:
  - `core.jsp` uses `jakarta.tags.core`. It has `c:forEach` over `a,b,c` with the probe inside on the first item only, a `c:if test="${true}"` holding a probe, and `ITEMS=` followed by the items joined with commas through `c:forEach`, `varStatus` and `c:if`/`c:out`.
  - `core-legacy.jsp` is the same with `http://java.sun.com/jsp/jstl/core`.

  Both are `contentType="text/plain"`.
- [ ] **Step 2: Run the IT against an empty page set** (create the poms and `server.xml` first, without the JSPs). Run `./mvnw -ntp -pl bodoni-it-other-containers/bodoni-it-openliberty -am verify`. Expected: the build reaches Failsafe, Liberty starts with `pages-4.0` from the Web Profile zip, and both tests FAIL with status 404. If `pages-4.0` does not resolve from `openliberty-webProfile11`, switch to `openliberty-jakartaee11` and record why in the pom comment.
- [ ] **Step 3: Add `ParentHandlerTag`, `probe.tld`, `core.jsp` and `core-legacy.jsp`.**
- [ ] **Step 4: Run the same command.** Expected: PASS, 2 tests.
- [ ] **Step 5: Run `./mvnw -ntp clean install`.** Expected: green, with the IT run.
- [ ] **Step 6: Commit** with the message `test(it): Open Liberty portability proof, core tags (B7)`.

### Task 2: `fmt:` and `fn:` on Liberty

**Files:**
- Create: `.../src/main/webapp/fmt.jsp` and `.../src/main/webapp/fn.jsp`.
- Create: `.../src/main/resources/io/vidocq/bodoni/it/openliberty/Messages.properties`, with `greeting=Hello from the application bundle`. It lands in `WEB-INF/classes`.
- Modify: `OpenLibertyPortabilityIT.java`.
- Modify: `server.xml`: add `<jspEngine keepGenerated="true"/>`, with a comment saying the IT reads the generated `_fn.java`.

**Interfaces:**
- Consumes: `get(String)` and `ParentHandlerTag` from Task 1.
- Produces: `private static String generatedSource(String jspName)`. It walks `System.getProperty("bodoni.it.workarea")` for a file named `_<jspName>.java` and returns its text, failing if there is none or more than one. Failsafe passes the property as `bodoni.it.workarea=${project.build.directory}/liberty/wlp/usr/servers/bodoniIt/workarea`.

- [ ] **Step 1: Write the failing tests:**

```java
@Test
void fmtTagsAreBodoni() throws Exception {
    String body = get("/fmt.jsp");
    assertTrue(body.contains("[parent=io.vidocq.bodoni.core.tags.fmt.BundleTag]"), body);
    assertTrue(body.contains("[parent=io.vidocq.bodoni.core.tags.fmt.TimeZoneTag]"), body);
}

@Test
void fmtMessageReadsTheApplicationBundle() throws Exception {
    String body = get("/fmt.jsp");
    assertTrue(body.contains("MSG=Hello from the application bundle"), body);
}

@Test
void fnFunctionsAreBodoni() throws Exception {
    assertTrue(get("/fn.jsp").contains("LEN=3"));
    String source = generatedSource("fn");
    assertTrue(source.contains("io.vidocq.bodoni.core.functions.Functions"), source);
    assertFalse(source.contains("org.apache.taglibs"), source);
}
```

  The pages:
  - `fmt.jsp` has `fmt:bundle basename="io.vidocq.bodoni.it.openliberty.Messages"` holding a probe and `MSG=<fmt:message key="greeting"/>`, and `fmt:timeZone value="UTC"` holding a probe.
  - `fn.jsp` has `LEN=${fn:length('abc')}`.
- [ ] **Step 2: Run** `./mvnw -ntp -pl bodoni-it-other-containers/bodoni-it-openliberty verify`. Expected: the three new tests FAIL with 404, and `generatedSource` fails before the page exists.
- [ ] **Step 3: Add the pages, the bundle and `keepGenerated`.**
- [ ] **Step 4: Run the same command.** Expected: PASS, 5 tests.
- [ ] **Step 5: Commit** with the message `test(it): fmt and fn tags on Open Liberty, application bundle lookup (B7)`.

### Task 3: `x:` and `sql:` on Liberty

**Files:**
- Create: `.../src/main/webapp/xml.jsp` and `.../src/main/webapp/sql.jsp`.
- Modify: `OpenLibertyPortabilityIT.java`.
- Modify: the WAR `pom.xml`: add `com.h2database:h2` with `<scope>runtime</scope>`. Its version comes from the root dependencyManagement (`${h2.version}`). The scope must be written explicitly, because the root manages H2 at `test` scope.

**Interfaces:**
- Consumes: `get(String)` from Task 1.

- [ ] **Step 1: Write the failing tests:**

```java
@Test
void xmlTagsAreBodoni() throws Exception {
    String body = get("/xml.jsp");
    assertTrue(body.contains("[parent=io.vidocq.bodoni.core.tags.xml.ForEachTag]"), body);
    assertTrue(body.contains("NAMES=ada,grace"), body);
}

@Test
void sqlQueryRunsOnTheApplicationDriver() throws Exception {
    String body = get("/sql.jsp");
    assertTrue(body.contains("[parent=io.vidocq.bodoni.core.tags.sql.TransactionTag]"), body);
    assertTrue(body.contains("ROWS=2"), body);
}
```

  The pages:
  - `xml.jsp` uses `x:parse` on an inline document `<people><p>ada</p><p>grace</p></people>`, then `x:forEach select="$doc/people/p"` with a probe on the first item. `NAMES=` lists the names joined with commas.
  - `sql.jsp` uses `sql:setDataSource var="ds" url="jdbc:h2:mem:bodoniIt;DB_CLOSE_DELAY=-1" driver="org.h2.Driver" user="sa" password=""`. A `sql:transaction dataSource="${ds}"` holds a probe, a `sql:update` creating the table `t(id int)` if it does not exist, a delete, two inserts and a `sql:query`. It prints `ROWS=${r.rowCount}`.
- [ ] **Step 2: Run** `./mvnw -ntp -pl bodoni-it-other-containers/bodoni-it-openliberty verify`. Expected: the two new tests FAIL with 404.
- [ ] **Step 3: Add the pages and the H2 dependency.**
- [ ] **Step 4: Run the same command.** Expected: PASS, 7 tests.
- [ ] **Step 5: Commit** with the message `test(it): x and sql tags on Open Liberty, application JDBC driver (B7)`.

### Task 4: Validators at real translation time

**Files:**
- Create: `.../src/main/webapp/invalid/choose-order.jsp`, a `c:choose` with `<c:otherwise/>` before `<c:when test="${true}"/>`.
- Modify: `OpenLibertyPortabilityIT.java`.

**Interfaces:**
- Consumes: `getRaw(String)` from Task 1.

- [ ] **Step 1: Write the failing test:**

```java
@Test
void chooseConstraintIsReportedByBodoni() throws Exception {
    HttpResponse<String> response = getRaw("/invalid/choose-order.jsp");
    assertEquals(500, response.statusCode(), response.body());
    assertTrue(response.body().contains("after the otherwise action"), response.body());
    assertFalse(response.body().contains("Illegal \"c:when\""), response.body());
}
```

  If Liberty does not put the translation message in the response body, read it from `messages.log`. That file is under `${project.build.directory}/liberty/wlp/usr/servers/bodoniIt/logs/`; pass its path as `bodoni.it.logs`. Record which source the test reads in a comment.
- [ ] **Step 2: Run.** Expected: FAIL with 404.
- [ ] **Step 3: Add the page.**
- [ ] **Step 4: Run.** Expected: PASS, 8 tests.
- [ ] **Step 5: Commit** with the message `test(it): Bodoni's tag library validators run at translation time on Open Liberty (B7)`.

### Task 5: Reference block, plan and documentation

**Files:**
- Modify: `docs/en/modules/ROOT/pages/reference.adoc`: a `[WARNING]` block titled `.Deploying on an application server`, at the end of `== Dependencies`. Champollion's `reference.adoc:189-195` gives the form.
- Modify: `PLAN.md`:
  - **Open question 5:** strike it through, with the answer and "decided by `bodoni-it-openliberty` (2026-10-24)".
  - **B7 row:** **Done (date)**, with the module, the eight tests and the runtime.
  - **"Portability is tested":** the outcome in one sentence.
  - **Status header:** B7 delivered.
- Modify: `AGENTS.md`:
  - **Status line:** B7 delivered.
  - **Architecture block:** `bodoni-it-other-containers/bodoni-it-openliberty` loses `(planned)`.
  - **Essential Commands:** add `./mvnw -ntp -pl bodoni-it-other-containers/bodoni-it-openliberty -am verify`.
- Modify: `README.md` (module table) and `docs/en/modules/ROOT/pages/internals.adoc` (module table): mark `bodoni-it-openliberty` as delivered (B7).

**Interfaces:**
- Consumes: the facts proven by Tasks 1–4. The block states only what a test proves, plus the spike facts this plan lists.

- [ ] **Step 1: Write the `WARNING` block.** Four bullets:
  1. **Nothing to switch off:**
     - `pages-4.0` has no switch for its bundled JSTL (Apache taglibs), and none is needed;
     - with `bodoni-core` in `WEB-INF/lib`, Bodoni handles every JSTL URI, the `jakarta.tags.*` and the legacy `http://java.sun.com/jsp/jstl/*` ones;
     - Liberty's own JSTL runs only for an application that bundles none.
  2. **Exclude the JSTL API jar** from the WAR, with the `<exclusions>` snippet below. `pages-4.0` provides it. Under the default `parentFirst` delegation the WAR's copy is never loaded, and under `parentLast` it is a second copy in the JVM.
  3. **Keep the default class loader delegation.** `parentLast` also works, but there is nothing to gain from it.
  4. **What runs where:**
     - do not add `bodoni-peano`: it is for Peano, and Liberty runs its own EL;
     - Bodoni's tag library validators run when Liberty translates a page, so a page that breaks a §2.7 constraint fails on first request with Bodoni's message;
     - `bodoni-it-openliberty` builds its WAR with exactly these dependencies.

  The snippet:

```xml
<dependency>
  <groupId>io.vidocq.bodoni</groupId>
  <artifactId>bodoni-core</artifactId>
  <version>${bodoni.version}</version>
  <exclusions>
    <exclusion>
      <groupId>jakarta.servlet.jsp.jstl</groupId>
      <artifactId>jakarta.servlet.jsp.jstl-api</artifactId>
    </exclusion>
  </exclusions>
</dependency>
```

- [ ] **Step 2: Update PLAN.md, AGENTS.md, README.md and internals.adoc** as listed in Files.
- [ ] **Step 3: Run `./mvnw -ntp clean install`.** Expected: green.
- [ ] **Step 4: Commit** with the message `docs: deploying Bodoni on Open Liberty, open question 5 settled (B7)`.
