# Peano — Design plan

> Status: **implementation in progress (P7)**. This document is the design of record for Peano, the
> Jakarta Expression Language 6.0 implementation of the Vidocq ecosystem. The core
> now implements grammar, evaluation, resolvers, lambdas, collection pipelines,
> evaluation events, expression serialization and a compiled Predicate adapter.
> All 361 official EL 6.0.1 TCK tests pass on Temurin 25 (2026-10-10).
> The peano-api service contract and direct accessor-table resolver are implemented.
> Property table APT generation is implemented with the approved annotation and explicit-type selection.
> Direct methods, runtime annotation literals and method metadata are implemented for selected models.
> Build-time literal/constant template compilation through the Class-File API is implemented.
> Optional PropertyEditor registry conversion is implemented; certification remains open.
> Decisions marked
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
- **Type selection (approved 2026-10-10):** (a) types annotated with `@ELAccessible`
  in `peano-api`; (b) types referenced by a typed declaration the build already
  knows (Jakarta Pages `jsp:useBean`, tag attribute types, CDI `@Named` beans
  found by Vauban's indexer); (c) a Maven-plugin scan of compiled classes, as in
  `erasmus-codegen-maven-plugin`, for types the user cannot annotate. Recommended
  selection: (a) + (b), approved by the maintainer; (c) remains on demand.

**Degraded reflective path (documented, not hidden):** a type with no generated
table falls through to the API's `BeanELResolver` (reflection, exported public
members only). The system property `peano.reflection=deny` (name approved 2026-10-11)
turns that fallback into an `ELException` naming the type, so an application can
prove at test time that Peano's member fallback stays unused — the same "eliminate runtime
fallbacks" approach Vauban follows.

### 2. Expression evaluation — interpreter first, compiled when known

P7 logical slice (2026-10-11): inline branches implement and/&& and or/|| over
supported scalar trees. Each evaluated operand uses the caller's boolean conversion;
discarded operands perform no evaluation or conversion. getType evaluates the body
and preserves those effects. Numeric overflow errors are emitted at the literal's
evaluation site, so discarded invalid numbers remain inert (COMP-003). Independent
runtime tests verify operand order, altered conversions, failures and serialization.

P7 arithmetic slice (2026-10-11): inline bytecode now implements +, -, *, / (div) and
% (mod) over scalar literal/unary/arithmetic trees. Numeric domains are known for these
inputs: Long or Double, including numeric-string floating markers. Both-null operands
produce Long zero before coercion, including division. Both operands are evaluated and
stored before either math coercion; null inputs become Long zero before caller conversion.
Generated guards wrap arithmetic/coercion RuntimeExceptions with their causes while
rethrowing ELException, and leave operand-evaluation failures outside that guard.
Operator getType reevaluates its body to retain conversion effects. Independent tests
compare 50 templates and separately check order, metadata effects, division infinities,
NaN, overflow and modulo-zero errors. Comparisons, conditional operations,
concatenation and dynamic resolver-backed nodes remain subsequent slices.

P7 unary slice (2026-10-11): the compiler now emits recursive minus, not/! and empty
over literal/unary trees, including Long.MIN_VALUE and signed zero. Numeric strings
are coerced through the caller context before negation; numeric literals do not gain
extra conversions. Empty preserves operand evaluation even when the empty result
is statically known. A single unary evaluation's getType reevaluates its body,
matching interpreter conversion effects without expected-type conversion or events.
Binary operators and dynamic resolver-backed nodes remain subsequent slices.

Compiled serialization UIDs now fingerprint exact UTF-16 units of canonical parsed
identity plus the peano-compiled-value-v1 emission-schema tag. The tag must change
when emitted semantics or protocol become serialization-incompatible. Changed AST
redeployments reject old streams; normalized equivalent forms retain original source
metadata and remain compatible. This replaces the initial prototype's fixed UID.
No runtime parsing or bytecode generation is used for restoration.

P7 first slice (2026-10-11): peano-compiler exports ExpressionCompiler.compile(binaryName,
source), returning a fresh Java 25 class file. The emitted public class extends the
peano-api CompiledValueExpression protocol base and has a public Class constructor for
the expected result type. The compiler uses the prescribed qualified core syntax export;
no syntax is exported to applications and no opens are added. A scoped module-lint
suppression accounts for the later-built compiler target during core-only javac.

Literal text, null/boolean/string/integer/floating AST literals and composite literal
templates are supported. Signed literals remain unary nodes and are rejected in this
slice, as are identifiers, operators, calls and other unsupported ASTs. Invalid names,
syntax/numeric literals and Class-File size limits receive build-time diagnostics.
There is no parser or interpreter fallback in emitted classes.

