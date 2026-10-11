# Peano optimization study

Date: 2026-10-11. Implementation baseline: `8a80bf342ac28e817029bc65f5956d447edc1da3`.
Scope: investigation and proposed experiments only. This document introduces no
implementation changes and reports no new performance measurements.

## Recommendation

Start with reproducible CPU and allocation profiles, then investigate repeated
expression creation and numeric literal evaluation. These have concrete source-level
opportunities and directly exercise the existing corpus. Investigate conversion
dispatch and lambda allocation next, according to the profiles. Keep property access
and collection construction as lower priorities until broader workloads show a need.

Build-time compilation already reduces evaluation time for arithmetic and the lambda
fixture, but it does not remove resolver conversions or dynamic numeric dispatch.
Generating more bytecode alone is therefore not an established solution to the
remaining gap. No speedup target is justified by the available evidence.

## Recorded evidence

The authoritative measurements and reproduction commands are in [BENCH.md](BENCH.md).
The table below reproduces rounded mean scores from the
[2026-10-11 corpus JSON](peano-bench/results/2026-10-11-corpus.json).
All values are nanoseconds per operation; smaller is better.

| Fixture | Create: Peano | Create: Expressly | Evaluate: Peano | Evaluate: Expressly | Evaluate: compiled Peano |
| --- | ---: | ---: | ---: | ---: | ---: |
| Arithmetic `${(a+2)*3}` | 306.893 | 25.525 | 189.967 | 51.717 | 164.142 |
| Property `${model.value}` | 215.965 | 24.068 | 70.236 | 83.406 | 72.776 |
| Lambda `${(x -> x+1)(a)}` | 489.720 | 30.798 | 203.987 | 107.603 | 155.380 |
| Collection `${[a,2,3]}` | 364.113 | 24.417 | 71.554 | 54.065 | 74.178 |

These measurements support the following observations:

- Repeated creation takes substantially longer with Peano across all four fixtures.
  This benchmark includes each implementation's existing caches; it is not a
  comparison of uncached parser throughput.
- Compiled Peano evaluates arithmetic and the lambda fixture faster than its
  interpreter, while Expressly remains faster on these two fixtures.
- Peano's generated property access is faster than Expressly's reflective access in
  this fixture. The different access strategies prevent attribution of that result
  solely to the expression evaluator.
- The compiled property and collection paths show no demonstrated advantage over
  interpreted Peano in this run. Their small mean differences do not justify a
  general conclusion about compilation overhead.

### Limits of this evidence

The run used Apple M5 Max hardware, Temurin 25+36-LTS, JMH 1.37, Expressly 6.0.0 and
Jakarta EL 6.0.1. It used one benchmark thread, two forks, two 500 ms warmup
iterations and three 500 ms measurement iterations per fork. The six samples and
short warmup make this an exploratory workstation result. Power, thermal state and
background load were not controlled. JMH errors are recorded in BENCH.md and the
JSON; they must not be treated as standard deviations.

Contexts and expressions are reused during evaluation. Compilation, generated-class
loading and constructor lookup occur outside the evaluation timer. Creation repeatedly
uses the same source. The corpus uses a small Long value, one getter and tiny
collections. It does not cover large numeric values, mixed numeric types, custom
converters, listeners, long resolver chains, streams or application request lifecycles.
Small boxed values also make it unsuitable for estimating boxing allocation generally.

The earlier arithmetic-only baseline has a different protocol and context composition.
Its difference from the corpus is not evidence of an implementation improvement.
The initial prototype result is invalid because of the factory/context association bug
documented as BENCH-002. The module smoke result is not a performance baseline.

No CPU profile, allocation profile or retained-memory measurement was collected for
this study. The source establishes work that exists, not its share of measured time.
Expressly's internal cache design has not been investigated here.

## Source findings

### Expression creation

