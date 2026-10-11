# Peano

![Peano logo](docs/en/modules/ROOT/images/peano-logo.png)

**Jakarta Expression Language 6.0** implementation for the
[Vidocq](https://codeberg.org/Vidocq/vidocq) runtime.

> Named after **Giuseppe Peano** (1858–1932), the Turin logician who gave
> mathematics a rigorous formal notation — the `∈` symbol, the Peano axioms:
> expressions written in an unambiguous notation and evaluated mechanically.

## Status

🚧 **Implementation in progress (P7)** — the lexer and recursive-descent parser build
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
Statically compiled adapters cover all 43 java.util.function interfaces plus Runnable,
Callable and Comparator. Generated application-owned functional-interface adapters
are available too. Select custom interfaces with the same
annotation or processor option and annotate them with `@FunctionalInterface`.
Application-owned accessor tables can now register through peano-api services.
The provider dispatches their direct calls inside closed application packages.
The peano-processor APT module generates property tables for `@ELAccessible` types
and explicit selected types, plus their accessible source descendants in the current
compilation, direct public method calls and generated metadata.
Method references retain raw arguments and runtime annotations without invoking the method.

Set `-Dpeano.reflection=deny` to reject Peano's reflective member fallback with a
diagnostic naming the target type. Generated tables, custom resolvers and collection
operations remain available. The default is `allow`; captured-function deserialization
requires that default because restoring its method descriptor uses reflective lookup.

Peano's named-consumer tests also run with java.desktop excluded. Optional
`peano-beans-compat` enables PropertyEditorManager discovery for factory and context
conversion; build it with `-Pbeans-compat` and install its jar on the module path.
Only this optional module requires java.desktop. Application-owned ELResolver converters
remain supported.
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
| `peano-api` | Application accessor-table service contract and selection annotation | Exports SPI and SOURCE-retained annotation packages |
| `peano-processor` | Build-time APT property generation | Annotation and explicit-type selection; no runtime dependency on processor |
| `peano-core` | Engine: parser, AST, coercions, `ExpressionFactory` provider | P6 direct accessor-table dispatch available |
| `peano-compiler` | Build-time Class-File API compilation | Scalar operator and conditional templates; dynamic resolution follows |
| `peano-tck` | Official EL 6.0 TCK runner (`-Ptck` profile only) | 361/361 tests pass on Temurin 25; full suite runs in CI |

peano-compiler now emits build-time literal/constant expressions through the Java
Class-File API, including unary minus, not/! and empty. Generated application classes
extend the API protocol base and do not require core or compiler at runtime.
Logical and/&&, or/|| and conditional ?: preserve lazy evaluation and caller conversions.
Mixed conditional results retain runtime numeric promotion and null metadata.
Scalar comparisons, concatenation and sequences are compiled. Resolver-backed expressions
remain subsequent P7 slices; peano-bench remains planned. Regenerate pre-v2 compiled
classes: corrected text-literal identity uses a new serialization emission schema.

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
