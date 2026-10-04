# humboldt-otel-api

Repackaging of `io.opentelemetry:opentelemetry-api` with an explicit `module-info.class` (`io.opentelemetry.api`) for Java Modules/jlink usage.

The descriptor (`src/main/moditect/module-info.java`) exports the public API packages, plus
`io.opentelemetry.api.internal` to a fixed list of OpenTelemetry SDK and exporter modules only: the 1.66 stable
artifacts that use it across jars (BUG-20261004-03). Upstream the API jar is an automatic module that exports
every package. An incubating (`-alpha`) artifact that needs the package must be opened with
`--add-exports io.opentelemetry.api/io.opentelemetry.api.internal=<module>`. Re-check the list with `jdeps`
on each OpenTelemetry upgrade.

## Quick verification

```zsh
cd /Users/antoine/dev/vidocq/humboldt
./mvnw -ntp -pl humboldt-otel-api clean verify
jar --describe-module --file humboldt-otel-api/target/humboldt-otel-api-*.jar
```

