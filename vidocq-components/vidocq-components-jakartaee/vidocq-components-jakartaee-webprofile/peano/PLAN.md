# Peano — Design plan

> Status: **in design**. This document is the design of record for Peano, the
> Jakarta Expression Language 6.0 implementation of the Vidocq ecosystem. Nothing
> below is implemented yet beyond the scaffold (milestone P0). Decisions marked
> **(to validate)** need the maintainer's sign-off before the milestone that
> depends on them starts.

## Vision

Peano evaluates Jakarta Expression Language 6.0 expressions — `${...}` and
`#{...}` — with the Vidocq guarantees:

- **zero third-party dependency** at run time: `jakarta.el` (the spec API) only;
- **strict Java Modules**: one `module-info.java` per artefact, nothing exported
  that is not an API, no `opens`;
- **static code generation first**: property accessors and, where the expression
  is known at build time, the expression itself are generated at build time
  (APT + Class-File API, JEP 484) instead of being discovered by reflection at
  run time;
- **usable standalone** (`ELProcessor` in a plain Java SE program) as well as
  embedded by other bricks (Ibarra for Jakarta Pages, a future `vauban-el`
  module for CDI, possibly Erasmus for message interpolation).

The name: Giuseppe Peano (1858–1932), the Turin logician who gave mathematics a
rigorous formal notation (the `∈` symbol, the Peano axioms) — expressions
written in an unambiguous notation and evaluated mechanically.

```
                       user code / Ibarra / vauban-el (future)
                                      │  jakarta.el API only
                                      ▼
        ┌───────────────────────────────────────────────────────────┐
        │ peano-core   (module io.vidocq.peano.core)                │
        │  provides jakarta.el.ExpressionFactory                    │
        │  lexer → parser → AST → interpreter (reference oracle)    │
        │  coercions, ELContext glue, stream resolver, lambdas      │
        │  GeneratedAccessorELResolver ◄── generated accessors      │
        └───────────────▲───────────────────────────▲───────────────┘
                        │ peano-api (generated-code SPI)
        build time ─────┼───────────────────────────┼──────────────────
        ┌───────────────┴─────────────┐  ┌──────────┴─────────────────┐
        │ peano-processor (APT)       │  │ peano-compiler             │
        │  accessor tables per type   │  │  AST → Class-File API      │
        │                             │  │  (used by Ibarra's plugin) │
        └─────────────────────────────┘  └────────────────────────────┘
```

## Specification scope (Jakarta Expression Language 6.0)

In scope — everything the 6.0 specification and its TCK require:

| Area | Content |
|---|---|
| Syntax | Full 6.0 grammar: `${}` immediate and `#{}` deferred, composite expressions, literals (incl. `{}` set / `[]` list / `{k:v}` map construction), all operators (arithmetic, relational, logical, `empty`, conditional `?:`, string concatenation `+=`, assignment `=`, semicolon), operator precedence, reserved words, escaping |
| Parser / AST | Hand-written lexer and recursive-descent parser (no parser generator — zero dependency, and a generator would be a build dependency whose output we would then have to own); immutable AST nodes (records + sealed interfaces); precise error positions in `ELException` messages |
| Coercions | Chapter 1.23 rules: to String, Number (incl. `BigInteger`/`BigDecimal` widening), Character, Boolean, enum, any `Object`, functional interfaces (lambda coercion), `null` handling; `ExpressionFactory.coerceToType` |
| Resolution | `ELResolver` chain as built by `StandardELContext` / `ELManager`: `BeanNameELResolver`, custom resolvers, `StaticFieldELResolver`, `MapELResolver`, `ResourceBundleELResolver`, `ListELResolver`, `ArrayELResolver` (incl. `length`), `RecordELResolver`, `OptionalELResolver`, `BeanELResolver`, plus the implementation-provided stream resolver (`ExpressionFactory.getStreamELResolver()`). The standard resolvers are concrete classes of the API jar; Peano supplies the engine around them and one extra, generated-accessor resolver (see "Codegen doctrine") |
| Static members | `ImportHandler` (class / package / static imports), `ELClass`, static field and static method references, constructor calls via `ELClass` |
| Functions | `FunctionMapper`, `ELProcessor.defineFunction`, `ELManager.mapFunction`, default-package and namespace-prefixed functions |
| Variables | `VariableMapper`, `ELProcessor.setVariable`, lambda parameters shadowing |
| Lambdas | `x -> expr`, multi-parameter, nested, immediately invoked, assigned to variables, coercion to functional interfaces |
| Collection operations | Chapter 2 stream API: `stream()`, `filter`, `map`, `flatMap`, `distinct`, `sorted`, `forEach`, `peek`, `limit`, `substream`, `toArray`, `toList`, `reduce`, `max`, `min`, `average`, `sum`, `count`, `anyMatch`, `allMatch`, `noneMatch`, `findFirst`, plus the `Optional` type of chapter 2 |
| Expressions API | `ValueExpression` (get/set/isReadOnly/getType/getValueReference), `MethodExpression` (with and without parameters, `getMethodInfo`, `getMethodReference`, `isParametersProvided`), `ValueReference`, `MethodReference`, `MethodInfo`; serializability of expressions |
| Standalone | `ELProcessor`, `ELManager`, `StandardELContext`, `ExpressionFactory.newInstance()` through `ServiceLoader` (`provides jakarta.el.ExpressionFactory`) |
| Events | `EvaluationListener` callbacks (before/after evaluation, property resolved) |

