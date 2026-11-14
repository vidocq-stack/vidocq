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

## Next

- K1 (identity stores, `kerckhoffs-cdi`) provides `IdentityStoreHandler`: the `app-mem` deployment is the first
  scenario to turn.
- Hosting the authentication mechanism needs the installation through Jakarta Authentication (spec section 2.5),
  which Foy does not offer yet (PLAN.md section 3).
- The other applications are not run yet: they need an EJB, a `DataSource`, an embedded directory or an
  OpenID provider (PLAN.md section 7). They are added here as the platform needs are met.
- `ci.tck.command` stays undeclared until a smoke set is green.