CompiledValueExpression owns expected-type conversion, successful evaluation events,
read-only metadata and serialization. Its opaque identity key represents normalized
parsed structure, independent of emitted binary name and immediate/deferred delimiter;
captured bindings can contribute through bindingIdentity in later slices. Compiled
instances compare only with other compiled instances, preserving symmetry with the
interpreter implementation. Generated body bytecode contains literal evaluation and
per-part context conversion for composites, then calls only API/JDK types.
Separate-JVM differential tests omit both core and compiler, verify class files and
constant-pool dependencies, compare values/types/literal flags and check equality,
custom conversion, events and serialization. Runtime modules require only peano-api
(transitively Jakarta EL) and the JDK for this slice.

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
the `empty` array case (section 1.11) and stream sources (section 2.3.1) use `java.lang.reflect.Array` for
allocation, element access and length only. Array component types are supplied
at runtime, so a single statically typed allocation cannot cover arbitrary
reference and primitive array targets. This narrow exception requires only
`java.base` and performs no member lookup or accessibility override.

P1 implements scalar and array coercions. P2 routes operand conversion through
the evaluation context; functional-interface adaptation belongs with lambdas in P4. PropertyEditor
conversion through an application-owned ELResolver is verified. Automatic registry
discovery is available through the approved optional peano-beans-compat module only.
Core remains free of `java.desktop`; unsupported conversions raise ELException.

P3 metadata fallback (2026-10-10): ELResolver exposes invocation but no method-metadata
lookup SPI. MethodInfo/MethodReference temporarily use public method signature and
annotation inspection, restricted to members accessible to the jakarta.el API module,
including accessible interface/superclass declarations. This fallback never invokes
members, changes accessibility, adds opens or generates runtime bytecode. Generated
accessor metadata now takes precedence for selected models in P6. Custom hidden-method invocation uses the
application resolver; generated tables supply hidden metadata and requested return-type validation.
Captured FunctionMapper methods invoke through the API's StaticFieldELResolver, retaining
the mapped declaring class/name/parameter descriptor and API-qualified export access.

P5 serialization (2026-10-10): value and method expressions use a source/binding
serialization proxy. Public static function descriptors restore metadata through
Class.getMethod only; invocation stays in the API resolver. Contexts and parser trees
are rebuilt rather than serialized. Captured object values must be serializable.
Selected application functional interfaces now receive build-time adapters;
no runtime proxies or bytecode generation are used.

P6 runtime foundation (2026-10-10): peano-api exports only io.vidocq.peano.spi.
AccessorProvider contributes AccessorTable instances (ELResolver subclasses), keyed
by exact runtime class. Core uses ServiceLoader and snapshots registrations in an
immutable map; conflicting targets fail explicitly. The standard factory resolver
hook places table dispatch after application resolvers and before API fallbacks.
Closed application packages keep all direct calls inside their own Java module.
Named-consumer tests cover closed beans/records and compare a direct table against
the API RecordELResolver for an API-qualified exported record.
No APT generation or generated method metadata is claimed by this first slice.

### P6 processor property generation (selection approved 2026-10-10)

The maintainer approved the opt-in SOURCE-retained TYPE annotation
`io.vidocq.peano.annotation.ELAccessible` and explicit
`-Apeano.accessorTypes=pkg.Type,pkg.Other` for integrating builds.
The processor now implements both selections without a class-file scan.
It processes one named application module per javac invocation and rejects foreign
types and private enclosing declarations. Explicit names are retried across rounds
so another processor can supply a selected model before processing finishes.
Generated property tables cover public JavaBean getters/setters (including inherited
methods, primitive boolean is-getter precedence and acronym capitalization), write-only
properties and read-only record components. Ambiguous write-only setters and class
properties containing unbound type variables receive diagnostics.
Generated public providers live in the model package; Filer emits source and a
META-INF/peano/accessor-providers registration fragment for each selected type.
Users add that fragment's provides directive to the application's module-info.java.
Public method metadata and dispatch are now implemented. Source subtype support is
implemented, together with custom functional-interface adapters. The reflection-denial
switch is implemented with the approved configuration name.

P6 source hierarchies (2026-10-10): annotated and explicitly selected roots also select
their accessible descendants in the current compilation, including nested declarations
and types generated in later processing rounds. Each concrete type gets its own table
and registration fragment; register every required provider. Runtime lookup stays exact,
preserving concrete overloads, override annotations and covariant return metadata.
Static hiding keeps the most-derived declaration for each signature. Unselected private
descendants are skipped; explicitly selected private declarations remain errors.
Unresolved superclass/interface hierarchies defer generation until a later round and
receive diagnostics if still unresolved when processing ends. No binary subtype scan
is performed. Family selection depends on compilation roots and is not build-tool
isolating incremental processing.

