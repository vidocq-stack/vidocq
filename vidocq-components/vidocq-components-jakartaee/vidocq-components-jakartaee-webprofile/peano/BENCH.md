# BENCH.md — Peano

JMH performance numbers. Entry format: `date · hardware/JVM · exact command ·
raw results · delta vs previous run`. No performance numbers elsewhere (README, commit)
without a corresponding entry here.

## 2026-10-11 · Prototype diagnostics (excluded from comparison baseline)

This run predates the BENCH-002 correction: StandardELContext did not carry the selected
factory in its conversion context key. With both providers present, conversion could
use a globally selected factory. Preserve these samples for traceability; they do not
establish a valid engine comparison. The corrected baseline is recorded below.

- Hardware: Apple M5 Max, Mac17,6, 18 logical CPUs, 128 GiB RAM; macOS 27.0.1.
- JVM: Temurin 25+36-LTS, OpenJDK 64-Bit Server VM; JMH 1.37, Expressly 6.0.0,
  Jakarta EL API 6.0.1. One benchmark thread, peano.reflection=allow, named modules.
- Sources: initial peano-bench harness based on b32738a, recorded alongside this entry.
- Fixture: `${(a+2)*3}`, captured variable a=7L, expected Object result 27L.
  Evaluation reuses one expression/context per trial. Creation repeatedly creates the
  same source and includes provider caching; this is not an uncached-parser comparison.
- Local exploratory run; other workstation activity was not controlled. CPU frequency,
  power mode and thermal state were not fixed. Results do not establish production throughput.

Exact build and run, from the checkout root:

```bash
export JAVA_HOME=/Users/yblazart/.sdkman/candidates/java/25-tem
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw -ntp -Pbench -pl peano-bench package
./run-bench.sh -rf json -rff peano-bench/target/jmh-2026-10-11.json
```

JMH annotations select 2 forks, 3 one-second warmups and 5 one-second measurements
per fork. Returned values are consumed by JMH. Raw output (decimal separator from
the workstation locale):

```text
Benchmark                      (engine)  Mode  Cnt    Score    Error  Units
CreationBenchmark.create          peano  avgt   10  312,048 ± 15,002  ns/op
CreationBenchmark.create      expressly  avgt   10   25,675 ±  0,518  ns/op
EvaluationBenchmark.evaluate      peano  avgt   10  194,183 ±  5,342  ns/op
EvaluationBenchmark.evaluate  expressly  avgt   10   52,744 ±  0,543  ns/op
EvaluationBenchmark.evaluate   compiled  avgt   10  154,303 ±  2,640  ns/op
```

Complete JVM arguments, per-fork samples and JMH statistics are preserved in
[the raw JSON](peano-bench/results/2026-10-11-initial.json). These prototype samples
are diagnostic artifacts and do not support an engine comparison.
Delta vs previous comparable run: none; the prototype is excluded from comparison.

### Startup validation before the baseline

Same hardware/JVM, exact command `./run-bench.sh -wi 0 -i 1 -r 100ms -f 1`.
This run only verified modular forks and is not comparable to warmed measurements.

```text
CreationBenchmark.create          peano  avgt  615,348 ns/op
CreationBenchmark.create      expressly  avgt   49,464 ns/op
EvaluationBenchmark.evaluate      peano  avgt  206,512 ns/op
EvaluationBenchmark.evaluate  expressly  avgt   65,657 ns/op
EvaluationBenchmark.evaluate   compiled  avgt  171,525 ns/op
```

Delta vs previous run: none; startup validation has a different protocol.

### Module-boundary assertion validation

After adding trial assertions for named expression modules and compiler absence, run
`./run-bench.sh EvaluationBenchmark.evaluate -p engine=compiled -wi 0 -i 1 -r 100ms -f 1 -rf json -rff peano-bench/target/module-smoke.json`.
Same hardware/JVM; assertions pass in the fork. Raw timing and JVM arguments are in
[the validation JSON](peano-bench/results/2026-10-11-module-smoke.json).
This is startup validation only; delta vs the warmed baseline is not comparable.

## 2026-10-11 · Corrected modular arithmetic baseline

Same hardware/JVM, expression, warmup/measurement/fork settings and workstation limits
as above. The selected factory is now explicitly stored under ExpressionFactory.class
in each ELContext; the regression checks both providers (BENCH-002). Named module and
compiler-absence assertions also pass in trial setup.

Exact commands, with the Java 25 environment above:

```bash
./mvnw -ntp -Pbench -pl peano-bench package
./run-bench.sh -rf json -rff peano-bench/target/jmh-2026-10-11-corrected.json
```

Raw output:

```text
Benchmark                      (engine)  Mode  Cnt    Score    Error  Units
CreationBenchmark.create          peano  avgt   10  330,551 ± 23,706  ns/op
CreationBenchmark.create      expressly  avgt   10   26,344 ±  1,257  ns/op
EvaluationBenchmark.evaluate      peano  avgt   10  198,986 ±  7,505  ns/op
EvaluationBenchmark.evaluate  expressly  avgt   10   53,126 ±  3,140  ns/op
EvaluationBenchmark.evaluate   compiled  avgt   10  164,400 ±  3,281  ns/op
```

[Complete raw JMH JSON](peano-bench/results/2026-10-11-baseline.json) preserves JVM
arguments and per-fork samples. Expressly is faster on this fixture; compiled Peano
improves evaluation over its interpreter. No broader performance conclusion follows
from one arithmetic expression. Repeated creation includes provider caches.
Delta vs previous comparable run: none; this is the first valid baseline. The earlier
prototype is excluded because its factory association differed.

## 2026-10-11 · Arithmetic, property, lambda and collection corpus

Same Apple M5 Max/Mac17,6, 18 logical CPUs, 128 GiB RAM, macOS 27.0.1 and Temurin
25+36-LTS as above. JMH 1.37, Expressly 6.0.0, Jakarta EL API 6.0.1, one thread,
reflection policy allow, named modules. Workstation load/frequency/thermal state remain
uncontrolled; these are exploratory measurements.

Fixtures share the same variable/context inputs for all engines:

| Fixture | Expression | Expected result |
|---|---|---|
| arithmetic | `${(a+2)*3}` | 27L |
| property | `${model.value}` | 7L |
| lambda | `${(x -> x+1)(a)}` | 8L |
| collection | `${[a,2,3]}` | [7L, 2L, 3L] |

a=7L and model has a public getValue() returning 7L. Peano's property path uses the
application's APT-generated table; Expressly uses the API BeanELResolver reflective
getter. The model package is exported only to jakarta.el for that baseline, with no opens.
Both use the same model. This intentionally compares generated vs reflective access,
not two reflective engines. Other fixtures have no model property access.

Each context stores its selected factory explicitly. Expression construction, source
selection, compilation and reflective constructor lookup stay outside evaluation timing.
Repeated creation still includes provider caches. Semantic checks run before timing.
The compiler and processor are absent from benchmark runtime dependencies.

Exact command (Java 25 environment above), from the checkout root:

```bash
./mvnw -ntp -Pbench,beans-compat clean package
./run-bench.sh -wi 2 -w 500ms -i 3 -r 500ms -f 2 -rf json -rff peano-bench/target/jmh-2026-10-11-corpus.json
```

Two forks, two 500ms warmups and three 500ms measurements per fork, six samples
per combination. Raw aggregate values (dot decimal; JMH score/error):

```text
benchmark | engine | fixture | score | error | unit
CreationBenchmark.create | peano | arithmetic | 306.893 | 6.745 | ns/op
CreationBenchmark.create | peano | property | 215.965 | 15.134 | ns/op
CreationBenchmark.create | peano | lambda | 489.720 | 9.685 | ns/op
CreationBenchmark.create | peano | collection | 364.113 | 5.567 | ns/op
CreationBenchmark.create | expressly | arithmetic | 25.525 | 1.095 | ns/op
CreationBenchmark.create | expressly | property | 24.068 | 0.183 | ns/op
CreationBenchmark.create | expressly | lambda | 30.798 | 0.510 | ns/op
CreationBenchmark.create | expressly | collection | 24.417 | 0.638 | ns/op
EvaluationBenchmark.evaluate | peano | arithmetic | 189.967 | 4.345 | ns/op
EvaluationBenchmark.evaluate | peano | property | 70.236 | 1.357 | ns/op
EvaluationBenchmark.evaluate | peano | lambda | 203.987 | 2.353 | ns/op
EvaluationBenchmark.evaluate | peano | collection | 71.554 | 3.807 | ns/op
EvaluationBenchmark.evaluate | expressly | arithmetic | 51.717 | 5.370 | ns/op
EvaluationBenchmark.evaluate | expressly | property | 83.406 | 3.695 | ns/op
EvaluationBenchmark.evaluate | expressly | lambda | 107.603 | 4.207 | ns/op
EvaluationBenchmark.evaluate | expressly | collection | 54.065 | 2.359 | ns/op
EvaluationBenchmark.evaluate | compiled | arithmetic | 164.142 | 1.677 | ns/op
EvaluationBenchmark.evaluate | compiled | property | 72.776 | 0.669 | ns/op
EvaluationBenchmark.evaluate | compiled | lambda | 155.380 | 8.539 | ns/op
EvaluationBenchmark.evaluate | compiled | collection | 74.178 | 7.488 | ns/op
```

