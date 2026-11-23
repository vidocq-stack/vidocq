# BUG.md — Kerckhoffs

Reproducible bugs (internal issue, regression, incorrect behavior not yet fixed).
Entry format: `short id · date · symptom · minimal repro · cause hypothesis · status`.

## BUG-20261123-62 · 2026-11-23 · An in-memory identity store starts without a word

- **Symptom:** an application with `@InMemoryIdentityStoreDefinition` starts silently, though PLAN.md §6 asks for a warning at start so that callers and passwords written in the code do not reach production unnoticed.
- **Minimal repro:** `InMemoryStoreWarningTest#the_start_warns_once_of_each_in_memory_store_and_names_its_declaration`: a container with `MemoryApplication` logs no `WARNING` (`expected: <1> but was: <0>`).
- **Cause:** `InMemoryIdentityStoreCreator` created the store and logged nothing.
- **Status:** FIXED (pending commit): the creator logs one `WARNING` on `io.vidocq.kerckhoffs.identitystore`, naming the declaring class. The store is a singleton the start check creates, so the warning comes once per definition, at start. No message id: the `[VIDOCQ-…]` codes are the runtime core's startup anomalies (`StartupAnomalies`, which nothing else logs), and no brick has codes of its own. The opt-in to refuse the start in production (PLAN.md §6) is not done.

## BUG-20261123-61 · 2026-11-23 · `@CustomFormAuthenticationMechanismDefinition` is silently ignored

- **Symptom:** an application that declares the custom FORM mechanism gets no `HttpAuthenticationMechanism` and no deployment error: its protected pages are served as with no mechanism at all. The Javadoc of `KerckhoffsServletAuthentication` (runtime extension) said Kerckhoffs registers one.
- **Minimal repro:** `MechanismCdiTest#aCustomFormDefinitionPutsTheCustomFormMechanismInService` (`CustomFormApplication`): `UnsatisfiedResolution No bean found for type: HttpAuthenticationMechanism`.
- **Cause:** `KerckhoffsExtension` collected the BASIC and FORM definitions only.
- **Status:** FIXED (pending commit): implemented (PLAN.md K2 lists the custom FORM mechanism). `FormAuthenticationMechanism.customForm` keeps the login to continue of FORM, takes the credential the application hands to `SecurityContext.authenticate` instead of `j_security_check`, and answers `SEND_FAILURE`, `SUCCESS` or `SEND_CONTINUE` with a redirect to the page asked for (`CustomFormAuthenticationMechanismTest`, 8 tests). The TCK application `app-mem-customform` stays out: its login page is a Jakarta Faces page.

## BUG-20261123-60 · 2026-11-23 · `@Inject Pbkdf2PasswordHash` does not resolve

- **Symptom:** an application that injects the built-in `Pbkdf2PasswordHash` fails to start: no bean has that type, though `DefaultPbkdf2PasswordHash` exists in `kerckhoffs-core`.
- **Minimal repro:** `PasswordHashCdiTest` (3 tests): `UnsatisfiedResolution No bean found for type: jakarta.security.enterprise.identitystore.Pbkdf2PasswordHash`.
- **Cause:** nothing registered `DefaultPbkdf2PasswordHash` as a bean.
- **Status:** FIXED (pending commit): `KerckhoffsExtension` adds a `@Dependent` synthetic bean of type `Pbkdf2PasswordHash` (`Pbkdf2PasswordHashCreator`) for every application, so each injection point configures its own through `initialize(Map)`. Tested at start, frozen (`FrozenExtensionTest`) and on the assembled runtime (`KerckhoffsSecurityTest#theApplicationInjectsTheBuiltInPasswordHash`).

## BUG-20261123-57 · 2026-11-23 · The TCK scripts install the branch into the shared `~/.m2`

- **Symptom:** `run-official-tck-security-4.0.sh` ran `./mvnw -ntp install -DskipTests`, the class path build and the TCK's own build on the default local repository, `~/.m2/repository`, which every build and session of the machine shares: a run from a branch replaced the published Kerckhoffs snapshot there for every other build. The runner of the assembled runtime (`vidocq-runtime-tck-kerckhoffs-security/run-official-tck-security-4.0.sh`) did the same with `VIDOCQ_TCK_INSTALL=1`. Both also failed under macOS's Bash 3.2 without `MVN_EXTRA` or `--` arguments (an empty array is unbound under `set -u`). Same bug in Foy, Peano, Ibarra, Bodoni, Cassini, Champollion, Mansart and Noether.
- **Minimal repro:** `OfficialTckScriptTest` (`kerckhoffs-tck`, `-Ptck`): the script, copied next to a `mvnw` that logs its arguments, on a stand-in distribution, made its three Maven calls without `-Dmaven.repo.local`. The runtime runner has no test (its module holds none).
- **Cause:** the scripts predate the workspace rule "never install into `~/.m2` from a branch build".
- **Status:** FIXED (pending commit). Every Maven call takes `-Dmaven.repo.local=<folder>/.tck-repository -Dmaven.repo.local.tail=$HOME/.m2/repository` (`TCK_LOCAL_REPOSITORY` moves the first), the Kerckhoffs folder for the brick's script and `vidocq-runtime/` for the runtime's; the empty arrays expand as `${a[@]+"${a[@]}"}`. `.tck-repository/` is git-ignored.

