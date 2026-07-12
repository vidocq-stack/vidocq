# humboldt-otel-context

Repackaging of `io.opentelemetry:opentelemetry-context` with an explicit `module-info.class` (`io.opentelemetry.context`) for JPMS/jlink usage.

## Quick verification

```zsh
cd /Users/antoine/dev/vidocq/humboldt
./mvnw -ntp -pl humboldt-otel-context clean verify
jar --describe-module --file humboldt-otel-context/target/humboldt-otel-context-*.jar
```

