# PLAN.md — Kerckhoffs, Jakarta Security 4.0

Design of record of the Kerckhoffs brick. Written 2026-11-14 at scaffold time. Everything marked
**(to validate)** needs the maintainer's sign-off before code; everything marked **(to verify)** is
a fact still to be checked against the spec or the TCK sources.

> Auguste Kerckhoffs (1835–1903) stated, in *La cryptographie militaire* (1883), that a system
> must stay secure even if everything about it except the key is public. Kerckhoffs applies that to
> the platform: the security machinery is open, generated at build time and inspectable; only the
> credentials stay secret.

## 1. Goal and non-goals

**Goal.** Implement **Jakarta Security 4.0** (Jakarta EE 11, final 2024-05-09), the API
`jakarta.security.enterprise:jakarta.security.enterprise-api:4.0.0` (module `jakarta.security`):

| Chapter | Content |
|---|---|
| 2. Authentication Mechanism | `HttpAuthenticationMechanism`, `HttpMessageContext`, built-in beans for `@BasicAuthenticationMechanismDefinition`, `@FormAuthenticationMechanismDefinition`, `@CustomFormAuthenticationMechanismDefinition`, `@OpenIdAuthenticationMechanismDefinition`, plus `@LoginToContinue`, `@RememberMe`, `@AutoApplySession` interceptors |
| 3. Identity Store | `IdentityStore`, `IdentityStoreHandler`, `RememberMeIdentityStore`, credentials, `PasswordHash` + `Pbkdf2PasswordHash`, built-in stores `@InMemoryIdentityStoreDefinition`, `@DatabaseIdentityStoreDefinition`, `@LdapIdentityStoreDefinition` |
| 4. Security Context | `SecurityContext` (`getCallerPrincipal`, `isCallerInRole`, `hasAccessToWebResource`, `authenticate`), `OpenIdContext`, built-in `Principal` bean |
| 1.2 | Group-to-role mapping, `CallerPrincipal`, EL support in annotation attributes (`*Expression`) |

**Non-goals.** Not a Keycloak or an identity provider. No SAML. No DIGEST, no CLIENT-CERT beyond
what Foy offers. No SecurityManager. No certification claim from a TCK run (workspace rule).

## 2. Where Kerckhoffs sits

```
application ── Jakarta Security API (jakarta.security, compile)
     │
 kerckhoffs-core ─ identity stores, mechanisms, SecurityContext     (this repository)
     │   provided APIs: Servlet 6.1, CDI 4.1, Interceptor 2.2, EL 6.0,
     │                  Authentication 3.1, Authorization 3.0, JSON-P (OpenID Connect)
     ▼
 container: Foy (Servlet, via Chappe) + Vauban (CDI)  ← Vidocq runtime extension assembles
```

Per the ecosystem rule, Kerckhoffs depends on **APIs only**. It never depends on Foy, Vauban,
Peano, Mansart or Champollion implementations: its Vidocq runtime extension (in `Vidocq/vidocq`)
picks them. The spec API reaches applications transitively (`kerckhoffs-core` → compile
`jakarta.security.enterprise-api`); its POM declares every other API `provided`.

## 3. The structuring question (decided: A', §9)

**Security 4.0 §2.5: the servlet container MUST use Jakarta Authentication to arrange for an
`HttpAuthenticationMechanism` to be placed in service**, and `hasAccessToWebResource` SHOULD end in
`jakarta.security.jacc.Policy.implies(WebResourcePermission, Subject)` when Jakarta Authorization is
present. Verified on 2026-11-14 in Foy: it ships its own Servlet security (constraint table,
BASIC and FORM, an internal `IdentityStore.validate(user, password) -> Optional<Identity>` SPI,
`InMemoryIdentityStore`), and `reference.adoc` records "Jakarta Authentication and Authorization not
implemented". So the question is **who provides the Servlet Container Profile of Authentication 3.1,
and the Authorization 3.0 policy**:

