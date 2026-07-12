# humboldt-otel-instrumentation-annotations

Repackaging of `io.opentelemetry.instrumentation:opentelemetry-instrumentation-annotations` with an explicit `module-info.class` (`io.opentelemetry.instrumentation_annotations`) for JPMS/jlink usage.

## Quick verification

```zsh
cd /Users/antoine/dev/vidocq/humboldt
./mvnw -ntp -pl humboldt-otel-instrumentation-annotations clean verify
jar --describe-module --file humboldt-otel-instrumentation-annotations/target/humboldt-otel-instrumentation-annotations-*.jar
```