[`PeanoValueExpression.text`](peano-core/src/main/java/io/vidocq/peano/core/internal/PeanoValueExpression.java)
parses the source on each call, captures variables and functions through separate
AST traversals, and constructs an expression whose constructor builds a normalized
identity tree through
[`ExpressionIdentity`](peano-core/src/main/java/io/vidocq/peano/core/internal/ExpressionIdentity.java).
The normalization recursively creates additional AST records and lists.

[`Bindings`](peano-core/src/main/java/io/vidocq/peano/core/internal/evaluation/Bindings.java)
allocates maps and visited sets for capture. Lambda nesting creates additional sets
to exclude local parameter names. These operations are plausible contributors to
creation cost; the benchmark does not isolate their individual costs.

### Interpreted evaluation

[`Interpreter`](peano-core/src/main/java/io/vidocq/peano/core/internal/evaluation/Interpreter.java)
creates an evaluator object per entry. Numeric literal evaluation calls
`Long.valueOf(token.text())` or `Double.valueOf(token.text())` on each visit.
Negative integer literals have a separate path that constructs their signed text.
This repeats numeric parsing during evaluation even though the token is immutable.

Arithmetic delegates to
[`Arithmetic`](peano-core/src/main/java/io/vidocq/peano/core/internal/evaluation/Arithmetic.java),
which selects a numeric domain and converts operands through `ELContext.convertToType`.
The outer expression also converts its result to its expected type. Captured object
ValueExpressions perform their own expected-type conversion when read. Consequently,
the arithmetic fixture exercises several conversion calls in addition to AST dispatch.
The allocation of small evaluator/helper objects is visible in the source, but the
JIT may eliminate some of it; allocation profiling must establish what survives.

### Compiled evaluation

[`ExpressionCompiler`](peano-compiler/src/main/java/io/vidocq/peano/compiler/ExpressionCompiler.java)
emits constants for valid numeric literals, runtime failures for invalid selected
literals, and numeric dispatch for operands whose types are dynamic. Its
`numericOperand`, `bigOperand` and arithmetic emission retain context conversion.
[`CompiledValueExpression`](peano-api/src/main/java/io/vidocq/peano/spi/CompiledValueExpression.java)
retains before/after notifications and final expected-type conversion.

The compiler therefore removes parsing and interpreter traversal, while substantial
semantic work remains shared with the interpreted path. Whether conversions, branches,
inlining or another operation explains the remaining arithmetic cost requires profiling.

### Lambdas and collections

Interpreted lambda evaluation constructs a `LambdaExpression` and
[`LambdaBody`](peano-core/src/main/java/io/vidocq/peano/core/internal/evaluation/LambdaBody.java).
Capture traverses the body and allocates capture bookkeeping; invocation supplies an
argument array and enters a lexical scope. Compiled lambda emission constructs
parameter/name arrays at the creation site. These are allocation candidates rather
than measured allocation totals.

List literals already use `new ArrayList<>(elementCount)` in the interpreter.
Set literals first construct a list, then a `LinkedHashSet`; maps start with a
default-sized `LinkedHashMap`. Compiled emission follows the same broad construction
pattern. Avoid proposing list pre-sizing as a new optimization: it already exists.

[`GeneratedAccessorELResolver`](peano-core/src/main/java/io/vidocq/peano/core/internal/evaluation/GeneratedAccessorELResolver.java)
uses an immutable exact-type table map and context registration. The measured property
path already uses generated calls. Other factory/context setup and service-discovery
paths are outside the evaluation timer and need separate startup measurements.

## Candidate priorities

The order below balances measured relevance, implementation scope and semantic risk.
It is an experiment order, not an authorization to implement these changes.

| ID | Candidate | Priority | Expected effect to investigate | Main risk |
| --- | --- | --- | --- | --- |
| O0 | Extend measurements and profile existing paths | First | Attribute CPU, allocation and retained memory | Misleading fixture or profiling overhead |
| O1 | Reuse context-free creation analysis with bounded storage | High | Less repeated parsing, normalization and traversal | Literal identity, binding isolation, cache memory |
| O2 | Prepare numeric literals once | High | Remove repeated numeric text parsing in the interpreter | Lazy errors and observable object identity |
| O3 | Reduce conversion/dispatch overhead while preserving hooks | Profile-dependent | Improve arithmetic in both execution modes | Skipping converters or changing effect order |
| O4 | Precompute lambda metadata and simplify empty captures | Profile-dependent | Fewer traversals and temporary allocations | Closures, null captures and reentrancy |
| O5 | Construct sets directly and size maps appropriately | Lower | Avoid intermediate storage and resizing | Evaluation effects, order and freshness |
| O6 | Investigate startup/catalog setup separately | Lower | Reduce context/factory initialization work | Class-loader retention and provider isolation |