## KERCK-20261118-03 · 2026-11-18 · A BASIC mechanism's qualifiers differ from Soteria's

- **Symptom:** `KerckhoffsExtension.qualify` gives a `@BasicAuthenticationMechanismDefinition` other qualifiers than Soteria 4.0.2, the reference implementation, in two cases. (1) With `qualifiers` left to its default `{BasicAuthenticationMechanism.class}`, or written out the same, the mechanism is `@BasicAuthenticationMechanism` and `@Default`; on Soteria a mechanism with a non-empty `qualifiers` has those qualifiers alone, without `@Default`. (2) With an explicit `qualifiers = {}`, the mechanism is `@BasicAuthenticationMechanism` and `@Default`; on Soteria it has no `@BasicAuthenticationMechanism`, so it is `@Default` alone.
- **Minimal repro:** `MechanismCdiTest#aBasicDefinitionPutsTheBasicMechanismInService` and `#aBasicDefinitionThatWritesTheDefaultQualifiersStaysTheDefaultMechanism` select the mechanism by `@Default`, which Soteria would not find; a definition with `qualifiers = {}` resolves with `@BasicAuthenticationMechanism`, which Soteria would not.
- **Cause:** `qualify` treats a `qualifiers` that names nothing, or only the declared default, as "no qualifiers", gives the fallback qualifier and adds `@Default` so that an application without a handler of its own injects its one mechanism. Jakarta Security 4.0, section 2.4, asks for the qualifiers of the attribute only.
- **Status:** OPEN, behaviour kept (ruling of 2026-11-18: the change belongs to the brick's owner, and breaks the brick's own tests and its users). Named as a deviation in `reference.adoc`. A portable application selects it with `@BasicAuthenticationMechanism` when `qualifiers` is left to its default, with `@Default` when it writes `qualifiers = {}`, or lets the default handler find it (section 2.2.1).

## KERCK-20261118-02 · 2026-11-18 · The TCK container lacks the Authorization and Authentication APIs