P6 methods (2026-10-10): generated signatures and annotation literals provide metadata
without lookup or invocation. MethodSelection shares exact/assignable/coercible and
most-specific rules with the reflective metadata fallback. Generated branches convert
operands through ELContext and prepare typed varargs arrays at compile time.
The resolver binds its catalog in ELContext; metadata queries reuse the context snapshot
or discover the same services lazily for custom contexts.
Captured static functions dispatch through their owner's registered table before API fallback.
Public runtime annotation literals preserve defaults, nested/enum/class values and
Java equals/hashCode/defensive-array behavior, with no runtime proxies.
Object-declared methods and constructors retain the documented API path.
Inaccessible metadata types and specialized inherited generic parameters receive diagnostics.

| Module (artefact) | Java module | Kind | Requires | Exports |
|---|---|---|---|---|
| `peano-api` | `io.vidocq.peano.api` | runtime | `transitive jakarta.el` | `io.vidocq.peano.spi` and `io.vidocq.peano.annotation` (SOURCE ELAccessible) |
| `peano-core` | `io.vidocq.peano.core` | runtime | `transitive jakarta.el`, `io.vidocq.peano.api` | nothing — `provides jakarta.el.ExpressionFactory`, `uses io.vidocq.peano.spi.AccessorProvider` |
| `peano-processor` | `io.vidocq.peano.processor` | build | `java.compiler`, `io.vidocq.peano.api` | nothing — `provides javax.annotation.processing.Processor` |
| `peano-compiler` | `io.vidocq.peano.compiler` | build | `io.vidocq.peano.core` (parser/AST via a qualified export `to io.vidocq.peano.compiler`) | `io.vidocq.peano.compiler` |
| `peano-tck` | — | test, `-Ptck` | `peano-core` | — |
| `peano-bench` | — | JMH, not deployed | `peano-core`, Expressly (bench scope only) | — |

Today `peano-api`, `peano-core`, `peano-processor` and `peano-tck` exist; the other
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
  installed into the local Maven repository by the runner script, as for the Servlet TCK of `foy-tck`.
  The 6.0.x branch of `jakartaee/expression-language` builds
  `jakarta.tck:jakarta-expression-language-tck:6.0.1`, confirmed from the
  distribution's embedded Maven metadata on 2026-10-10. Central only has the
  Platform-mode `jakarta.tck:el-platform-tck` 11.0.x.
  The suite uses JUnit Jupiter and selects the provider through
  `ExpressionFactory.newInstance()`. It includes `ELSigTestIT` for signature
  testing. Official cases are named `ELClientIT`: the runner explicitly
  includes `*IT` classes instead of relying on Surefire's default test patterns.
- EL is Java SE friendly: inspected suite sources do not use Arquillian.
- Script `run-official-tck-el-6.0.sh` (`smoke` / `all` / `-Dtest=`) installs
  the official jar and executes the suite. `all` is the default. An invalid or
  empty selection fails. Signature setup supplies sigTestClasspath and jimage.dir;
  the upstream SigTest extracts the JDK modules itself.
- The test-only StandardVariableMapper supplies the TCK's reflective variable.mapper
  hook by delegating to the API's actual StandardELContext mapper. No fake mapper
  behavior or modified official test is used.
- The non-modular official harness runs on the classpath with explicit provider
  selection, confined to peano-tck. Runtime module checks stay in independent named consumers.
- First full Temurin 25 run after harness configuration: 361 tests, 21 errors.
  Regression tests and corrections for root writes, normalized equality, literal
  method invocation, unspecified conversion targets, bare-method fallback and
  compiled Predicate adaptation reduce this to 361/361 PASS, signatures included.
- Gate: 100 % PASS is now a merge gate on `main`; any challenge or
  exclusion goes to a `TCK.md` with the spec citation.
- A dedicated workflow runs the full TCK on pull requests and main.

Compatibility notes (2026-10-10): dynamic root namespace writes use the original
assigned value; only non-root properties query their writable type for conversion.
Bare MethodExpression invocation uses API-owned fallback when the resolver chain
does not handle it, as required by section 1.6; inline calls stay resolver-owned.
Expression identity uses normalized syntax while preserving source diagnostics.
Predicate adaptation is a statically compiled private implementation, not a runtime
proxy or generated class. Java platform adapters now cover all 43 java.util.function
interfaces plus Runnable, Callable and Comparator.