[Complete raw JMH JSON](peano-bench/results/2026-10-11-corpus.json) includes JVM
arguments and every sample. On these inputs, Peano's generated property access is
faster than Expressly's reflective property access; Expressly is faster on the other
evaluated fixtures. Compiled evaluation improves Peano arithmetic/lambda evaluation,
without a demonstrated improvement for the property/collection fixtures.

Delta vs previous comparable corpus run: none; this is the first corpus baseline.
The earlier arithmetic-only run uses a different context/provider catalogue and a
longer measurement protocol, so its delta is not an implementation performance comparison.

## 2026-10-11 · OPT-00 long-protocol baseline

Baseline runtime: main `e5055a36d7041bd66b476c2ebd248267f4d8085c`, with the
measurement-only harness changes of `perf/optim-baseline`. Runtime code is unchanged.
Same Apple M5 Max/Mac17,6, 18 logical CPUs, 128 GiB RAM, macOS 27.0.1,
Temurin 25+36-LTS, Maven 3.9.16, JMH 1.37, Expressly 6.0.0 and EL API 6.0.1.
AC power, macOS power mode 0. Background workstation load and thermal/frequency
state are not controlled; source inspection occurred during the run, without local
builds or simultaneous benchmark runs. Treat these as workstation measurements.

Each fixture context now also binds decimal=BigDecimal("7.25") and data={value:7L}
for the optional expanded fixtures. The original a/model inputs are unchanged.
Compilation and expected-result checks precede evaluation timing. Runtime dependencies
remain named modules and exclude the compiler/processor. One benchmark thread,
three forks, five one-second warmups and eight one-second measurements per fork:
24 samples per combination. Errors below are JMH reported errors, not standard deviations.

Exact commands from the checkout with JAVA_HOME and PATH selecting Temurin 25:

```bash
rtk ./mvnw -ntp -Pbench,beans-compat clean package
rtk ./run-bench.sh 'io.vidocq.peano.bench.(CreationBenchmark.create|EvaluationBenchmark.evaluate)$' -wi 5 -w 1s -i 8 -r 1s -f 3 -rf json -rff peano-bench/target/optim-baseline.json
```

Raw scores, nanoseconds per operation:

```text
benchmark | engine | fixture | score | error | unit
+CreationBenchmark.create | peano | arithmetic | 328.230 | 5.489 | ns/op
CreationBenchmark.create | peano | property | 230.416 | 7.302 | ns/op
CreationBenchmark.create | peano | lambda | 506.053 | 9.804 | ns/op
CreationBenchmark.create | peano | collection | 378.966 | 2.476 | ns/op
CreationBenchmark.create | expressly | arithmetic | 26.727 | 0.293 | ns/op
CreationBenchmark.create | expressly | property | 25.109 | 0.151 | ns/op
CreationBenchmark.create | expressly | lambda | 31.983 | 0.434 | ns/op
CreationBenchmark.create | expressly | collection | 25.372 | 0.100 | ns/op
EvaluationBenchmark.evaluate | peano | arithmetic | 204.499 | 1.875 | ns/op
EvaluationBenchmark.evaluate | peano | property | 71.443 | 0.388 | ns/op
EvaluationBenchmark.evaluate | peano | lambda | 216.022 | 1.377 | ns/op
EvaluationBenchmark.evaluate | peano | collection | 72.012 | 0.430 | ns/op
EvaluationBenchmark.evaluate | expressly | arithmetic | 54.057 | 0.951 | ns/op
EvaluationBenchmark.evaluate | expressly | property | 88.762 | 0.744 | ns/op
EvaluationBenchmark.evaluate | expressly | lambda | 112.759 | 0.881 | ns/op
EvaluationBenchmark.evaluate | expressly | collection | 55.797 | 0.537 | ns/op
EvaluationBenchmark.evaluate | compiled | arithmetic | 256.296 | 49.055 | ns/op
EvaluationBenchmark.evaluate | compiled | property | 73.458 | 2.153 | ns/op
EvaluationBenchmark.evaluate | compiled | lambda | 158.033 | 2.926 | ns/op
EvaluationBenchmark.evaluate | compiled | collection | 89.325 | 8.304 | ns/op
```

