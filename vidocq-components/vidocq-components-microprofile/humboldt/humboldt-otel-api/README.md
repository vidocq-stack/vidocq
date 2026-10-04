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

An artifact that is not in the lists (an incubating `-alpha` one, for instance) must be granted the packages it uses
with `--add-exports <module>/<package>=<consumer>`, where `<module>` is the Humboldt module that holds the package:
`io.opentelemetry.api` for the `io.opentelemetry.api.*` packages, and `io.opentelemetry.context`
(`humboldt-otel-context`) for `io.opentelemetry.context.internal.shaded` and `io.opentelemetry.common.impl`
(`opentelemetry-common` is shaded into it). For `opentelemetry-sdk-extension-incubator` and
`opentelemetry-api-incubator` 1.66.0-alpha that means, besides any `io.opentelemetry.api` package the jar needs:
`--add-exports io.opentelemetry.context/io.opentelemetry.context.internal.shaded=io.opentelemetry.sdk.extension.incubator`
and `--add-exports io.opentelemetry.context/io.opentelemetry.common.impl=io.opentelemetry.api.incubator`.
That option applies to the boot layer only.

## Quick verification

```zsh
cd /Users/antoine/dev/vidocq/humboldt
./mvnw -ntp -pl humboldt-otel-api clean verify
jar --describe-module --file humboldt-otel-api/target/humboldt-otel-api-*.jar
```