### O1 — Reuse creation analysis

First measure parsing, identity construction and mapper capture separately. A reusable
artifact could contain immutable syntax structure, canonical identity and ordered
variable/function descriptors. Each expression must still capture its own mapper
results at creation time; never cache a ValueExpression, ELContext, bound Method or
application object as part of a shared syntax entry.

A source-keyed cache must have an explicit capacity and eviction policy, with bounds
on retained weight as well as entry count. Large and mostly unique sources must not
turn the optimization into unbounded retention. Use concurrency facilities consistent
with the project's ConcurrentHashMap discipline, without `synchronized` or ThreadLocal.
Choose cache ownership and lifecycle explicitly; factory-local storage is one option
to evaluate against shared storage, rather than a predetermined design.

Sharing the current AST unchanged is unsafe as a default proposal. Nonempty String
literals have observable per-expression identity: [COMP-007 in BUG.md](BUG.md)
records conversions skipped when separate expressions shared their literals.
Reusing structure therefore requires separating structural data from expression-owned
literal values, or instantiating the affected nodes while retaining stable identity
within an expression. Measure that materialization cost before claiming a cache win.

Descriptor collection must preserve lexical parameter exclusion, first-occurrence
mapper lookup order, duplicate suppression, missing-function behavior and error timing.
Separate variable and function lookup phases should remain unless tests prove their
reordering unobservable. Preserve source offsets and diagnostics; do not merge cache
keys through whitespace or delimiter normalization. Any negative caching needs its
own bounded policy and diagnostics tests.

An incremental alternative is to precompute identity/descriptors without introducing
a cache. It has less retention risk and provides useful component measurements.
ValueExpression and MethodExpression creation need separate coverage.

### O2 — Prepare numeric literals once

Represent a literal's numeric interpretation as immutable prepared data, with explicit
success or deferred-failure information. Retain raw text and offsets for identity,
serialization and diagnostics. Preparing an overflowing literal must not reject
expression creation merely because a discarded branch contains it: COMP-003 already
demonstrates the equivalent compiler regression. Raise the failure only when the
literal is evaluated, preserving the exception type, cause and diagnostic.

Signed integer preparation must retain the special handling of Long.MIN_VALUE.
Test positive/negative overflow, floating-point parsing and literals behind lazy
logical/conditional branches. Treat boxed-value identity separately from numeric value:
reusing a previously fresh wrapper can change equality shortcuts and converter calls.
Preparing a primitive value and preserving the required boxing behavior may be safer
than caching a boxed object. This candidate principally benefits interpretation;
valid compiled numeric literals are already prepared during compilation.

### O3 — Conversion and arithmetic dispatch

Profile complete `getValue` calls, including captured variable reads, resolver
conversion, default coercion and final result conversion. Inspect generated machine
code or inlining diagnostics only if the CPU profile points to dispatch overhead.
Distinguish numeric domain selection from conversion-chain traversal and boxing.

Potential experiments include simplifying common dispatch branches and reducing
duplicated helper logic. Any specialization must retain the general path for changing
runtime types, BigInteger/BigDecimal, numeric strings, nulls and custom converters.
Build-time knowledge of syntax is not proof of the type a resolver will return.

Do not bypass `ELContext.convertToType` merely because a value is already assignable,
the expected type is Object, or both operands are Long. A custom resolver may handle
that conversion. Likewise, numeric operations must evaluate both operands before
their conversions, as the existing emitter explicitly does. Constant folding or
primitive fusion requires proof that it preserves converter calls and side effects;
it is not a safe blanket optimization for the general EL contract.

