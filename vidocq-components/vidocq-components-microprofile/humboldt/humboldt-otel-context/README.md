# humboldt-otel-context

Repackaging of `io.opentelemetry:opentelemetry-context` with an explicit `module-info.class` (`io.opentelemetry.context`) for Java Modules/jlink usage.

`opentelemetry-common` is shaded in as well, with one class replaced: Humboldt ships its own
`io.opentelemetry.common.ServiceLoaderComponentLoader` (in `src/main/java`; the upstream class is excluded from
the shade). It is the default `ComponentLoader` that OpenTelemetry components use when given no other one. It
keeps the upstream contract, but adds the `uses` of the requested service to `io.opentelemetry.context`
(`Module.addUses`) before calling `ServiceLoader.load`, so that the lookups of optional OpenTelemetry artifacts
(an OTLP exporter's `Compressor` or `HttpSenderProvider`) work on the module path (BUG-20261004-04).

## Quick verification

```zsh
cd /Users/antoine/dev/vidocq/humboldt
./mvnw -ntp -pl humboldt-otel-context clean verify
jar --describe-module --file humboldt-otel-context/target/humboldt-otel-context-*.jar
```

