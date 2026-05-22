# humboldt-otel-api

Repackage de `io.opentelemetry:opentelemetry-api` avec un `module-info.class` explicite (`io.opentelemetry.api`) pour usage JPMS/jlink.

## Vérification rapide

```zsh
cd /Users/antoine/dev/vidocq/humboldt
./mvnw -ntp -pl humboldt-otel-api clean verify
jar --describe-module --file humboldt-otel-api/target/humboldt-otel-api-0.1.0-SNAPSHOT.jar
```