### O4 — Lambda metadata and capture

Investigate precomputed free-variable descriptors to avoid traversing the body on
every lambda construction. Measure empty capture handling separately from nested
closures. Compiled parameter/name arrays could be replaced by immutable metadata
only after establishing that downstream API code cannot mutate or retain them in an
observable way.

Preserve null captures, shadowing, lexical scope, retained lambdas, exceptions and
recursive/reentrant evaluation. Scope cleanup must remain in `finally`. Do not pool
lambda instances, mutable argument arrays or scope maps across evaluations. An
immediately invoked lambda shortcut would require differential tests for nested
closures and full API behavior before consideration.

### O5 — Collection construction

Direct set insertion can avoid the intermediate list, but currently every element is
evaluated before the set constructor hashes/inserts values. Interleaving evaluation
with hashing may change effects for application objects. Evaluate this ordering issue
before adopting direct insertion. For maps, investigate safe capacity calculation
without overflow or excessive allocation for duplicate keys.

Preserve null support, duplicate handling, encounter order, mutable fresh results and
per-evaluation element effects. Immutable or shared constant collections would change
those properties. Larger collections and sets/maps are required to judge this work;
the existing three-element list fixture gives no evidence for the proposed set/map
improvements.

### O6 — Startup and provider catalogs

Add separate measurements for factory/context creation, service discovery, generated
table registration and first evaluation. Existing hot evaluation results cannot justify
startup caching. Reuse immutable context-owned metadata where appropriate, preserving
the established class-loader refresh behavior and custom-context support. Avoid global
strong references to application classes or loaders. Include application unload and
provider replacement checks when evaluating any lifecycle change.

## Measurement and validation plan

1. Keep the existing corpus as a compatibility baseline. Repeat it with a longer
   protocol selected before comparing changes, on the same JDK/hardware under recorded
   load and power conditions. Check warmup stability and uncertainty rather than
   selecting whichever run shows the largest gain.
2. Separate warm repeated creation, first-use creation and misses over a bounded set
   of distinct sources. An uncached parser measurement must bypass any expression
   cache explicitly. Vary expression length, nesting, mapper density and lambda depth.
3. Collect allocation bytes/op and GC behavior with JMH's GC profiler. Capture CPU
   and allocation profiles in separate diagnostic runs using JDK tooling or justified
   build-only profiling tools. Keep profiling overhead out of the comparison scores.
4. Expand evaluation inputs: numeric values outside boxing caches, floating point,
   BigInteger/BigDecimal, numeric strings, nulls, changing resolver-returned types,
   different expected types, converters, listeners and longer resolver chains.
   Include nested/retained lambdas and larger list/set/map/stream workloads.
5. Add paired property fixtures with equivalent resolver strategies where feasible,
   alongside the existing generated-versus-reflective application comparison.
   Measure compiled construction and generated artifact size separately from evaluation.
6. For caches, measure bounded retention, hit/miss behavior and throughput with
   independent contexts under concurrency. Test eviction and pathological unique
   sources. Never share a mutable ELContext between benchmark threads.
7. Record every new performance figure in BENCH.md with hardware/JVM, exact command,
   raw results and a delta against a comparable run. Use the same protocol and
   fixtures for before/after comparisons and identify changes to the harness.

Each future implementation experiment must start with a failing semantic test where
behavior is at risk, then pass the relevant unit and differential tests. Use the
interpreter as an oracle for compiled changes; when interpretation itself changes,
retain explicit expected results and the pre-change semantic regression corpus.
The official 361-test EL TCK remains a merge gate. Named-module consumers must keep
passing, including API-only compiled execution, reflection-deny paths and execution
without java.desktop where applicable.

Required semantic cases include custom conversion of already-compatible values,
listener ordering, propertyResolved state, creation-time mapper snapshots, lexical
shadowing, String and numeric identity effects, lazy invalid literals, serialization,
exception cleanup, mutable collection freshness and class-loader lifecycle.

