# Kerckhoffs

**Jakarta Security 4.0** for the [Vidocq](https://codefloe.com/Vidocq) ecosystem: HTTP
authentication mechanisms, identity stores and the security context of a Jakarta EE
application — zero third-party dependency, strict Java Modules, build-time generated.

> Named after Auguste Kerckhoffs (1835–1903): a system must stay secure even if everything about
> it except the key is public.

**Kerckhoffs names this repository, not only Security:** it is planned to carry Jakarta Security 4.0 and, as separate modules, the Jakarta Authentication 3.1 and Authorization 3.0 it stands on (PLAN.md §3).

**Status: scaffold.** Nothing is implemented and no TCK has run. The design, the open
decisions and the milestones are in [PLAN.md](PLAN.md).

## Modules

| Module | Role | State |
|---|---|---|
| `kerckhoffs-core` | Identity stores, mechanisms, `SecurityContext` | empty module descriptor |
| `kerckhoffs-authentication` | Jakarta Authentication 3.1 (planned, K2) | not created |
| `kerckhoffs-authorization` | Jakarta Authorization 3.0 (planned, K3) | not created |
| `kerckhoffs-tck` | Runner of the official Jakarta Security 4.0 TCK (`-Ptck`, never published) | empty |

## Prerequisites

Java 25 (Temurin) and Maven 3.9.16, pinned in `.sdkmanrc`:

```bash
sdk env
./mvnw -ntp install -DskipTests
./mvnw test
```

## Where it fits

Kerckhoffs depends on the Jakarta APIs only. Under Vidocq, the runtime extension assembles it
with Foy (Servlet), Vauban (CDI) and Peano (Expression Language); it is designed to run
unchanged on another container or in a WAR. See [PLAN.md](PLAN.md) §2 and §3.

## Contributing

Signed commits, DCO sign-off, pull requests: see [CONTRIBUTING.md](CONTRIBUTING.md). Report
vulnerabilities as described in [SECURITY.md](SECURITY.md), never in a public issue.

## Licence

`EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later` — see [LICENSE](LICENSE) and [NOTICE](NOTICE).