Out of scope: anything Jakarta Faces or Jakarta Pages specific (implicit
objects, scoped attributes — those resolvers belong to Ibarra), and EL 6.1+
features until a 6.1 TCK exists.

**To verify against the 6.0 specification text before P2 starts:** the exact
order of the standard resolvers in `StandardELContext`, the list of 6.0 API
removals (notably `ELResolver.getFeatureDescriptors`), and whether
`jakarta.el`'s `BeanELResolver` works without `java.desktop` (the API module
declares `requires static transitive java.desktop`, see `jar --describe-module`
on `jakarta.el-api-6.0.1.jar`). Peano itself must not require `java.desktop`.

## Codegen doctrine applied

The workspace rule is "generate at build time what others reflect at run time".
For Expression Language this means two separate things.

### 1. Property and method access — build-time accessors

The API's `BeanELResolver` reflects (`Method.invoke`) and, in a Java Modules
world, it cannot reach a public getter declared in a **non-exported** package of
an application module at all. Peano adds a resolver that does not reflect:

- **`peano-processor`** (APT, runs at `compile` in the user's build) emits, for
  each selected type, an accessor table class in the *user's own package*:
  property names → direct `getX()` / `isX()` / `setX()` invocations, method
  names + arities → direct calls, record components → accessor calls. Because the
  generated class lives in the user's module, it needs no export and no `opens`.
- The table registers through `ServiceLoader`: the user module declares
  `provides io.vidocq.peano.spi.AccessorProvider with ...` (generated
  `module-info` fragment documented, like Vauban's generated factories).
- **`GeneratedAccessorELResolver`** (in `peano-core`) consults the tables and is
  placed *before* `BeanELResolver` in every chain Peano builds (`ELProcessor`,
  `ELManager`); Ibarra places it in the Jakarta Pages chain the same way. When it
  resolves a property it calls `ELContext.setPropertyResolved(true)`; when it
  does not know the type, it leaves resolution to the next resolver — semantics
  stay exactly those of the spec chain, custom resolvers keep their precedence.
- **Type selection (to validate):** (a) types annotated with a Peano annotation
  in `peano-api`; (b) types referenced by a typed declaration the build already
  knows (Jakarta Pages `jsp:useBean`, tag attribute types, CDI `@Named` beans
  found by Vauban's indexer); (c) a Maven-plugin scan of compiled classes, as in
  `erasmus-codegen-maven-plugin`, for types the user cannot annotate. Recommended
  start: (a) + (b); (c) on demand.

**Degraded reflective path (documented, not hidden):** a type with no generated
table falls through to the API's `BeanELResolver` (reflection, exported public
members only). A system property `peano.reflection=deny` (name to validate)
turns that fallback into an `ELException` naming the type, so an application can
prove at test time that it runs reflection-free — the same "eliminate runtime
fallbacks" approach Vauban follows.

### 2. Expression evaluation — interpreter first, compiled when known

- The **tree-walking interpreter** is the reference implementation and the
  differential-testing oracle. It handles every expression created at run time
  (`ELProcessor.eval("...")`, `createValueExpression` with a dynamic string).
- **`peano-compiler`** turns an AST into a class emitted with the **Class-File
  API** (JEP 484): one class per expression (or per page in Ibarra's case) that
  implements the evaluation directly — literal folding, arithmetic with the
  coercion rules inlined, resolver calls left in place (resolution stays dynamic,
  as the spec requires). It runs **at build time** only, called by
  `ibarra-maven-plugin` for expressions found in pages, and by a future goal for
  expressions found in annotations or resources.
- **No bytecode is generated at run time by default.** Runtime compilation into
  hidden classes (`Lookup.defineHiddenClass`) is technically possible and is an
  **open question**: it would speed up hot dynamic expressions but breaks the
  "no runtime bytecode" line Vauban holds and complicates AOT.
- Every compiled expression is tested against the interpreter on the same inputs
  (differential testing), so the two paths cannot drift.

## Java Modules layout

Implementation note (2026-10-10): runtime-typed array conversion (section 1.25.7)
and the `empty` array case (section 1.11) use `java.lang.reflect.Array` for
allocation, element access and length only. Array component types are supplied
at runtime, so a single statically typed allocation cannot cover arbitrary
reference and primitive array targets. This narrow exception requires only
`java.base` and performs no member lookup or accessibility override.

P1 implements scalar and array coercions. P2 routes operand conversion through
the evaluation context; functional-interface adaptation belongs with lambdas in P4. PropertyEditor
conversion remains a design question because Peano forbids `java.desktop` and
`java.beans` introspection. Unsupported conversions raise ELException.

| Module (artefact) | Java module | Kind | Requires | Exports |
|---|---|---|---|---|
| `peano-api` | `io.vidocq.peano.api` | runtime | `jakarta.el` | `io.vidocq.peano.spi` (generated-code SPI: `AccessorProvider`, accessor table contract), the opt-in annotation |
| `peano-core` | `io.vidocq.peano.core` | runtime | `transitive jakarta.el`, `io.vidocq.peano.api` | nothing — `provides jakarta.el.ExpressionFactory`, `uses io.vidocq.peano.spi.AccessorProvider` |
| `peano-processor` | `io.vidocq.peano.processor` | build | `java.compiler`, `io.vidocq.peano.api` | nothing — `provides javax.annotation.processing.Processor` |
| `peano-compiler` | `io.vidocq.peano.compiler` | build | `io.vidocq.peano.core` (parser/AST via a qualified export `to io.vidocq.peano.compiler`) | `io.vidocq.peano.compiler` |
| `peano-tck` | — | test, `-Ptck` | `peano-core` | — |
| `peano-bench` | — | JMH, not deployed | `peano-core`, Expressly (bench scope only) | — |

Today only `peano-core` (scaffold) and `peano-tck` (skeleton) exist; the other
modules are created by the milestone that needs them — no empty placeholders.
No `peano-api` re-export aggregator of the spec jar (unlike `erasmus-api`):
`jakarta.el-api` 6.0.1 is already a proper Java module.

## Integration points

- **Ibarra (Jakarta Pages 4.0)** — consumes Peano through the standard API:
  `JspApplicationContext.getExpressionFactory()` returns
  `ExpressionFactory.newInstance()`, which finds Peano by `ServiceLoader`. Ibarra
  adds `GeneratedAccessorELResolver` to its page chain, and its Maven plugin
  calls `peano-compiler` to precompile the expressions of each page. Ibarra
  depends on Peano; Peano never depends on Ibarra.
- **Vauban (CDI 4.1)** — `vauban-core` already `requires jakarta.el`. CDI 4.1
  moved EL integration to the separate `cdi-el-api` (`ELAwareBeanManager`,
  `getELResolver()`, `wrapExpressionFactory()`); a future **`vauban-el`** module
  (in the Vauban repository, not here) will implement it and resolve `@Named`
  beans. Peano stays CDI-agnostic: it only offers the standard extension points
  (`ELManager.addELResolver`, `ExpressionFactory` wrapping) that `vauban-el` will
  use. Vauban's indexer can feed `@Named` bean types to `peano-processor`
  (type-selection option (b)).
- **Erasmus (Jakarta Validation 3.1)** — interpolates messages with a deliberate
  EL subset today. Jakarta Validation allows full EL; an optional
  `erasmus-el-peano` integration is an **open question** for Erasmus, not a Peano
  milestone.
- **Vidocq Runtime** — an extension that wires Peano as the EL provider of the
  assembled runtime, after P8.

## TCK plan

- Runner: `peano-tck`, in the reactor only under `-Ptck` (present now as a
  skeleton); the official suite is resolved only under `-Ptck,tck-official`.
- Distribution (checked 2026-10-10): the standalone EL 6.0 TCK is **not on Maven
  Central**. It ships as `jakarta-expression-language-tck-6.0.1.zip` (and 6.0.0)
  on `download.eclipse.org/jakartaee/expression-language/6.0/`; its artefacts are
  installed into the local Maven repository, as for the Servlet TCK of `foy-tck`.
  The 6.0.x branch of `jakartaee/expression-language` builds
  `jakarta.tck:jakarta-expression-language-tck:6.0.1`, confirmed from the
  distribution's embedded Maven metadata on 2026-10-10. Central only has the
  Platform-mode `jakarta.tck:el-platform-tck` 11.0.x.
  The suite uses JUnit Jupiter and selects the provider through
  `ExpressionFactory.newInstance()`. It includes `ELSigTestIT` for signature
  testing. Official cases are named `ELClientIT`: the runner must explicitly
  include `*IT` classes instead of relying on Surefire's default test patterns.
- EL is Java SE friendly: inspected suite sources do not use Arquillian.
- Script `run-official-tck-el-6.0.sh` (`smoke` / `all` / `-Dtest=`), added with
  the first real run, following the other bricks.
- Gate: once green, 100 % PASS is a merge gate on `main`; any challenge or
  exclusion goes to a `TCK.md` with the spec citation.

## Milestones (TDD — test first, every milestone)

| # | Content | Done when |
|---|---|---|
| P0 | Scaffold: reactor, `peano-core` module, `peano-tck` skeleton, docs, this plan | `./mvnw -ntp install` green (this commit) |
| P1 | Lexer, parser, AST for the full grammar; literal and operator evaluation; coercion rules; `ExpressionFactory` provider found by `ServiceLoader` | grammar test corpus (one test class per grammar production) + coercion table tests green |
| P2 | `ValueExpression` over the standard resolver chain: identifiers, `.`/`[]` navigation, `StandardELContext`, `ImportHandler`, static fields, `ELClass` | resolver-chain tests incl. Java Modules cases (non-exported package) |
| P3 | Functions, variables, `MethodExpression` (+ parameters), `MethodReference`, assignment, semicolon, `+=` | |
| P4 | Lambdas, collection construction, chapter 2 collection operations | |
| P5 | `ELProcessor` / `ELManager` standalone polish, `EvaluationListener`, serialization | Java SE example in docs runs |
| P6 | `peano-api` + `peano-processor`: generated accessor tables, `GeneratedAccessorELResolver`, `peano.reflection=deny` | differential tests generated vs reflective on the same fixtures |
| P7 | `peano-compiler`: Class-File API compiled expressions (build time) | differential tests compiled vs interpreted |
| P8 | Official TCK through `peano-tck` | 100 % PASS recorded in docs `tck` page |
| P9 | `peano-bench` (JMH vs Expressly), Vidocq Runtime extension | numbers in `BENCH.md` only |

## Open questions

1. **Runtime compilation** of dynamic expressions into hidden classes — allowed
   as an opt-in, or never (strict "no runtime bytecode")?
2. **Accessor type selection** — annotation in `peano-api`, typed declarations
   from Ibarra/Vauban, or a compiled-classes scan? Name of the annotation.
3. **Generated resolver placement** — the standard chain is built by API classes
   (`StandardELContext`); confirm Peano may prepend its resolver in the contexts
   *it* creates without diverging from TCK expectations on resolver order.
4. **TCK coordinates and harness shape** (see "TCK plan").
5. **Erasmus** — should full-EL message interpolation be offered through Peano
   (optional module on the Erasmus side)?
6. **Release line** — Peano starts at `0.4.0-SNAPSHOT` with the rest of the
   workspace; join the next coordinated release, or stay unreleased until P8?
7. **Remote repository and docs site** — `codefloe.com/Vidocq/peano` is assumed
   in the POM `<scm>`; the repository, the `vidocq-docs` playbook entry and the
   workspace `CLAUDE.md` listing are not created yet.