## Execution plan

Prepared: 2026-10-11. All lots below are planned; implementation has not started.
The first deliverable is a reproducible measurement baseline. The subsequent lots
are conditional experiments, with one focused PR per independently reviewable change.
This plan does not select a public SPI change or resolve an open structural question
in [PLAN.md](PLAN.md).

### Sequence and dependencies

| Lot | Scope | Depends on | Deliverable | Exit decision |
| --- | --- | --- | --- | --- |
| OPT-00 | Reproducible baseline and profiling (O0) | Existing corpus | Recorded timings, allocation data and profiles | Select supported candidates and comparison workloads |
| OPT-01 | Numeric literal preparation (O2) | OPT-00 attribution | Interpreter experiment with lazy-failure and identity tests | Keep only if evaluation improves without semantic regressions |
| OPT-02 | Creation analysis without shared caching (O1) | OPT-00 attribution | Parsing/identity/binding breakdown and focused simplification | Decide whether repeated-source reuse warrants a cache |
| OPT-03 | Bounded creation cache (O1) | OPT-02; ownership and literal design established | Hit/miss/retention experiment and concurrency tests | Keep only if the complete creation path benefits within memory bounds |
| OPT-04 | Conversion and arithmetic dispatch (O3) | OPT-00; refresh profiles after earlier retained changes | One supported dispatch experiment per PR | Keep only with unchanged hooks and dynamic fallback behavior |
| OPT-05 | Lambda metadata and capture (O4) | OPT-00; OPT-02 if it supplies reusable descriptors | Capture/allocation experiment | Keep only with closure, recursion and cleanup equivalence |
| OPT-06 | Collection construction (O5) | Larger collection baseline from OPT-00 | Separate set/map experiments | Proceed only when allocation or resizing is material |
| OPT-07 | Startup/catalog lifecycle (O6) | Startup baseline from OPT-00 | Setup/lifecycle experiment | Proceed only when setup cost matters to application use |
| OPT-08 | Consolidation | Every selected experiment accepted or discarded | Final BENCH.md comparison and updated decisions here | Close the campaign with explicit outcomes and remaining work |

OPT-01 and OPT-02 are the first implementation candidates after OPT-00. Their order
can change according to attribution. OPT-03 follows OPT-02; a cache is not the first
change. OPT-04 through OPT-07 may be deferred or discarded when their workloads show
no material problem. Deferred work does not block acceptance of an independent gain.

### OPT-00 — Establish the measurement baseline

- [ ] Record the source commit, JDK/Maven versions, machine, power/load conditions,
  JVM arguments, provider setup and exact harness commands in BENCH.md.
- [ ] Re-run the existing four-fixture corpus under a longer fixed protocol. Use
  five one-second warmups, eight one-second measurements and three forks as the
  initial protocol; inspect stability before freezing the comparison protocol.
- [ ] Add creation workloads that distinguish repeated source, first use, a bounded
  rotating source set and unique-source misses. Keep setup outside the timer where
  that is appropriate to the named workload; specify factory/context ownership.
- [ ] Add targeted inputs outside small boxing caches, nested lambdas and larger
  sets/maps. Introduce only the cases needed to assess each candidate, rather than
  multiplying every parameter into one expensive benchmark matrix.
- [ ] Measure creation components separately: parser, identity normalization and
  mapper capture. Keep full ExpressionFactory creation as the acceptance benchmark;
  component results are diagnostic and cannot replace it.
- [ ] Collect GC/allocation metrics in a separate run and CPU/allocation profiles for
  creation, arithmetic and lambda evaluation. Verify the modular fork setup still
  works with the selected profiler. Do not introduce shipped profiling dependencies.
- [ ] Add separate factory/context initialization and compiled construction measurements
  if startup or construction is selected for optimization.
- [ ] Record the attribution, measurement uncertainty and selected workloads here
  before beginning a production-code experiment.

Commands below describe the planned protocol and do not report a completed run.
Run from the checkout with the pinned Java 25 and Maven 3.9.16 environment:

```bash
rtk ./mvnw -ntp -Pbench,beans-compat clean package
rtk ./run-bench.sh -wi 5 -w 1s -i 8 -r 1s -f 3 -rf json -rff peano-bench/target/optim-baseline.json
rtk ./run-bench.sh -wi 5 -w 1s -i 8 -r 1s -f 3 -prof gc -rf json -rff peano-bench/target/optim-baseline-gc.json
```

Archive accepted raw results under `peano-bench/results/` with a dated name and
BENCH.md entry. Keep diagnostic profile artifacts outside published runtime jars;
record their location and reproduction procedure. Retained-memory checks complement
bytes/op measurements when evaluating a cache.

### OPT-01 — Prepare numeric literals

- [ ] First write tests for valid integer/floating literals, Long.MIN_VALUE, selected
  and discarded overflow, exception causes/offsets, serialization and custom
  conversion of repeated literals.
- [ ] Establish expected numeric wrapper identity across repeated evaluation and
  separate expression instances. Include equality paths that can skip conversion
  when operands share identity.
- [ ] Choose an immutable representation for a prepared primitive value or deferred
  failure. Preserve original token text and the existing public syntax contract.
- [ ] Implement only the interpreter literal path first. Do not combine this change
  with a cache, constant folding or arithmetic conversion bypass.
- [ ] Compare arithmetic, lambda and collection evaluation, and also expression
  creation cost: preparation moves work earlier and must be measured there.

Exit: the same lazy error behavior and observable values, with a repeatable evaluation
gain and a documented creation/memory tradeoff. If no useful gain remains after
profiling, record the result and discard the experiment.

### OPT-02 — Reduce creation analysis work

- [ ] First test creation-time mapper snapshots, first-occurrence lookup order,
  duplicate lookup suppression, lexical exclusions and missing-function errors.
- [ ] Use the OPT-00 breakdown to select normalization or descriptor traversal as
  the first target. Change one source of work per experiment.
- [ ] Evaluate a context-free artifact containing canonical identity and ordered
  binding descriptors, without introducing cross-expression storage yet.
- [ ] Preserve distinct variable and function capture phases unless tests prove that
  changing their order is unobservable. Keep bound values outside reusable data.
- [ ] Verify ValueExpression and MethodExpression equality/hash behavior, diagnostics,
  serialization and creation with null mappers.
- [ ] Measure complete creation with repeated and distinct sources, including the
  cost of any added analysis or representation.

Exit: either a useful standalone reduction or evidence that reuse is required to
amortize analysis. Before OPT-03, document exact literal ownership, artifact size,
cache owner, cache key and lookup/materialization responsibilities. Do not retain a
slower preparatory abstraction solely to make a later cache easier to implement.

### OPT-03 — Add bounded reuse only when justified

- [ ] Decide capacity and retained-weight limits from the recorded source-size and
  workload distribution. Define eviction, oversized-entry and concurrent-miss behavior.
- [ ] First test fresh nonempty String ownership, stable per-expression values,
  independent mapper bindings, eviction and large/unique-source workloads.
- [ ] Test concurrent creation with independent contexts and factories, including
  duplicate misses. Check ownership against class-loader lifecycle and retained data.
- [ ] Cache only context-free data; materialize expression-owned literals and bindings
  according to OPT-02. Never retain a bound Method, mapper or application object.
- [ ] Measure hit, miss, mixed working-set and capacity-pressure behavior, including
  allocation, retained heap and concurrency. Keep the uncached creation comparison.
- [ ] Request the applicable concurrency/module review for implementation changes,
  as required by AGENTS.md. No runtime dependency or new package export is assumed.

Exit: a bounded memory footprint, preserved isolation and a useful gain in full
creation for the selected workload. Report miss/eviction penalties alongside hit
gains. A failed gate means discarding the cache, not relaxing semantic checks.

### OPT-04 and OPT-05 — Optimize measured evaluation costs