| Option | What | For | Against |
|---|---|---|---|
| **A. Two new repositories** | `Authentication 3.1` and `Authorization 3.0` as their own bricks (own CI, own TCKs) | Faithful to "one brick per spec" | Two more repositories (CI, protection, dep-graph, docs) before Kerckhoffs passes anything |
| **A'. Two modules of this repository** | `kerckhoffs-authentication` and `kerckhoffs-authorization`, each with its own `module-info`, API `provided`, own TCK runner. Precedent: Champollion carries JSON-P 2.1 and JSON-B 3.0 in one repository | No new repository; each spec keeps its own artifact, exports and TCK; real implementations, so third-party SAMs and `AuthConfigFactory` work | One release line for three specs; "Kerckhoffs" then names a repository, not only Security (said in the README and the index page) |
| **B. Foy hook only** | Foy exposes a small container-neutral SPI (validate / secure / clean around the constraint check, plus a policy `implies`); Authentication/Authorization stay API-only `provided` types | Fastest path to a working mechanism | Not a Jakarta Authentication implementation: `AuthConfigFactory` and SAM registration by third parties do not work; the TCK cases that go through them stay red |
| **C. Profile inside `kerckhoffs-core`** | The Servlet Container Profile in the Security module | Self-contained | Wrong layer: other Servlet containers cannot reuse it |

**Direction of dependencies (hard constraint).** The dep-graph counts every scope, test included. If
Foy depended on a Kerckhoffs artifact while Kerckhoffs' TCK runner and integration tests run on
Foy, the two repositories would form a cycle and `pr-validate` would break. So the arrow goes one
way only: **Foy defines the neutral hook and knows nothing of Kerckhoffs; a Kerckhoffs module
(`kerckhoffs-foy`) adapts the hook to the Authentication and Authorization modules; the Vidocq
runtime extension assembles.** Foy never takes `kerckhoffs-*` as a dependency, at any scope.

**Recommendation: A' on top of B's hook.** In K2, Foy grows the hook (B, a small companion PR) and
`kerckhoffs-foy` bridges it to a minimal `kerckhoffs-authentication` (the Servlet Container Profile
slice Security needs). K3 and later grow both modules towards their full specs, without touching
Foy again. B is the first step of A', so nothing is thrown away. The Foy change goes on the **same
branch name in Foy and Kerckhoffs** (companion branches; `docs/changes-across-repositories.md`).

## 4. Java Modules layout

| Module | Artifact | Content | Scaffold |
|---|---|---|---|
| `io.vidocq.kerckhoffs.core` | `kerckhoffs-core` | Identity store handler, built-in stores, mechanism logic, `SecurityContext`, hashing. `requires transitive jakarta.security`. No dependency on any container. | yes, empty |
| `io.vidocq.kerckhoffs.cdi` | `kerckhoffs-cdi` | The CDI wiring as a **build compatible extension**: finds `*Definition` annotations and registers the built-in beans, interceptors and `Principal` bean; carries data as parameters (never as fields) | K1 |
| `io.vidocq.kerckhoffs.authentication` | `kerckhoffs-authentication` | Jakarta Authentication 3.1: Servlet Container Profile first (`ServerAuthContext` driving an `HttpAuthenticationMechanism`), then `AuthConfigFactory` and the rest of the spec. `requires transitive jakarta.security.auth.message`, API `provided`. No dependency on Servlet containers beyond the API | K2 (minimal), K3+ |
| `io.vidocq.kerckhoffs.authorization` | `kerckhoffs-authorization` | Jakarta Authorization 3.0: `Policy`, `PolicyConfiguration`, `PolicyFactory`, web permissions; `hasAccessToWebResource` ends in `Policy.implies`. API `provided` | K3 |
| `io.vidocq.kerckhoffs.foy` | `kerckhoffs-foy` | Adapter from the neutral Foy hook (§3) to the two modules above. Depends on Foy, never the reverse | K2 |
| (tests) | `kerckhoffs-it-other-containers` | Open Liberty WAR with the exact exclusions of the Reference "Deploying on an application server" block | K6 |
| (tests) | `kerckhoffs-tck` | Runner of the official TCK, `tck` profile, unpublished | scaffolded empty |

