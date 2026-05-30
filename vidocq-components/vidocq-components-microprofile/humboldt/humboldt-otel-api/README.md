# humboldt-otel-api

Repackaging of `io.opentelemetry:opentelemetry-api` with an explicit `module-info.class` (`io.opentelemetry.api`) for JPMS/jlink usage.

## Quick verification

```zsh
cd /Users/antoine/dev/vidocq/humboldt
./mvnw -ntp -pl humboldt-otel-api clean verify
jar --describe-module --file humboldt-otel-api/target/humboldt-otel-api-0.1.0-SNAPSHOT.jar
```

