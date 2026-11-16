# Jakarta Security 4.0 TCK — baseline on Kerckhoffs

A compatibility **measurement**, not a certification: partial local runs of the official TCK
sources, never a claim of conformance (AGENTS.md, "TCK").

The figures "on the assembled runtime" come from the runner of the Vidocq repository run locally on 2026-11-17: Vidocq commit `e0d61cdd`
(Vidocq/vidocq#634, `23331bda` on `main`), Kerckhoffs commit `3fe80a6`.

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
| `app-multiple-store-backup` | **6 of 6 green** | request-scoped identity stores, several stores with a backup |
| `app-custom-identity-store-handler` | 4 of 5 green | an application `IdentityStoreHandler` replaces the default one; the red test reads groups from an embedded LDAP directory, not available yet |
| `app-custom-authentication-mechanism-handler` | **4 of 4 green** | the `HttpAuthenticationMechanismHandler` of the application serves the requests (`HandlerMechanism`); it is no `HttpAuthenticationMechanism` itself |
| `app-custom-authentication-mechanism-handler2` | **3 of 3 green** | repeated `@BasicAuthenticationMechanismDefinition` with `qualifiers`, injected by qualifier into the handler |
| `app-securitycontext-auth` | **5 of 5 green** | `SecurityContext.authenticate(...)`: the mechanism reads the parameters, the status is SUCCESS, SEND_CONTINUE or SEND_FAILURE |
| `app-custom-rememberme` | **25 of 25 green** | `@RememberMe` (`RememberMeInterceptor`, `RememberMeSupport`): the cookie, its flags, the `*Expression` attributes through Jakarta Expression Language (Peano, test scope), and the logout that reaches the mechanism without a response |
| `app-mem-policy` | **4 of 4 green** | Jakarta Authorization: `kerckhoffs-authorization` (`ConstraintPolicyFactory`, `ConstraintPolicy`) and `PolicyAccessControl` on Foy's `AccessControl` hook (Foy#113); on the assembled runtime the wiring is still to do |
| `app-mem-policy2` | **5 of 5 green** | the same plus the `PrincipalMapper` of the policy context (`SubjectPrincipalMapper`) |
| `app-securitycontext`, `app-securitycontext-customprincipal` | deployment refused | the application needs an EJB |

Not claimed: this is one application of the TCK. The others need an EJB, a `DataSource`, an embedded directory or an
OpenID provider, and are not run yet.

## Next

Scope decision (2026-11-16): for now Kerckhoffs stays on the Jakarta Security 4.0 TCK **without JSF**. Per application,
on this container and on the assembled runtime, see the `tck` page of the documentation (`docs/en`).

- Out of scope for now: `app-mem-customform` (the only application that uses Jakarta Faces), `AppMemFormImmediateIT` of
  `app-mem-form` (`@LoginToContinue` with `*Expression` attributes, no JSF involved), the EJB applications
  (`app-securitycontext`, `app-securitycontext-customprincipal`, `app-db`), the LDAP ones (`app-ldap*`,
  `app-custom-identity-store-handler`, K4) and OpenID (`app-openid*`, K5).
- Still to do: Jakarta Authorization on the assembled runtime (green on this container), the mechanism decorator
  (`app-mem-basic-decorate`, 1 of 2: Vauban applies no `@Decorator`, and the mechanism is a synthetic bean). JAX-RS (`app-jaxrs`) is green on the assembled runtime, 8 of 8, since Cassini serves its resources as a servlet of Foy (KERCK-20261116-02, fixed); the Kerckhoffs container has no Jakarta REST and does not run it.
- `ci.tck.command` is declared (2026-11-15) on the smoke set: `app-mem`, `app-mem-basic`, `app-custom`, `app-custom-session`,
  `app-multiple-store`, `app-multiple-store-backup`, `app-securitycontext-auth`, `app-custom-authentication-mechanism-handler`, `…handler2`, `app-custom-rememberme`, `app-mem-policy`, `app-mem-policy2`. A red application leaves the smoke set
  until it is green again.
