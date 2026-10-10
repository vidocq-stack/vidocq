# Peano

![Peano logo](docs/en/modules/ROOT/images/peano-logo.png)

**Jakarta Expression Language 6.0** implementation for the
[Vidocq](https://codeberg.org/Vidocq/vidocq) runtime.

> Named after **Giuseppe Peano** (1858–1932), the Turin logician who gave
> mathematics a rigorous formal notation — the `∈` symbol, the Peano axioms:
> expressions written in an unambiguous notation and evaluated mechanically.

## Status

🚧 **Implementation in progress (P5)** — the lexer and recursive-descent parser build
immutable ASTs for EL 6.0 expression bodies, including navigation, calls, lambdas,
collections and operator precedence. Literal, immediate, deferred and composite
expressions are parsed with source offsets and syntax error diagnostics.
The ExpressionFactory provider now evaluates literal/operator expressions, with
scalar and array coercions. Identifiers, parse-time variable bindings, property
navigation and ValueExpression get/set/metadata now use the context's resolver
chain, including imports and static fields. Functions, resolver method calls,
MethodExpression metadata/invocation and assignment operators are implemented.
Lambda construction/invocation, lexical closures and list/set/map construction are
implemented, together with lazy chapter 2 collection pipelines and EL optional results.
Evaluation listeners and value/method expression serialization are implemented.
Serialized expressions retain parse-time bindings and use the receiving evaluation context.
The statically compiled Java Predicate adapter is available. Other functional
interfaces remain planned for build-time generation.
The official EL 6.0.1 TCK passes all 361 tests, including signatures; this result
is not a certification announcement.
Upstream API limitations and corrected engine regressions are tracked in BUG.md.
The design of record is [PLAN.md](PLAN.md) (spec scope, codegen doctrine, Java
Modules layout, integration points, TCK plan, milestones P0–P9).

## Ecosystem rules

Peano follows the transverse Vidocq philosophy (see the workspace
[AGENTS.md](https://codefloe.com/Vidocq/vidocq-workspace/src/branch/main/AGENTS.md) conventions):

- **Zero third-party dependency** — only the Jakarta Expression Language 6.0 API.
- **Strict Java Modules** — proper `module-info.java`, minimal `exports`,
  no unjustified `opens`, no classpath.
- **Static code generation first** — build-time accessor tables (APT) and
  compiled expressions (Class-File API, JEP 484) instead of runtime reflection;
  AOT-friendly (GraalVM, Leyden). The reflective path is a documented fallback.
- **TDD** — test (or TCK scenario) first, then code.
- **Virtual threads** for any I/O.
- Bugs tracked in [BUG.md](BUG.md), benchmarks in [BENCH.md](BENCH.md).

## Modules

| Module | Role | State |
|---|---|---|
| `peano-core` | Engine: parser, AST, coercions, `ExpressionFactory` provider | P4 lambdas, collection construction and chapter 2 pipelines available |
| `peano-tck` | Official EL 6.0 TCK runner (`-Ptck` profile only) | 361/361 tests pass on Temurin 25; full suite runs in CI |

Planned modules (`peano-api`, `peano-processor`, `peano-compiler`, `peano-bench`)
are described in [PLAN.md](PLAN.md) and created by the milestone that needs them.

## Build

```bash
sdk env          # Java 25 (Temurin) + Maven 3.9.16, pinned by .sdkmanrc
./mvnw -ntp install
./run-official-tck-el-6.0.sh all               # full official TCK, downloads distribution if needed
./run-official-tck-el-6.0.sh smoke             # 25 selected tests, including signatures
```

## License

Triple-licensed, at your option: **EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later**.
See [LICENSE](LICENSE) and [NOTICE](NOTICE).