[Raw JMH JSON](peano-bench/results/2026-10-11-optim-baseline.json) contains all
samples and JVM arguments. Delta vs previous comparable run: none. The prior corpus
used a shorter protocol and fewer fixture context bindings. This is the frozen
baseline for subsequent implementation comparisons; no improvement is claimed here.

### OPT-00 · CPU and allocation profiles

Same runtime and hardware. Separate diagnostic run: one fork, three one-second
warmups and five one-second measurements, JDK JFR profile configuration, stack depth
128. Its profiled timings are excluded from the unprofiled comparison baseline.

```bash
rtk ./run-bench.sh 'io.vidocq.peano.bench.(CreationBenchmark.create|EvaluationBenchmark.evaluate)$' -p engine=peano -p fixture=arithmetic,lambda -wi 3 -w 1s -i 5 -r 1s -f 1 -prof 'jfr:dir=peano-bench/target/optim-jfr;configName=profile;stackDepth=128' -rf json -rff peano-bench/target/optim-profile.json
```

[Raw profiler run JSON](peano-bench/results/2026-10-11-optim-profile.json) and
[filtered JFR recordings](peano-bench/results/2026-10-11-optim-jfr/) preserve the CPU
and allocation evidence. The original archive included sensitive environment metadata
(BENCH-004); the current files retain only ExecutionSample/ObjectAllocationSample
events. Raw recordings under target must remain local. Before archiving new profiles:

```bash
rtk ./scrub-bench-profiles.sh peano-bench/target/optim-jfr peano-bench/target/publishable-jfr
```

Archive only the filtered output. The output directory must be new for each invocation.
Bench-profile tests reject archived events outside this allowlist without printing
event values. The build-only benchmark module reads the JDK jdk.jfr module for that
validation; no published provider module gains a dependency.
For inspection, run `jfr print --json --stack-depth 12 --events
jdk.ExecutionSample,jdk.ObjectAllocationSample <recording>` with the pinned JDK.
Filter events to the JMH worker thread. Attribute CPU samples to their nearest Peano
implementation frame in the displayed stack; these counts are diagnostic samples,
not percentages of benchmark time or exact exclusive method costs.

| Workload | Worker CPU samples | Representative nearest implementation frames |
| --- | ---: | --- |
| Arithmetic creation | 408 | Lexer.scan 129; Parser.binary 72; Bindings.visit 32 |
| Lambda creation | 405 | Lexer.scan 101; Parser.binary 67; Bindings.visit 39 |
| Arithmetic evaluation | 372 | Arithmetic.number 142; PeanoValueExpression.getValue 86; Interpreter.literal 7 |
| Lambda evaluation | 434 | PeanoValueExpression.getValue 114; Arithmetic.number 76; Bindings.captureLambdaArguments 30 |

Allocation samples identify surviving Interpreter and Arithmetic objects during
arithmetic evaluation. Lambda evaluation allocation samples identify HashMap objects,
LambdaBody instances, map backing arrays and argument arrays. Sampled allocation
weights are not bytes/op; the separate GC-profiler run below supplies that metric.
Stack truncation/inlining and one diagnostic fork limit attribution precision.

These profiles support creation analysis reuse, removal of stateless arithmetic helper
allocation and reduction of empty lambda-capture bookkeeping as the first experiments.
Numeric literal parsing has little presence in this arithmetic profile, so preparing
all numeric tokens is deferred rather than expanding AST state before evidence of
a useful gain. Conversion hooks remain in place: their observed cost is not permission
to skip them. Delta vs previous comparable profile: none; these are initial profiles.

### OPT-00 · Expanded GC diagnostics

Same hardware/runtime; one thread, one fork, three one-second warmups and five
one-second measurements. Each GC-profiler workload has five samples. The short
single-fork timings are diagnostic and are not substituted for the long unprofiled
baseline. The normalized allocation metric is bytes per benchmark operation.