`Vidocq/vidocq` side (separate PR, after the first SNAPSHOT is on `central-snapshots`):
`vidocq-runtime-kerckhoffs-extension` under `vidocq-runtime-extensions/*-jakartaee-web` (decided,
§9), `kerckhoffs.version`, the
`dependencyManagement` entries, the BOM and the catalog.

**Build-compatible-extension rules apply** (`vidocq-workspace/AGENTS.md`): compute from the classes
of the deployment only (no configuration value or environment variable in a phase), check the start
environment in an observer of `Startup` (for instance a client secret that must resolve, a missing
`DataSource` lookup), add annotations never remove them, reach the processor path through the
`-codegen` bundle with `addHostBean`. Test both ways: extensions run at start (TCK, Weld, Open
Liberty) and frozen (`extensionsAtStart().ranAtStart()` empty).

## 5. Ecosystem friction points

| With | Point |
|---|---|
| **Foy** | No Authentication/Authorization (§3). Foy's own `IdentityStore` SPI must be bridged or retired in favour of the Security one (migration note in Foy's `migration.adoc`). Its FORM/BASIC code is a reference, not something to duplicate. |
| **Vauban** | Interceptor bindings `@LoginToContinue`, `@RememberMe`, `@AutoApplySession`; CDI must be fully active inside the SAM call (request, session, application scopes, `BeanManager` lookup). Built-in beans are `@Default @ApplicationScoped`; `PasswordHash` is dependent-scoped. |
| **Peano** | `*Expression` attributes need Jakarta Expression Language 6.0 (`EL60` is a normative reference). Kerckhoffs depends on the `jakarta.el` API only; Peano is the Vidocq default engine. |
| **Mansart / Noether** | `@DatabaseIdentityStoreDefinition(dataSourceLookup = "java:comp/DefaultDataSource")` needs JNDI and a `DataSource`: provided by the runtime (compare Vidocq/vidocq#297 for Bodoni). |
| **Champollion** | OpenID Connect reads JSON (`jakarta.json` is `requires transitive` in the API module): JSON-P stays `provided`, the Vidocq extension brings `champollion-jsonp`. |
| **Cervantes (MP JWT)** | ID-token and JWKS validation overlap with JWT parsing. Kerckhoffs must **not** depend on Cervantes' implementation; decide in K5 whether a shared internal approach is worth a proposal upstream (to validate). |
| **Noether-validation / Erasmus** | none |

## 6. Security-engineering rules (this is a security brick)

Inherited from the Foy phase-6 decisions, restated because they are cheap now and costly later:

- Constant-time comparison of secrets (`MessageDigest.isEqual` on encoded bytes); every `char[]`
  or `Password` wiped after use; **credentials never logged**, including at DEBUG.
- Session id rotation on successful login (`changeSessionId`), with the spec-allowed opt-out
  documented; redirect targets after login come from server-side state, never from the request.
- OpenID Connect: `state` and `nonce` checked, ID-token signature, `iss`, `aud`, `exp` validated;
  secrets only via expressions, never in an annotation default; fail closed.
- `@InMemoryIdentityStoreDefinition`: warn once at start (the spec allows it) and offer an
  opt-in to refuse the start in production profiles.
- Each feature gets a negative test (wrong credential, replayed token, tampered `state`) before the
  positive one. Run the workspace `CYBER_SECU.md` checklist before every milestone close; a
  `docs-security-md` worktree already exists for that file.

## 7. TCK plan (K0)

Facts established on 2026-11-14:

- Source: `github.com/jakartaee/security`, tag **`4.0.0-RELEASE`**, directory `tck/`; a distribution
  `jakarta-security-tck-4.0.0.zip` exists (Payara certification report). Tags `5.0.0-M1`/`-M2` are
  Jakarta EE 12 work: **not** the target.
- The TCK is Arquillian-based (Arquillian 1.8.0.Final) and holds 26 `app-*` fixture modules plus
  `common`, `old-tck` and the signature test. Verified on 2026-11-14 by scanning the 258 source
  files of tag `4.0.0-RELEASE` (`tck/`, `old-tck` excluded):
  - **Plain Servlet fixtures** (no database, directory or EJB): `app-mem*`, `app-custom*` except
    `app-custom-identity-store-handler`, `app-securitycontext*` (two of them touch EJB, see below),
    `app-mem-policy*` (also use Jakarta Authorization `Policy`), `app-jaxrs` (needs Jakarta REST).
  - **`app-db`**: `@DataSourceDefinition` and EJB, an embedded database — needs a JDBC `DataSource`
    from the container (Mansart's pool, or a fake).
  - **`app-ldap`, `app-ldap2`, `app-ldap3`, `app-custom-identity-store-handler`**: LDAP; the
    sources mention an embedded directory and EJB in all four (**to verify**: which LDAP server
    library, and whether the fixture starts it or the container must).
  - **`app-openid`, `app-openid2`, `app-openid3`, `app-multiple-store-backup`**: OpenID Connect;
    `app-openid` also uses Jakarta REST (**to verify**: how the provider is faked).
  This is a keyword scan of the sources, not a deployment: every line above is a hypothesis until
  the first run of each fixture.
  - **EJB** also appears in `app-securitycontext` and `app-securitycontext-customprincipal`.
  So the first baseline (K0/K1) targets the plain Servlet group; `app-db` needs the Mansart pool
  or a test double. Fixtures that use EJB are not excluded on a guess: check them against the
  Web Profile 11 requirements first, and report them as a gap, never as "not applicable".
- **Authentication 3.1 and Authorization 3.0 have their own TCKs**, one per specification
  repository, both Arquillian-based and both under `tck/`:
  - `jakartaee/authentication`, tag **`3.1.0-RELEASE-tck`** (666 files, artifact
    `jakarta-authentication-tck`): `basic-authentication`, `custom-principal`,
    `programmatic-authentication`, `lifecycle`, `wrapping`, `register-session`,
    `async-authentication`, `status-codes`, `dispatching*`, plus `ejb-propagation`,
    `ejb-register-session` and `jacc-propagation`; and the signature test.
  - `jakartaee/authorization`, tag **`3.0.0-RELEASE`** (305 files, artifact
    `jakarta-authorization-tck`): `app-custom-policy*`, `app-custom-trace-policy*`
    (`PolicyConfiguration` and factory), `app-policy-within-servlet`, `app-servlet-constraints`,
    `app-permissions-equals-hashcode`, `app-ejb-constraints`, and the signature test.
  Neither suite needs a full platform for its Servlet parts; the EJB and JACC propagation parts
  do. Each module gets its own runner (`kerckhoffs-authentication-tck`,
  `kerckhoffs-authorization-tck`) behind `-Ptck`, added in the milestone that grows the module.
- **No TCK artifact exists on Maven Central** for any of the three: the listings under
  `jakarta/security/enterprise/`, `jakarta/authentication/` and `jakarta/authorization/` show the
  API only. The TCK poms at the release tags still carry a `-SNAPSHOT` version. Follow Bodoni: pin
  the distribution (or source tag) URL and its SHA-256 in the script and build or extract what is
  needed. The distribution assembly lives in `tck-dist/` of each repository.

How the Security TCK runs (verified on 2026-11-14 in `jakarta-security-tck-4.0.0.zip`,
SHA-256 `e21a3d8251bf954c99a3f9a5f71911696509a1ed40c23673760785a3b88e4172`, 14 MB):

- **The distribution is sources only**: no prebuilt TCK jar. Unlike the Servlet TCK (Foy installs
  three jars) or the Tags TCK (Bodoni reads one DDL), the Security TCK must be **built** from
  those sources, a third-party Maven build run by the script.
- Every `app-*` module is a WAR whose `*IT` class (`AppMemIT`, ...) is an **Arquillian JUnit 4**
  test (`org.junit.runner`, `ArquillianBase` in `common`, HtmlUnit as the HTTP client) run by
  **failsafe**; the deployment is built by ShrinkWrap from the module's own classes, so the
  fixtures of two modules share package and class names (`ee.jakarta.tck.security.test.Servlet`,
  `TestIdentityStore`, ...): they **cannot be compiled into one module**, each runs in its own
  Maven module of the TCK reactor.
- The TCK parent selects the container with profiles (`glassfish-ci-managed` active by default,
  `payara`, `tomee`, `liberty`). Kerckhoffs' container is injected without patching the TCK:
  deactivate the default profile and add the classpath of `kerckhoffs-tck` (container, Foy,
  Vauban, Kerckhoffs) to failsafe through its `additionalClasspathElements` user property.
  **Verified 2026-11-14**: failsafe 3.2.5 honours `maven.test.additionalClasspath`, so no patch of
  the TCK is needed; the script also passes `arquillian.launch` / `arquillian.xml` through `argLine`
  and sets `sigtest.api.*` (read by a module the run does not build). Every result states these
  command-line deviations (`kerckhoffs-tck/BASELINE.md`).
- The adapter is a copy of the container of `foy-tck` (decision of 2026-11-14), placed in
  `kerckhoffs-tck`, adding the start of Vauban for the CDI beans of the fixtures. It is Arquillian
  1.8.0.Final + ShrinkWrap, JUnit 4 on the TCK side.

Runner shape (copy `foy-tck` and `bodoni-tck`, not a new design):

1. `kerckhoffs-tck` in the reactor behind `-Ptck`, never published (`maven.deploy.skip`,
   `skipPublishing`), `tck-official` profile for the official dependency.
2. An Arquillian container adapter that deploys the TCK WARs on Foy + Vauban + Kerckhoffs
   (done: a trivial WAR deployed and fetched; a CDI bean in the WAR; `app-mem` run from the TCK
   sources).
3. `run-official-tck-security-4.0.sh`, which exits non-zero whenever a selected application fails,
   so that a green exit never means "nothing ran". First baseline: `kerckhoffs-tck/BASELINE.md`.
4. `kerckhoffs-tck/BASELINE.md` with pass/fail per test class; a first honest baseline before any
   implementation is a legitimate K0 deliverable.
5. Declare `ci.tck.command` in the root POM only once the baseline is green on the smoke set;
   until then pull requests run no TCK.

**Scope and state, 2026-11-16.** The TCK is run **without JSF** for now: `app-mem-customform` (the only application that
uses Jakarta Faces) and the `@LoginToContinue` expressions of `app-mem-form` are set aside, as are the EJB applications
(`app-securitycontext*`, `app-db`), the LDAP ones (K4) and OpenID (K5). Twelve applications (73 tests) are green on the Kerckhoffs
container and on the assembled runtime, `app-mem-policy*` (Jakarta Authorization) among them; still to do are the mechanism decorator
(`app-mem-basic-decorate`, blocked on `@Decorator` support in Vauban); JAX-RS (`app-jaxrs`) is green on the runtime since Cassini serves its resources as a servlet of Foy (8 of 8). Per application: the `tck` page (`docs/en`) and
`kerckhoffs-tck/BASELINE.md`.

## 8. Milestones (TDD: test or TCK scenario first, then code)

**Order, decided by the maintainer on 2026-11-18: K5 comes before K4.** The need is OAuth/OpenID Connect sign-in for forge-dashboard and a few applications; the full TCK waits for the Web Profile (K7). The rows keep their numbers and are listed in the order they are built.

| # | Scope | Exit criterion |
|---|---|---|
| **K0** | Bootstrap: repository, CI, dep-graph entry, docs component, branch protection (done at scaffold, see §10). TCK discovery: the **to verify** items of §7, Arquillian adapter, first baseline. | `./run-official-tck-security-4.0.sh` runs and reports; `BASELINE.md` committed |
| **K1** | Identity stores: `IdentityStore` validation chain and `IdentityStoreHandler` (priorities, `ValidationType`), credentials, `@InMemoryIdentityStoreDefinition`, `PasswordHash`/`Pbkdf2PasswordHash`; `kerckhoffs-cdi` extension. Pure Java, no container yet. | Spec §3 unit tests green; extension tested at start and frozen |
| **K2** | Foy hook (companion PR, Foy side only) + `kerckhoffs-foy` + minimal `kerckhoffs-authentication` (Servlet Container Profile slice). HTTP mechanisms on that path: `HttpAuthenticationMechanism`/`HttpMessageContext` contract, BASIC, FORM, custom FORM, `@LoginToContinue`, `@AutoApplySession`; built-in `Principal` bean; `SecurityContext` core methods. Authentication 3.1 TCK baseline taken here (§7). | Smoke set of the TCK green; Foy companion PR merged |
| **K3** | `kerckhoffs-authorization` (Policy, web permissions); `@RememberMe` + `RememberMeIdentityStore`; `hasAccessToWebResource` through `Policy.implies`; `kerckhoffs-authentication` grown towards the full spec; `authenticate()` programmatic path; group-to-role mapping; `*Expression` attributes via EL. | Corresponding TCK classes green |
| **K5** | OpenID Connect: provider discovery, authorization-code flow, ID-token validation, `OpenIdContext`, refresh. Uses `java.net.http` on virtual threads. | Negative tests of §6 green; TCK OpenID fixtures green |
| **K4** | `@DatabaseIdentityStoreDefinition` (JNDI `DataSource`, hashed passwords) and `@LdapIdentityStoreDefinition` (`java.naming` only, no library). | TCK `app-db-*` and LDAP fixtures green or each remaining failure filed in `BUG.md` |
| **K6** | Vidocq runtime extension (PR in `Vidocq/vidocq`); `kerckhoffs-it-other-containers` Open Liberty WAR; Reference page "Deploying on an application server" tested by that WAR. | Same unchanged jar runs under Vidocq and in the WAR |
| **K7** | Full TCK pass, docs (Antora, `tck` page), `CYBER_SECU.md` review, SonarQube clean, first release candidate. | 100 % PASS of the full run is the merge gate, as in Bodoni |

## 9. Decisions and open questions

**Decided by the maintainer on 2026-11-14:**

1. **§3: option A'**: Authentication 3.1 and Authorization 3.0 are modules of this repository, on top of a neutral Foy hook; dependencies go one way, Kerckhoffs → Foy.
2. **Extension aggregator**: `vidocq-runtime-extensions/*-jakartaee-web` (Security is a Web Profile 11 spec), next to Ibarra, Peano and Bodoni.
3. **Foy adapter**: `kerckhoffs-foy` (`io.vidocq.kerckhoffs.foy`), an engine adapter like `cassini-chappe`.
4. **Release line**: one version for Security, Authentication and Authorization (`0.x`, then one line).
5. **Jakarta Security 5.0** (Jakarta EE 12, milestones only): decided after 4.0 has a solid TCK baseline. Nothing is planned for it now.

**Still open:**

6. Does the Authentication/Authorization TCK need a full platform? Answered in K0 (§7).
7. Logo: a temporary one, generated with ChatGPT (2026-11-14); to be replaced by a designed logo.

## 10. Bootstrap checklist (state at scaffold, 2026-11-14)

Procedure: `GestionProjet/HOWTO_ADD_NEW_PROJECT.md` and the Bodoni bootstrap.

- [x] Local layout `kerckhoffs/main`, registered in `mani.yaml`, `.gitignore`, workspace docs
- [x] Root POM on `vidocq-parent`, `kerckhoffs-core`, `kerckhoffs-tck` (profile `tck`), `.sdkmanrc`, wrapper, licences
- [x] Workflows copied from Bodoni (`pr`, `ci`, `merge-bot`, `notify-docs`, `notify-slack`, `sonar`, `release`, `refresh-release-yml`, `update-dep-graph`)
- [x] `AGENTS.md`, `CLAUDE.md`, `README.md`, `BUG.md`, `BENCH.md`, `SECURITY.md`, Antora component
- [ ] Remote repository, Actions enabled, first signed commit pushed, branch protection, `update-dep-graph` dispatched
- [ ] First SNAPSHOT on `central-snapshots`
- [ ] Vidocq-docs component registration and `pages` site entry (check how Bodoni was added)
- [ ] `ci.tck.command` declared once K0 has a baseline
