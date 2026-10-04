# humboldt-otel-api

Repackaging of `io.opentelemetry:opentelemetry-api` with an explicit `module-info.class` (`io.opentelemetry.api`) for Java Modules/jlink usage.

The descriptor (`src/main/moditect/module-info.java`) exports the public API packages, plus three packages that
are not API — `io.opentelemetry.api.internal`, `io.opentelemetry.api.impl` and
`io.opentelemetry.api.trace.propagation.internal` — each to the OpenTelemetry 1.66 stable SDK, exporter and
sender modules whose classes use it (found with `jdeps`; BUG-20261004-03). Upstream the API jar is an automatic
module that exports every package. Re-check the lists with `jdeps` on each OpenTelemetry upgrade.

A qualified export reaches only target modules of the same module layer or of a parent layer. For OpenTelemetry
jars in a child layer, `src/main/java` holds `io.vidocq.humboldt.otel.api.layer.ApiLayerExports`: it adds, at run
time and from inside this module, the exports the descriptor names. humboldt-otel-interop calls it for the layer of
each OpenTelemetry provider it discovers; an OpenTelemetry component that the application builds itself, outside
that discovery, does not get them.

An artifact that is not in the lists (an incubating `-alpha` one, for instance) must be granted the package with
`--add-exports io.opentelemetry.api/<package>=<module>`. That option applies to the boot layer only.

## Quick verification

```zsh
cd /Users/antoine/dev/vidocq/humboldt
./mvnw -ntp -pl humboldt-otel-api clean verify
jar --describe-module --file humboldt-otel-api/target/humboldt-otel-api-*.jar
```