P6 functional adapters (2026-10-10, EL 6.0 section 1.25.8): select application interfaces
through ELAccessible or the existing explicit-types option. The selected interface must
carry java.lang.FunctionalInterface and permit a generated implementation (non-sealed).
Its provider contributes a conversion table keyed by target interface. An ordinary
generated Adapter class invokes the EL lambda and coerces its result, supporting primitive,
reference and void returns, zero/multiple parameters, array/varargs inputs and inherited
generic specialization. Generic interface signatures use Java erasure; generic SAM methods
receive a diagnostic. Object-equivalent methods and default/static methods do not count
as the abstract descriptor. Signature types must be accessible from the generated package.
Unselected child interfaces are not inferred. Parent hierarchies generated in later
rounds retain the same deferral rule as class tables.
ELContext conversions preserve custom resolver precedence and use the caller context
for lambda invocation/result conversion. Factory conversion has no caller context: its
table receives null and its adapter invokes the lambda with its captured context, then
uses ExpressionFactory result coercion. Providers use the existing registration fragments.
No new runtime dependency, exports, opens or member introspection is introduced.

## Milestones (TDD — test first, every milestone)

P6 Java platform adapters (2026-10-11, EL 6.0 section 1.25.8): 46 statically compiled
private records cover all java.util.function interfaces, Runnable, Callable and Comparator.
Each has its Java descriptor signature, including primitive specializations; generic
contracts use erasure. Context conversion claims supported lambdas after custom resolvers
and registered application tables; result conversion reuses that context. Factory coercion
uses the lambda's captured context and intrinsic result conversion. Null and identity
conversions retain the ordinary rules. Default methods compose the implemented descriptor.
Adapters are ordinary compiled classes, with no proxy, runtime generation or member lookup.
Named-consumer tests verify typed callbacks and generated method arguments in deny mode.
Reflective EL navigation on an adapter's Java methods remains subject to the existing
fallback policy; the adapter itself invokes its lambda directly.

P6 reflection policy (2026-10-11): peano.reflection defaults to allow; deny prevents
Peano-controlled member fallback and invalid values receive an explicit diagnostic.
The factory-supplied resolver chain tries custom resolvers, generated tables and
collection pipelines before the guard. Direct map/list/resource-bundle/array property
operations remain available; bean/record fallback, static fields/methods, constructors
and ungenerated Object methods are denied with the target type named in ELException.
Generated method metadata remains available; reflective metadata searches and direct
bare-method/captured-function fallback are guarded even with a custom ELContext.
Captured-function deserialization is denied, including generated function owners,
because restoring the Method descriptor requires reflective lookup. Serialization
without captured functions remains available.
Application-supplied resolver chains and callbacks retain their own implementation
responsibility: the setting governs Peano's fallback, not reflection inside application
resolvers or API import handling. Runtime-typed array operations remain the documented
non-member reflection exception. Configure the system property before application startup.

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

## PropertyEditor boundary study (2026-10-11)

EL 6.0 section 1.25 permits custom ELResolver conversions; section 1.25.9 specifies
the legacy editor rules for other types. The release makes java.desktop optional,
not the editor API independent of java.beans. Sources:
[EL 6.0 specification](https://jakarta.ee/specifications/expression-language/6.0/jakarta-expression-language-spec-6.0),
[EL team discussion](https://www.eclipse.org/lists/el-dev/msg00208.html).

Two named-consumer proofs now cover the existing architecture:

- A launcher limited to java.base, Jakarta EL and the Peano/application modules runs
  generated property/method/callback and collection scenarios in deny mode. The actual
  boot layer is asserted to exclude java.desktop.
- A separate test application explicitly requires java.desktop, owns its editor and
  resolver, and performs text conversion through ELResolver.convertToType. Each call
  constructs a fresh editor. Failed empty text returns null, invalid nonempty text
  raises ELException, and the integration works in deny mode without core member lookup.

Approved implementation (2026-10-11): the opt-in `beans-compat` Maven profile builds
peano-beans-compat (`io.vidocq.peano.beans.compat`). Only that module requires java.desktop
and discovers PropertyEditorManager registrations. Core uses the API TypeConversionProvider
service after built-in conversions and before the unsupported-target empty-text fallback.
Providers run by descending priority, then class name; the legacy provider has lowest
priority. Both factory and context coercion use this path, with context-local converters
retaining precedence. A handled null is distinct from an unhandled conversion.

Each call obtains a fresh editor and observes current registrations. Successful empty
text conversion returns the editor result; failed empty text returns null; invalid
nonempty text raises ELException with its cause. JDK-created editor classes may need a
package export qualified to java.desktop, without opens. Installing this optional artifact
explicitly enables registry compatibility even in deny mode. Distributions without it
retain the tested desktop-free boot layer. EL-016 is resolved for opt-in distributions.

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
