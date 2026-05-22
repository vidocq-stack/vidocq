# humboldt-otel-instrumentation-annotations

Repackage de `io.opentelemetry.instrumentation:opentelemetry-instrumentation-annotations` avec un `module-info.class` explicite (`io.opentelemetry.instrumentation_annotations`) pour usage JPMS/jlink.

## Vérification rapide

```zsh
cd /Users/antoine/dev/vidocq/humboldt
./mvnw -ntp -pl humboldt-otel-instrumentation-annotations clean verify
jar --describe-module --file humboldt-otel-instrumentation-annotations/target/humboldt-otel-instrumentation-annotations-0.1.0-SNAPSHOT.jar
```

