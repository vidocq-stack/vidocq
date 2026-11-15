# Jakarta Security 4.0 TCK — baseline on Kerckhoffs

A compatibility **measurement**, not a certification: partial local runs of the official TCK
sources, never a claim of conformance (AGENTS.md, "TCK").

## How it is measured

`./run-official-tck-security-4.0.sh [app-*]` (PLAN.md section 7). The TCK is built from its published
sources (`jakarta-security-tck-4.0.0.zip`, SHA-256 pinned in the script) with its own Maven build, and
its Arquillian tests run on the Kerckhoffs container of `kerckhoffs-tck` (Foy + Chappe + Vauban)
instead of the GlassFish container.

Deviations from an unmodified TCK run, all on the Maven command line, none in the TCK sources:

- the default `glassfish-ci-managed` profile is deactivated;
- the class path of `kerckhoffs-tck` is added to failsafe (`maven.test.additionalClasspath`) and Arquillian
  is pointed at our container (`arquillian.launch`, `arquillian.xml`);
- `sigtest.api.*` are set so the unbuilt signature test module can be read.

## Run of 2026-11-14

Kerckhoffs: scaffold, no `IdentityStoreHandler`, no HTTP authentication mechanism support (K0).
Foy, Chappe, Vauban: `0.4.0-SNAPSHOT` of the day.

| Application | Result | First cause |
|---|---|---|
| `app-mem` | **deployment refused**, 0 of 4 tests ran | CDI validation: `TestAuthenticationMechanism` injects `IdentityStoreHandler`, which nothing provides yet |

What this run proves, and what it does not:

- It proves the chain works: the TCK war is built from the sources, handed to the Kerckhoffs container by
  Arquillian, started with its own CDI container, and its beans are validated.
- It does not measure any Security behaviour: nothing is implemented. The first honest result is a red one.

## Run of 2026-11-15

Kerckhoffs: K1 (`IdentityStoreHandler`, in-memory identity stores, `kerckhoffs-cdi`); the container adds the
extension and its start check to the CDI container of the war, as the Vidocq runtime extension will. No HTTP
authentication mechanism support yet.

| Application | Result | First cause |
|---|---|---|
| `app-mem` | deployment accepted, **3 of 4 tests ran green, 1 red** | `testAuthenticated`: `web username: null`, the authentication mechanism of the war is never called |

What this run proves, and what it does not:

- The CDI validation error of the previous run is gone: `IdentityStoreHandler` is provided.
- The three green tests expect a caller that is *not* authenticated, so they do not yet measure the identity
  stores: no mechanism runs at all. Only `testAuthenticated` exercises the stores, and it needs K2.

## Run of 2026-11-15 (K2: HTTP mechanism through Jakarta Authentication)

Kerckhoffs: K1 plus `kerckhoffs-authentication` (Servlet Container Profile slice), `kerckhoffs-foy` and the
`HttpAuthenticationMechanism` module of `kerckhoffs-core`. Foy: the `RequestAuthentication` hook (Vidocq/foy#103).
The container installs the mechanism of the war on that hook, as the Vidocq runtime extension will.

| Application | Result | Note |
|---|---|---|
| `app-mem` | **4 of 4 green** | `testAuthenticated` now measures the in-memory identity store through the mechanism of the war |
| `app-mem-basic` | **4 of 4 green** | the built-in BASIC mechanism (`@BasicAuthenticationMechanismDefinition`, realm as a literal or an EL string literal) |
| `app-mem-form` | **4 of 4 green**; `AppMemFormImmediateIT` red | the built-in FORM mechanism with login to continue (forward or redirect, session-bound caller, logout). The red class deploys `loginPage="${applicationConfigImmediate.loginPage}"`: a CDI bean read through Jakarta Expression Language, which Kerckhoffs does not evaluate yet (K3); the deployment is refused with an explicit message |
| `app-custom` | **4 of 4 green** | a custom mechanism, a custom identity store and an injected `SecurityContext` (caller and roles of the request) |
| `app-custom-session` | **5 of 5 green** | `@AutoApplySession`: the caller is kept in the session, the mechanism is not asked again, a logout forgets it |
| `app-multiple-store` | **4 of 4 green** | several identity stores behind one handler |
| `app-mem-basic-decorate` | 1 of 2 red | a CDI decorator on the mechanism: the decorated response header is missing |
| `app-custom-identity-store-handler` | 4 of 5 red | an application `IdentityStoreHandler` does not replace the default one yet (it is a synthetic bean) |
| `app-multiple-store-backup` | deployment refused | the start check creates a request-scoped identity store while no request scope is active |
| `app-custom-authentication-mechanism-handler`, `…2` | red | `HttpAuthenticationMechanismHandler` (several mechanisms in one application) is not implemented |
| `app-securitycontext-auth` | 1 of 5 green | `SecurityContext.authenticate(...)` and `hasAccessToWebResource` are refused for now |
| `app-custom-rememberme` | 13 of 25 green | `@RememberMe` is not implemented (K3) |
| `app-mem-policy`, `app-mem-policy2` | deployment refused | needs Jakarta Authorization (JACC `PolicyFactory`), K3 |
| `app-securitycontext`, `app-securitycontext-customprincipal` | deployment refused | the application needs an EJB |

Not claimed: this is one application of the TCK. The others need an EJB, a `DataSource`, an embedded directory or an
OpenID provider, and are not run yet.

## Next

- The other applications are not run yet: they need an EJB, a `DataSource`, an embedded directory or an
  OpenID provider (PLAN.md section 7). They are added here as the platform needs are met.
- `ci.tck.command` stays undeclared until a smoke set is green.
