# dirac-bench

Benchmarks JMH pour M4 (Timer) :

- overhead appel CDI baseline vs `@Counted` vs `@Timed`
- enregistrement `Counter` et `Timer` sous 1000 virtual threads

## Lancer

```bash
./mvnw -ntp -pl dirac-bench package
java -jar dirac-bench/target/benchmarks.jar M4TimerBenchmarks
```

## Exemple de filtre

```bash
java -jar dirac-bench/target/benchmarks.jar M4TimerBenchmarks.timedCdiCall
```