```bash
rtk ./run-bench.sh 'EvaluationBenchmark.evaluate$' -p engine=peano -p fixture=arithmetic,property,lambda,collection,arithmeticLarge,arithmeticDouble,arithmeticDecimal,nestedLambda,set,map,mapProperty -wi 3 -w 1s -i 5 -r 1s -f 1 -prof gc -rf json -rff peano-bench/target/optim-evaluation-gc-baseline.json
rtk ./run-bench.sh 'io.vidocq.peano.bench.CreationBenchmark.create$' -p engine=peano -p fixture=arithmetic,lambda -wi 3 -w 1s -i 5 -r 1s -f 1 -prof gc -rf json -rff peano-bench/target/optim-creation-gc-baseline.json
rtk ./run-bench.sh 'SourceCreationBenchmark.(create|sourceGeneration)$' -p engine=peano -wi 3 -w 1s -i 5 -r 1s -f 1 -prof gc -rf json -rff peano-bench/target/optim-source-gc-baseline.json
rtk ./run-bench.sh 'StartupBenchmark.*' -p engine=peano -wi 3 -w 1s -i 5 -r 1s -f 1 -prof gc -rf json -rff peano-bench/target/optim-startup-gc-baseline.json
```

Raw aggregate scores and allocations:

```text
benchmark | fixture/workload | ns/op | error ns/op | B/op
+EvaluationBenchmark.evaluate | arithmetic | 208.088 | 24.291 | 56.001
EvaluationBenchmark.evaluate | property | 73.003 | 3.917 | 24.001
EvaluationBenchmark.evaluate | lambda | 212.831 | 5.355 | 752.001
EvaluationBenchmark.evaluate | collection | 72.675 | 1.479 | 80.001
EvaluationBenchmark.evaluate | arithmeticLarge | 222.222 | 7.001 | 152.002
EvaluationBenchmark.evaluate | arithmeticDouble | 214.187 | 9.214 | 232.001
EvaluationBenchmark.evaluate | arithmeticDecimal | 258.461 | 43.367 | 304.002
EvaluationBenchmark.evaluate | nestedLambda | 426.689 | 7.043 | 2184.003
EvaluationBenchmark.evaluate | set | 470.059 | 5.638 | 1832.003
EvaluationBenchmark.evaluate | map | 1379.183 | 55.407 | 1864.010
EvaluationBenchmark.evaluate | mapProperty | 78.046 | 5.846 | 24.001
CreationBenchmark.create | arithmetic | 328.927 | 25.030 | 2248.002
CreationBenchmark.create | lambda | 492.056 | 12.301 | 3464.003
SourceCreationBenchmark.create | repeated | 217.012 | 2.434 | 1752.002
SourceCreationBenchmark.create | rotating | 209.296 | 2.256 | 1752.001
SourceCreationBenchmark.create | unique | 219.934 | 5.590 | 1816.002
SourceCreationBenchmark.sourceGeneration | repeated | 1.269 | 0.019 | 0.000
SourceCreationBenchmark.sourceGeneration | rotating | 1.410 | 0.010 | 0.000
SourceCreationBenchmark.sourceGeneration | unique | 7.077 | 0.480 | 56.000
StartupBenchmark.createContext | - | 1280.388 | 13.682 | 3504.009
StartupBenchmark.createFactory | - | 420.443 | 12.750 | 960.003
StartupBenchmark.firstEvaluation | - | 2457.806 | 49.940 | 7432.017
```

Raw JMH data, including samples and secondary-metric errors:
[evaluation](peano-bench/results/2026-10-11-optim-evaluation-gc-baseline.json),
[creation](peano-bench/results/2026-10-11-optim-creation-gc-baseline.json),
[source working sets](peano-bench/results/2026-10-11-optim-source-gc-baseline.json),
[setup](peano-bench/results/2026-10-11-optim-startup-gc-baseline.json).

Unique creation includes source construction. The separate sourceGeneration result
is a control and is not subtracted from creation. Setup workloads include fixture
binding/provider initialization as documented in the harness; they are not process
startup measurements. Expected-result checks for all expanded evaluation fixtures
passed before these runs. Delta vs previous comparable expanded GC run: none.

### OPT-00 · Creation components

Same hardware/runtime and GC diagnostic protocol: one fork, three one-second warmups
and five one-second measurements. Parsing is uncached; other components operate on
a retained parsed Template. Variable capture sees a=7L; the function mapper is empty.
These independent benchmark scores cannot be summed to predict full creation time.

The build-only diagnostic sources are patched into core for this command, with reads
and qualified exports to JMH only. Shipped descriptors and artifacts are unchanged.
The first attempted fork failed before measurement (BENCH-003) and is excluded.

```bash
rtk ./run-analysis-bench.sh -wi 3 -w 1s -i 5 -r 1s -f 1 -prof gc -rf json -rff peano-bench/target/optim-components-baseline.json
```

