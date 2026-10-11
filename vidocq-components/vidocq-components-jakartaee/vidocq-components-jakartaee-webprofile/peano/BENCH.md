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