- **Symptom:** `./mvnw -Ptck test -pl kerckhoffs-tck`: 3 of the 4 `KerckhoffsDeployableContainerTest` tests fail with `NoClassDefFound jakarta/security/jacc/WebResourcePermission`, whatever the Vauban. Once the JACC API is there, a war that declares an HTTP mechanism fails with `NoClassDefFound jakarta/security/auth/message/AuthException`.
- **Minimal repro:** `./mvnw -Ptck test -pl kerckhoffs-tck -am` on 41ac3fd (`stack-l1b-kerckhoffs-full.log` against the Vauban lane, `stack-l1b-kerckhoffs-mainvauban.log` against today's Vauban); the new `a_war_declaring_the_basic_mechanism_is_authenticated_by_it` for the Authentication API.
- **Cause:** the parent manages `jakarta.authorization-api` and `jakarta.authentication-api` as `provided`, as platform APIs, and a `provided` dependency is not transitive: `kerckhoffs-foy` uses both (`PolicyAccessControl` since 5b11805), and `kerckhoffs-tck`, the container, never brought them. The CI did not see it: the module is built under `-Ptck` only, and `ci.tck.command` (`run-official-tck-security-4.0.sh`) runs `test-compile` on it, never its tests; the official TCK applications bring the platform APIs themselves.
- **Status:** fixed 2026-11-18: `kerckhoffs-tck` declares both APIs at `test` scope (versions from the parent), and a test deploys a war with `@BasicAuthenticationMechanismDefinition`, which goes through `KerckhoffsDeployableContainer.mechanismOf` (`cdi.select(HttpAuthenticationMechanism.class)`). 5 of 5 against the Vauban lane (`stack-l1b-kerckhoffs-tck.log`, "GREEN (2)"); without KERCK-20261118-01 that test gets 401 where it expects 200 ("CONTROL (2)").

## KERCK-20261118-01 · 2026-11-18 · A BASIC definition loses `@Default` when the language model shows its default `qualifiers`

- **Symptom:** on Vauban with BUG-20261117-55 (the language model shows an annotation's default members), a `@BasicAuthenticationMechanismDefinition` that writes no `qualifiers` gives a mechanism without `@Default`: `select(HttpAuthenticationMechanism.class)` finds nothing (`MechanismCdiTest:60`, `FrozenExtensionTest:160`, `KerckhoffsDeployableContainer:257`). On today's Vauban, a definition that writes `qualifiers = BasicAuthenticationMechanism.class` out loses it the same way.
- **Minimal repro:** `MechanismCdiTest#aBasicDefinitionThatWritesTheDefaultQualifiersStaysTheDefaultMechanism` (`ExplicitDefaultQualifierApplication`), and the two tests above, red against the Vauban lane `stack/l1-batch-b` (acc49e64) in a private repository (`stack-l1b-kerckhoffs-red.log`).
- **Cause:** `KerckhoffsExtension.qualify` made the mechanism `@Default` only when `hasMember("qualifiers")` was false. `qualifiers()` defaults to `{BasicAuthenticationMechanism.class}` (security-api 4.0.0), which the language model now shows.
- **Spec:** Jakarta Security 4.0, section 2.4: the built-in beans "MUST have the qualifiers as set by their qualifiers attribute; either the default (as it appears in the API definition) or those as explicitly set". The default gives `@BasicAuthenticationMechanism`, as Kerckhoffs does; Kerckhoffs adds `@Default` on top, which the spec does not ask (a CDI bean with a qualifier other than `@Named` and `@Any` is not `@Default`), so that an application without a handler of its own injects its one mechanism. The default handler looks the mechanisms up "irrespective of any qualifiers" (section 2.2.1). That addition is kept as it is.
- **Status:** fixed 2026-11-18: a `qualifiers` that names the declared default alone is treated as an absent one, so the mechanism keeps `@BasicAuthenticationMechanism` and `@Default` on both Vaubans (`stack-l1b-kerckhoffs-green.log`; `stack-l1b-kerckhoffs-mainvauban.log` against today's Vauban).

## KERCK-20261115-01 · 2026-11-15 · `getAuthType()` answers `HTTP`, not `BASIC`

- **Symptom:** in a servlet protected by the BASIC mechanism, `HttpServletRequest.getAuthType()` returns `HTTP`; Servlet 6.1 expects `BASIC` (`HttpServletRequest.BASIC_AUTH`) for HTTP Basic.
- **Minimal repro:** Vidocq `vidocq-runtime-it-kerckhoffs-security`, `GET /whoami` with a valid `Authorization: Basic …`; assert `getAuthType()` equals `"BASIC"` (the assertion was removed from the IT, Vidocq#603).
- **Cause:** no built-in mechanism named its type in the message map, so `MechanismRequestAuthentication` (`kerckhoffs-foy`) fell back to its default `HTTP`.
- **Status:** fixed 2026-11-15: `HttpAuthenticationMechanismModule` publishes `BASIC` / `FORM` (`jakarta.servlet.http.authType`) after a successful built-in mechanism; a custom mechanism keeps the `HTTP` default. Vidocq's IT can assert `BASIC` again once this SNAPSHOT is deployed.

## KERCK-20261116-01 · 2026-11-16 · `@Decorator` of an `HttpAuthenticationMechanism` is not applied

- **Symptom:** official Security TCK `app-mem-basic-decorate` passes 1 of 2 tests: the decorator that must wrap the mechanism never runs.
- **Minimal repro:** `run-official-tck-security-4.0.sh app-mem-basic-decorate` (container or assembled runtime).
- **Cause:** Vauban (CDI Lite) applies no `@Decorator`, and the mechanism is a synthetic bean, so a decorator has nothing to attach to.
- **Status:** open, upstream (Vauban). A build-time decorator support is being assessed with the Vauban session.

## KERCK-20261116-02 · 2026-11-16 · REST endpoints are not protected by the Servlet authentication hook

- **Symptom:** official Security TCK `app-jaxrs` passes 4 of 8: requests to the REST resources bypass the authentication and authorization of Foy.
- **Minimal repro:** `run-official-tck-security-4.0.sh app-jaxrs`.
- **Cause:** Cassini mounts on Chappe outside Foy, so neither the context path nor the authentication hook sees its requests; the runtime security runner also has no REST extension.
- **Status:** fixed 2026-11-17. Cassini serves the REST resources as a servlet of Foy (`cassini-servlet`, Vidocq/cassini#148 and #149), the Vidocq REST extension picks that mode when Foy is there (`vidocq.rest.mode`, Vidocq/vidocq#634), and the runtime security runner carries both: `app-jaxrs` passes 8 of 8.