```text
component | fixture | ns/op | error ns/op | B/op
+captureFunctions | arithmetic | 12.773 | 0.169 | 112.000
captureFunctions | lambda | 29.127 | 0.663 | 320.000
captureVariables | arithmetic | 35.287 | 6.894 | 408.000
captureVariables | lambda | 52.269 | 3.430 | 616.000
identity | arithmetic | 54.394 | 1.938 | 528.000
identity | lambda | 78.027 | 6.567 | 752.001
parse | arithmetic | 211.044 | 21.591 | 1152.001
parse | lambda | 338.280 | 38.007 | 1728.002
```

[Raw component JSON](peano-bench/results/2026-10-11-optim-components-baseline.json)
records samples and diagnostic JVM flags. Delta vs previous comparable component
run: none. Baseline parsing/normalization/capture all allocate, confirming that a
full repeated-source creation comparison is needed for reuse experiments.

## 2026-10-11 · OPT-02 creation analysis experiment

Runtime branch perf/creation-analysis after OPT-00. One pure traversal collects
ordered binding descriptors, with separate context lookup phases and direct immutable
single-variable snapshots. No cache is active. Same hardware, inputs and GC protocol
as the OPT-00 creation diagnostics: one fork, three one-second warmups, five one-second
measurements, one thread. Full bench/beans-compatible reactor passed before measurement.

```bash
rtk ./mvnw -ntp -Pbench,beans-compat package
rtk ./run-bench.sh 'io.vidocq.peano.bench.CreationBenchmark.create$' -p engine=peano -p fixture=arithmetic,lambda -wi 3 -w 1s -i 5 -r 1s -f 1 -prof gc -rf json -rff peano-bench/target/optim-analysis-gc.json
```

```text
fixture | ns/op | error ns/op | B/op | time delta vs OPT-00 GC | allocation delta B/op
+arithmetic | 306.567 | 3.093 | 2304.002 | -6.8% | 56.000
lambda | 496.339 | 3.395 | 3256.003 | 0.9% | -208.000
```

[Raw experiment JSON](peano-bench/results/2026-10-11-optim-analysis-gc.json).
The single-fork timing deltas are exploratory; allocation changes are compared under
the same protocol. The final long-protocol comparison remains required. These results
also define the uncached reference when assessing the added reuse layer.

## 2026-10-11 · OPT-02/03 factory-owned syntax cache

Branch perf/expression-cache, runtime source includes the analyzed descriptor layer
and bounded reuse, before arithmetic/capture changes. Same hardware, JVM, factory/context
inputs and measurement protocols as OPT-00. Full bench/beans-compatible clean install
and the 361-case official TCK passed before timing. Concurrent/module review passed.

Engineering bounds: 128 direct-mapped slots, sources at most 4096 characters and
estimated retained artifact weight at most 16KiB per entry. These bound estimated
retention, not exact heap bytes or transient concurrent parsing. There is no LRU claim.
Oversized artifacts bypass storage. Nonempty literal values are owned by each expression;
mapper snapshots and expected types are outside the cache. No AST or emitted-schema
change is needed.

```bash
rtk ./mvnw -ntp -Pbench,beans-compat clean install
rtk ./run-official-tck-el-6.0.sh all
rtk ./run-bench.sh 'io.vidocq.peano.bench.CreationBenchmark.create$' -p engine=peano -wi 5 -w 1s -i 8 -r 1s -f 3 -rf json -rff peano-bench/target/optim-cache-creation.json
rtk ./run-bench.sh 'io.vidocq.peano.bench.CreationBenchmark.create$' -p engine=peano -p fixture=arithmetic,lambda -wi 3 -w 1s -i 5 -r 1s -f 1 -prof gc -rf json -rff peano-bench/target/optim-cache-creation-gc.json
rtk ./run-bench.sh 'SourceCreationBenchmark.(create|sourceGeneration)$' -p engine=peano -wi 3 -w 1s -i 5 -r 1s -f 1 -prof gc -rf json -rff peano-bench/target/optim-cache-source-gc.json
rtk ./run-bench.sh 'StartupBenchmark.*' -p engine=peano -wi 3 -w 1s -i 5 -r 1s -f 1 -prof gc -rf json -rff peano-bench/target/optim-cache-startup-gc.json
```

Long-protocol creation: three forks, 24 samples per fixture, unprofiled. Delta against
the matching OPT-00 long-protocol creation result:

```text
fixture | ns/op | error ns/op | OPT-00 ns/op | time delta
+arithmetic | 9.355 | 0.035 | 328.230 | -97.1%
property | 31.411 | 0.108 | 230.416 | -86.4%
lambda | 9.341 | 0.035 | 506.053 | -98.2%
collection | 9.336 | 0.015 | 378.966 | -97.5%
```

