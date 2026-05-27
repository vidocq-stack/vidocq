# grimm-bench

JMH benchmarks for Grimm (M11).

## Included benchmarks

- `io.vidocq.grimm.bench.OpenApiPipelineBenchmark`
  - model build throughput
  - cached JSON render throughput
  - cached YAML render throughput
- `io.vidocq.grimm.bench.SerializationComparisonBenchmark`
  - Grimm JSON/YAML serialization throughput
  - SmallRye JSON/YAML serialization throughput on an equivalent parsed model

## Build and run

```bash
./mvnw -pl grimm-bench -Pbench -DskipTests package
java -jar grimm-bench/target/benchmarks.jar OpenApiPipelineBenchmark
java -jar grimm-bench/target/benchmarks.jar SerializationComparisonBenchmark
```

Quick smoke profile:

```bash
java -jar grimm-bench/target/benchmarks.jar OpenApiPipelineBenchmark -wi 1 -i 1 -f 1 -t 1
java -jar grimm-bench/target/benchmarks.jar SerializationComparisonBenchmark -wi 1 -i 1 -f 1 -t 1
```
