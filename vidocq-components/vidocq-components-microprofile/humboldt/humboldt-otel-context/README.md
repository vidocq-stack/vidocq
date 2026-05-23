# humboldt-otel-context

Repackage de `io.opentelemetry:opentelemetry-context` avec un `module-info.class` explicite (`io.opentelemetry.context`) pour usage JPMS/jlink.

## Vérification rapide

```zsh
cd /Users/antoine/dev/vidocq/humboldt
./mvnw -ntp -pl humboldt-otel-context clean verify
jar --describe-module --file humboldt-otel-context/target/humboldt-otel-context-0.1.0-SNAPSHOT.jar
```