GC diagnostics use the same one-fork protocol as the corresponding OPT-00 run:

```text
benchmark | fixture/workload | ns/op | error ns/op | B/op | time delta vs OPT-00 GC | allocation delta B/op
+CreationBenchmark.create | arithmetic | 9.319 | 0.017 | 80.000 | -97.2% | -2168.002
CreationBenchmark.create | lambda | 9.332 | 0.067 | 80.000 | -98.1% | -3384.003
SourceCreationBenchmark.create | repeated | 10.822 | 0.112 | 80.000 | -95.0% | -1672.001
SourceCreationBenchmark.create | rotating | 60.438 | 8.115 | 456.250 | -71.1% | -1295.751
SourceCreationBenchmark.create | unique | 254.121 | 3.324 | 1864.002 | 15.5% | 48.000
SourceCreationBenchmark.sourceGeneration | repeated | 1.231 | 0.004 | 0.000 | -3.0% | -0.000
SourceCreationBenchmark.sourceGeneration | rotating | 1.376 | 0.025 | 0.000 | -2.5% | -0.000
SourceCreationBenchmark.sourceGeneration | unique | 6.923 | 0.409 | 56.000 | -2.2% | -0.000
StartupBenchmark.createContext | - | 1269.405 | 48.076 | 3504.009 | -0.9% | -0.000
StartupBenchmark.createFactory | - | 399.766 | 6.076 | 1056.003 | -4.9% | 96.000
StartupBenchmark.firstEvaluation | - | 2387.058 | 95.778 | 7688.017 | -2.9% | 255.999
```

Raw data: [long creation](peano-bench/results/2026-10-11-optim-cache-creation.json),
[creation GC](peano-bench/results/2026-10-11-optim-cache-creation-gc.json),
[source working sets](peano-bench/results/2026-10-11-optim-cache-source-gc.json),
[setup](peano-bench/results/2026-10-11-optim-cache-startup-gc.json).
The cache targets repeated creation, while unique misses include analysis/storage
overhead. Rotating sources also exercise collision eviction. Setup and miss penalties
must be assessed alongside the warm results; diagnostic timings have one-fork uncertainty.
The final long corpus will verify the combined retained changes.

## 2026-10-11 · OPT-04 stateless arithmetic helpers

Branch perf/stateless-arithmetic atop the accepted creation cache. Numeric operations
now pass ELContext directly through static helpers. Operand evaluation, conversion
calls, promotion and exception handling remain unchanged. Before the refactor, the
OPT-00 allocation profile identified surviving Arithmetic instances; eliminating
that allocation is this experiment's acceptance criterion. New compatible-type,
domain/order and failure tests passed before and after the refactor.

Same hardware/JVM/context and one-fork GC protocol as the expanded OPT-00 evaluation
run. The benchmark module now resolves JDK jdk.jfr for archive validation; the profile
is not active in this GC run. The code change is assessed primarily on allocation;
short diagnostic timing deltas are not a final throughput claim.

```bash
rtk ./mvnw -ntp -Pbench,beans-compat package
rtk ./run-bench.sh 'EvaluationBenchmark.evaluate$' -p engine=peano -p fixture=arithmetic,property,lambda,collection,arithmeticLarge,arithmeticDouble,arithmeticDecimal,nestedLambda,set,map,mapProperty -wi 3 -w 1s -i 5 -r 1s -f 1 -prof gc -rf json -rff peano-bench/target/optim-arithmetic-gc.json
```

```text
fixture | ns/op | error ns/op | B/op | time delta vs OPT-00 GC | allocation delta B/op
+arithmetic | 185.329 | 4.501 | 24.001 | -10.9% | -32.000
property | 71.067 | 5.526 | 24.000 | -2.7% | -0.000
lambda | 213.299 | 41.373 | 736.001 | 0.2% | -16.000
collection | 74.778 | 13.166 | 80.001 | 2.9% | 0.000
arithmeticLarge | 212.759 | 39.123 | 120.001 | -4.3% | -32.000
arithmeticDouble | 199.058 | 11.667 | 200.001 | -7.1% | -32.000
arithmeticDecimal | 238.594 | 10.337 | 272.002 | -7.7% | -32.000
nestedLambda | 413.803 | 15.348 | 2168.003 | -3.0% | -16.000
set | 457.619 | 5.144 | 1832.003 | -2.6% | -0.000
map | 1353.115 | 56.463 | 1864.009 | -1.9% | -0.000
mapProperty | 82.386 | 19.642 | 24.001 | 5.6% | 0.000
```

