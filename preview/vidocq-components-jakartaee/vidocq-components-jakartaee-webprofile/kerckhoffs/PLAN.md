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

## 3. The structuring question (to validate)

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
| `io.vidocq.kerckhoffs.foy` | `kerckhoffs-foy` (name to validate) | Adapter from the neutral Foy hook (§3) to the two modules above. Depends on Foy, never the reverse | K2 |
| (tests) | `kerckhoffs-it-other-containers` | Open Liberty WAR with the exact exclusions of the Reference "Deploying on an application server" block | K6 |
| (tests) | `kerckhoffs-tck` | Runner of the official TCK, `tck` profile, unpublished | scaffolded empty |

`Vidocq/vidocq` side (separate PR, after the first SNAPSHOT is on `central-snapshots`):
`vidocq-runtime-kerckhoffs-extension` under `vidocq-runtime-extensions/*-jakartaee-web` (or
`*-jakartaee-core`, to validate: Security is a Web Profile 11 spec), `kerckhoffs.version`, the
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
- The TCK is Arquillian-based and contains application fixtures (`app-custom-*`, `app-db-basic`,
  ...). **(to verify)** which of them need a JDBC `DataSource`, an embedded LDAP server, EJB, or
  `@DataSourceDefinition`; those decide how much of the platform Kerckhoffs' test container must
  fake.
- **Authentication 3.1 and Authorization 3.0 have their own TCKs** (separate suites from Security's). **(to verify)** where they live, how they are distributed, and whether they run without a full platform; each module gets its own runner (`kerckhoffs-authentication-tck`, `kerckhoffs-authorization-tck`) behind `-Ptck`, added in the milestone that grows the module.
- Maven Central carries only the API (`jakarta.security.enterprise-api`); the TCK artifact
  coordinates are **to verify** (Maven Central listing under `jakarta/security/enterprise/` shows
  the API only). If none exists, follow Bodoni: pin the zip URL and its SHA-256 in the script and
  extract what is needed.

Runner shape (copy `foy-tck` and `bodoni-tck`, not a new design):

1. `kerckhoffs-tck` in the reactor behind `-Ptck`, never published (`maven.deploy.skip`,
   `skipPublishing`), `tck-official` profile for the official dependency.
2. An Arquillian container adapter that deploys the TCK WARs on Foy + Vauban + Kerckhoffs.
3. `run-official-tck-security-4.0.sh` (today it **refuses with exit 2** so that a green exit never
   means "nothing ran").
4. `kerckhoffs-tck/BASELINE.md` with pass/fail per test class; a first honest baseline before any
   implementation is a legitimate K0 deliverable.
5. Declare `ci.tck.command` in the root POM only once the baseline is green on the smoke set;
   until then pull requests run no TCK.

## 8. Milestones (TDD: test or TCK scenario first, then code)

| # | Scope | Exit criterion |
|---|---|---|
| **K0** | Bootstrap: repository, CI, dep-graph entry, docs component, branch protection (done at scaffold, see §10). TCK discovery: the **to verify** items of §7, Arquillian adapter, first baseline. | `./run-official-tck-security-4.0.sh` runs and reports; `BASELINE.md` committed |
| **K1** | Identity stores: `IdentityStore` validation chain and `IdentityStoreHandler` (priorities, `ValidationType`), credentials, `@InMemoryIdentityStoreDefinition`, `PasswordHash`/`Pbkdf2PasswordHash`; `kerckhoffs-cdi` extension. Pure Java, no container yet. | Spec §3 unit tests green; extension tested at start and frozen |
| **K2** | Foy hook (companion PR, Foy side only) + `kerckhoffs-foy` + minimal `kerckhoffs-authentication` (Servlet Container Profile slice). HTTP mechanisms on that path: `HttpAuthenticationMechanism`/`HttpMessageContext` contract, BASIC, FORM, custom FORM, `@LoginToContinue`, `@AutoApplySession`; built-in `Principal` bean; `SecurityContext` core methods. Authentication 3.1 TCK baseline taken here (§7). | Smoke set of the TCK green; Foy companion PR merged |
| **K3** | `kerckhoffs-authorization` (Policy, web permissions); `@RememberMe` + `RememberMeIdentityStore`; `hasAccessToWebResource` through `Policy.implies`; `kerckhoffs-authentication` grown towards the full spec; `authenticate()` programmatic path; group-to-role mapping; `*Expression` attributes via EL. | Corresponding TCK classes green |
| **K4** | `@DatabaseIdentityStoreDefinition` (JNDI `DataSource`, hashed passwords) and `@LdapIdentityStoreDefinition` (`java.naming` only, no library). | TCK `app-db-*` and LDAP fixtures green or each remaining failure filed in `BUG.md` |
| **K5** | OpenID Connect: provider discovery, authorization-code flow, ID-token validation, `OpenIdContext`, refresh. Uses `java.net.http` on virtual threads. | Negative tests of §6 green; TCK OpenID fixtures green |
| **K6** | Vidocq runtime extension (PR in `Vidocq/vidocq`); `kerckhoffs-it-other-containers` Open Liberty WAR; Reference page "Deploying on an application server" tested by that WAR. | Same unchanged jar runs under Vidocq and in the WAR |
| **K7** | Full TCK pass, docs (Antora, `tck` page), `CYBER_SECU.md` review, SonarQube clean, first release candidate. | 100 % PASS of the full run is the merge gate, as in Bodoni |

## 9. Open questions (maintainer)

1. §3: option A' (two modules of this repository, dependencies one-way Kerckhoffs → Foy) on top of the Foy hook of B: confirm?
2. Extension aggregator: `*-jakartaee-web` or `*-jakartaee-core`?
3. Module name of the Foy adapter (`kerckhoffs-foy`)? It is an engine adapter, the documented exception to "a brick depends on APIs" (like `cassini-chappe`).
4. Is Jakarta Security 5.0 (Jakarta EE 12, milestones M1/M2 exist) the follow-up line, planned as `0.5.x`?
5. Logo: none yet (tracked in `tasks/todo.md`, like Bodoni).
6. Release line: one version for Security, Authentication and Authorization, or independent versions per module? (Default: one line, `0.x`.)
7. Does the Authentication/Authorization TCK need a full platform? Answer in K0 (§7).

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