For OPT-04, select the conversion/dispatch branch identified by the profile. Write
tests for compatible-type conversion, custom resolver results, propertyResolved state,
operand/conversion ordering, numeric promotion and dynamic types before changing it.
Measure both matching and general/fallback paths. Preserve every required conversion
and evaluation notification. No primitive specialization is assumed safe in advance.

For OPT-05, first test empty and nullable captures, nested/retained closures, shadowing,
recursion, reentrancy and failure cleanup. Reuse immutable free-variable metadata only
where its lifetime and API use permit it. Measure immediately invoked and retained
lambdas separately. Keep scope cleanup in `finally`; do not pool mutable call state.

Refresh profiles after retained earlier changes before selecting either lot. Changes
to Class-File API emission require the applicable codegen/module review and independent
API-only generated consumers. Each lot exits with differential equivalence and a
repeatable gain for its target workload without an unexplained general-path regression.

### OPT-06 and OPT-07 — Conditional workloads

For OPT-06, establish larger list/set/map workloads first. Before direct set insertion,
write a test with observable element evaluation and hashCode/equals effects: the current
implementation evaluates all elements before set construction. For map sizing, cover
duplicates, empty/large inputs and safe capacity arithmetic. Preserve fresh mutable
results, nulls and encounter order. Separate set and map changes into distinct PRs.

For OPT-07, measure first-use and steady factory/context setup separately. First test
provider isolation, loader-change refresh and custom contexts before reusing metadata.
Record lifecycle retention as well as setup timing; an evaluation-only benchmark cannot
accept this change. Preserve existing context ownership and application unload behavior.

### Validation required for implementation PRs

Each PR carries the semantic regression evidence, a before/after comparison using
the frozen protocol, and the BENCH.md entry with raw results. Reproducible defects
discovered during an experiment receive a BUG.md entry. Document any harness change
and re-establish a comparable baseline when necessary.

Run focused tests during TDD, then the appropriate reactor checks. The full official
TCK, including signatures, is mandatory before merging a retained production change:

```bash
rtk ./mvnw -ntp -Pbench,beans-compat clean install
rtk ./run-official-tck-el-6.0.sh all
```

The bench profile above verifies the harness; it does not run performance comparisons.
Also verify named-module consumers and the affected allow/deny, API-only compiled and
desktop-free scenarios. Use the equivalent successful CI checks as proof where they
execute the same required validation; repeat checks only after relevant changes or
unresolved failures. The documentation-only preparation of this plan does not require
running the implementation test suite locally.

### OPT-08 — Consolidate results

- [ ] Mark every selected lot accepted, discarded or deferred, with a reason and
  links to its PR, raw measurements and semantic validation.
- [ ] Re-run the accepted corpus on the final combination of changes to detect
  interactions; compare it with OPT-00 under the same protocol.
- [ ] Record final creation/evaluation/allocation results, cache retention where
  applicable, and any measured startup tradeoff in BENCH.md.
- [ ] Update this document with the effective order, final design choices and deferred
  candidates. Update PLAN.md only if an accepted design decision changes its record.

Campaign completion means all chosen lots have a recorded outcome and the retained
combination has passed its checks. It does not require implementing every candidate,
matching Expressly on every fixture or reaching an invented percentage improvement.

## Decision rules

Before each experiment, name its workload, expected benefit and acceptable tradeoffs.
Retain it only when comparable repeated runs show a useful benefit, measurement
uncertainty permits that conclusion, semantic checks pass and memory/startup/general
path costs are acceptable. Rework or discard gains that disappear under comparable
runs. Report absolute results and uncertainty alongside deltas.

All implementation remains subject to the project constraints: Java 25, strict Java
Modules, build-time Class-File API/APT, no additional shipped dependencies beyond the
permitted APIs, no dynamic proxies or runtime bytecode generation, and no unjustified
reflection or package opens. The existing Maven processor-classpath accommodation
is build-only. Runtime modules remain named modules. Any unresolved structural choice
marked for validation in PLAN.md remains subject to its existing decision process.

The next execution lot is OPT-00. This plan establishes a sequence and required proof;
it does not establish a dominant cost or promise a performance target.