[Raw GC results](peano-bench/results/2026-10-11-optim-arithmetic-gc.json). The
arithmetic fixture removes the two helper allocations while retaining its evaluator
object. Other allocation deltas depend on operations and JIT escape analysis. General
numeric values and custom conversion effects remain covered by the semantic tests;
the final long corpus checks the combined evaluation timing.

## 2026-10-11 · OPT-05 empty capture bookkeeping

Branch perf/lambda-capture, after stateless arithmetic. Interpreted capture now uses
an immutable unused-variable map, immutable parameter membership and a capture map
allocated only after a lexical argument is found. Compiled helpers likewise avoid
empty mutable-map/wrapper allocation. Nonempty nullable snapshots, duplicate-name
suppression, lexical scope entry/exit and generated helper ABI remain unchanged.

The OPT-00 profile identified capture bookkeeping allocation as the performance red
criterion. New scope/callback/null/failure tests passed before and after this change;
the full bench/beans-compatible reactor and codegen review passed. Same hardware,
context and one-fork GC protocol. Interpreted deltas use OPT-04; compiled deltas use
the newly recorded pre-change compiled baseline under the same protocol.

```bash
rtk ./run-bench.sh 'EvaluationBenchmark.evaluate$' -p engine=compiled -p fixture=lambda,nestedLambda -wi 3 -w 1s -i 5 -r 1s -f 1 -prof gc -rf json -rff peano-bench/target/optim-lambda-compiled-before.json
rtk ./mvnw -ntp -Pbench,beans-compat package
rtk ./run-bench.sh 'EvaluationBenchmark.evaluate$' -p engine=peano,compiled -p fixture=lambda,nestedLambda -wi 3 -w 1s -i 5 -r 1s -f 1 -prof gc -rf json -rff peano-bench/target/optim-lambda-gc.json
```

```text
engine | fixture | ns/op | error ns/op | B/op | time delta vs previous matching GC | allocation delta B/op
+peano | lambda | 219.142 | 11.780 | 640.002 | 2.7% | -96.000
peano | nestedLambda | 426.158 | 48.050 | 2064.003 | 3.0% | -104.000
compiled | lambda | 155.718 | 14.494 | 160.001 | 4.0% | -80.000
compiled | nestedLambda | 290.022 | 17.905 | 832.002 | 4.4% | -80.000
```

[Compiled before-change data](peano-bench/results/2026-10-11-optim-lambda-compiled-before.json),
[after-change data](peano-bench/results/2026-10-11-optim-lambda-gc.json).
The allocation reduction satisfies this experiment's target; short profiled timing
deltas remain diagnostic. Fresh LambdaExpression instances, call arguments and scope
state are not pooled. Free-variable metadata precomputation and generated-name-array
reuse remain deferred until profiles of the retained change justify their complexity.

### OPT-05 · Refined small-parameter membership

The initial Set.copyOf prototype still built membership storage for every capture.
Reuse the immutable parameter list for zero, one or two parameters; retain hash-based
membership for larger lists and nested scopes. Lookup order, duplicate suppression and
shadowing are unchanged. Same GC protocol and workload as the previous OPT-05 run:

```bash
rtk ./mvnw -ntp -Pbench,beans-compat package
rtk ./run-bench.sh 'EvaluationBenchmark.evaluate$' -p engine=peano,compiled -p fixture=lambda,nestedLambda -wi 3 -w 1s -i 5 -r 1s -f 1 -prof gc -rf json -rff peano-bench/target/optim-lambda-gc-refined.json
```

```text
engine | fixture | ns/op | error ns/op | B/op | time delta vs initial OPT-05 | allocation delta B/op
+peano | lambda | 185.349 | 4.822 | 432.001 | -15.4% | -208.000
peano | nestedLambda | 366.577 | 12.870 | 1496.003 | -14.0% | -568.000
compiled | lambda | 146.611 | 1.447 | 160.001 | -5.8% | -0.000
compiled | nestedLambda | 278.040 | 14.879 | 832.002 | -4.1% | -0.000
```

[Initial membership prototype](peano-bench/results/2026-10-11-optim-lambda-membership-prototype.json),
[refined data](peano-bench/results/2026-10-11-optim-lambda-gc-refined.json).
Only the refined implementation is retained. Timing remains diagnostic; the final
long corpus validates the complete combination. API-004's pre-existing null-shadowing
limitation is tracked separately and excluded from optimization claims.
