# Humboldt — Session lessons

> Any user correction or non-trivial validation must be recorded here. Modeled after `chappe/tasks/lessons.md`.

## M2 — SDK Trace (2026-05-20)

### Locale.ROOT for format strings

`String.format("%.6f", 0.42)` returns `"0,420000"` in a French environment.
The `description()` methods of Sampler / IdGenerator / etc. **must** use
`String.format(Locale.ROOT, ...)` to guarantee a portable decimal point —
otherwise the `contains("0.42")` tests fail in CI depending on the runner locale.

### InMemorySpanExporter.shutdown() does not clear

Humboldt convention: `shutdown()` stops export but **preserves** the
already collected spans, to allow inspection after `provider.close()`
in try-with-resources tests. Use `reset()` to clear them
explicitly. This differs from the reference OTel impl, which clears on shutdown.

### OTel 1.39 SpanBuilder — 4 abstract primitive setAttribute methods

In OpenTelemetry API 1.39.0, `SpanBuilder.setAttribute(String, boolean)`
is ABSTRACT (and likely the other 3 primitives as well). The 4 must be
overridden by delegating to `setAttribute(AttributeKey.{string|long|double|boolean}Key(key), value)`.
Same for `TracerProvider.get(String name, String version)`, which must
be implemented (the "cache by name only" simplification is OK for M2).

## M3 — OTLP Exporter (2026-05-20)

### `requires static jdk.httpserver` for E2E tests

In a JPMS module with `module-info.java`, Maven 4 + Surefire 3.5 tests
are compiled on the MODULEPATH (not the classpath). A test that uses
`com.sun.net.httpserver.HttpServer` (the pure JDK HttpServer, perfect for
in-process fake servers) must therefore see the `jdk.httpserver` module.

Clean solution: `requires static jdk.httpserver;` in the main module-info.
`static` = compile-time only, it does not appear in the runtime
graph. No external dep added (the module is in the JDK).

Alternative considered (rejected): separate test module-info, argLine
`--add-modules` on the surefire side — more complex for zero gain.

### W3C propagators are already concrete in the OTel API

`W3CTraceContextPropagator.getInstance()` and `W3CBaggagePropagator.getInstance()`
are concrete classes in `opentelemetry-api` (not just interfaces).
So `humboldt-propagator-w3c` does not reimplement them — it only provides the
canonical composition `ContextPropagators.create(TextMapPropagator.composite(...))`.

## M4 — SDK Metric (2026-05-20)

### `mvn clean` is mandatory after moving a package

Symptom: `LayerInstantiationException: Package X in both module A and module B`
when starting the test JVM. Cause: an orphan `.class` remains in `target/`
after moving the `.java` to another module (or changing its
`package`). The built JAR contains both the class at the new
location AND the leftover class at the old one — JPMS detects the duplicate
package and refuses to mount the layer.

Always run `mvn clean install` after moving a class between
modules or changing a package declaration.

### Cross-SDK coupling must be avoided — place shared building blocks in sdk-common

`SpanData`, `MetricData`, `LogRecordData` share the same building blocks:
`Resource`, `InstrumentationScope`, `CompletableResultCode`. If they are left
in the first SDK that creates them (humboldt-sdk-trace), the other SDKs
eventually have to `requires` that module — which needlessly couples
metric/log to trace.

Rule: any type shared by 2+ SDKs must live in `humboldt-sdk-common`.
Migration applied in M4 for InstrumentationScope (trace → common) and
CompletableResultCode (trace → common).

### OpenTelemetry API 1.39 — MeterBuilder.setInstrumentationAttributes absent

Contrary to what one might think, `MeterBuilder` in OTel 1.39 does
NOT have `setInstrumentationAttributes(Attributes)`. Abstract methods = only
`setInstrumentationVersion(String)`, `setSchemaUrl(String)` and `build()`.
Always verify overrides through the compiler — each OTel version has
subtle differences in what is default vs abstract on builders.

## M6b — humboldt-rest (2026-05-21)

### `java.lang.reflect.Proxy` to mock a JAX-RS API without Mockito

The JAX-RS 4.0 `ContainerRequestContext` and `UriInfo` interfaces each have
~40-50 abstract methods (and the surface changes between versions:
`MatchedResource` added in 4.0, `getMatchedResourceTemplate()` new,
etc.). Implementing these interfaces by hand in a test = huge,
fragile boilerplate on every JAX-RS upgrade.

Humboldt solution: `java.lang.reflect.Proxy.newProxyInstance` + switch on
`method.getName()` to route only the ~6 methods actually
consumed by the filter, with `defaultForReturnType(m)` returning
safe values (`null`, `false`, `0`, `List.of()`, `Map.of()`,
`MultivaluedHashMap`) for everything else.

Advantage: robust against upstream additions of abstract methods — no need
to patch the test on every JAX-RS release. No Mockito dependency.

Pattern to reuse for `ContainerResponseContext`, `SecurityContext`,
`Application`, etc. when testing M6c/M7.

### `UriInfo.getPath()` does NOT contain the leading '/' — normalize it

According to the JAX-RS spec, `UriInfo.getPath()` returns the path **relative to the base URI**,
WITHOUT a leading '/'. But the OTel HTTP semantic conventions require
`url.path` WITH a leading '/'.

Fix in `HumboldtServerRequestFilter`:
`path = raw.startsWith("/") ? raw : "/" + raw` **before** setting it on
the attribute AND on the span name. Otherwise tests fail with
`expected: </users/42> but was: <users/42>`.

## Refactor — shared OtlpHttpJsonSender (2026-05-21)

### A 3-copy pattern = signal for refactor

When a pattern is duplicated in 3+ classes (M3 Span, M4 Metric, M5 Log
exporters shared nearly identical HttpClient + retry + headers + computeBackoffMillis),
it is the right time to extract a common utility.

Humboldt solution: internal `OtlpHttpJsonSender` (package `.internal.`) that
encapsulates HttpClient + endpoint + headers + timeouts + retry. The 3 exporters
become ~85 lines each (vs ~170 before) and only delegate the encoding.

Measured benefit: -255 lines of code (3 × -85), a single place to modify
for future retry/transport/protocol switching (M3b: move to chappe-client,
add OTEL_EXPORTER_OTLP_TIMEOUT/PROTOCOL in M7).

Public API unchanged — the `OtlpHttpXxxExporter.builder()` builders
keep exactly the same signature. E2E tests pass without modification
(109/109 still green).

### Backward compatibility with static wrapper

`OtlpHttpSpanExporter.computeBackoffMillis(int)` public static was used
by the 2 other exporters AND by an E2E test. Instead of breaking
backward compatibility, the method is kept and delegates to
`OtlpHttpJsonSender.computeBackoffMillis()`. Cost: 3 lines. Benefit:
no modification of external callers (and the E2E test keeps
working without a patch).

## Fix M6a — Adopt public OTel @WithSpan API + BCE (2026-05-21)

### M6a design error corrected

In M6a, I created `io.vidocq.humboldt.cdi.WithSpan` (custom annotation) by
misunderstanding the scope of the "zero-dep" principle. The correct rule is:
**public API/SPI OK, runtime impl not OK**.

The `io.opentelemetry.instrumentation.annotations.WithSpan` annotation is in
`opentelemetry-instrumentation-annotations` — a JAR that contains ONLY
marker annotations (`@WithSpan`, `@SpanAttribute`, etc.), no runtime
implementation. That is exactly the kind of dep we accept (like
`opentelemetry-api`, `opentelemetry-semconv`).

Immediate bonus: automatic alignment with the MicroProfile Telemetry
2.1 TCK, which expects this official annotation.

### CDI 4.x BuildCompatibleExtension to enable interception

Challenge: OTel `@WithSpan` **is not** an `@InterceptorBinding` (and we
cannot modify the third-party annotation). To make the CDI interceptor work
without asking the user to write 2 annotations, we use a
`BuildCompatibleExtension` (CDI 4.x Lite + Full unified):

```java
@Enhancement(types = Object.class, withSubtypes = true,
             withAnnotations = WithSpan.class)
public void addSpanBinding(ClassConfig classConfig) {
    if (classConfig.info().hasAnnotation(WithSpan.class)) {
        classConfig.addAnnotation(SpanBinding.class);
    }
    for (MethodConfig m : classConfig.methods()) {
        if (m.info().hasAnnotation(WithSpan.class)) {
            m.addAnnotation(SpanBinding.class);
        }
    }
}
```

Advantages of BCE vs portable Extension:
- Compatible with CDI 4.1 **Lite** (Vauban) AND CDI 4.1 **Full** (Weld 5+) — a
  single shared mechanism
- Build-time = no runtime cost
- Standard CDI 4.x API (vs Quarkus-specific)

Discovery: `META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension`
+ JPMS `provides` in module-info.

### Automatic-Module-Name with underscore

`opentelemetry-instrumentation-annotations:2.7.0` has `Automatic-Module-Name:
io.opentelemetry.instrumentation_annotations` — **with an underscore** in the
middle, not a dot like the usual convention. Cryptic
"module not found" error if you write `io.opentelemetry.instrumentation.annotations`.

Always run `unzip -p .../foo.jar META-INF/MANIFEST.MF | grep Automatic-Module-Name`
when importing a JAR that is not explicitly modularized.

### OTel @WithSpan targets ONLY METHOD and CONSTRUCTOR

Contrary to what one might think, `io.opentelemetry.instrumentation.annotations.WithSpan`
**cannot** be placed on a class (`TYPE` is absent from `@Target`).
Consequence: no "trace the whole class" with a single OTel annotation — each
method must be annotated individually.

Our interceptor keeps a defensive class-level fallback (for exotic BCEs
that would add @WithSpan via metaprogramming) but in practice it is
dead code.

## Refactor P1 — shared OtlpJsonCommon (2026-05-21)

### Latent bug revealed by the refactor

During the duplication audit across the 3 OTLP/JSON encoders (M3 spans,
M4 metrics, M5 logs), it was observed that the `writeAnyValue` versions in
OtlpJsonMetricEncoder and OtlpJsonLogEncoder did NOT support
array attribute types (STRING_ARRAY, LONG_ARRAY, BOOLEAN_ARRAY,
DOUBLE_ARRAY). Latent bug: a metric or log with an attribute
`tags=["a","b"]` would have crashed with a RuntimeException via `default ->`.

Cause: OtlpJsonEncoder (M3) was written first with the complete
version; M4 and M5 copy-pasted a simplified version by mistake.
The refactor to `OtlpJsonCommon.writeAnyValue`, supporting only ONE
fixed version — the most complete one — removes the latent bug.

Regression test added (`encodes_array_attributes_as_otlp_arrayValue`)
proving that STRING_ARRAY + LONG_ARRAY serialize to
`arrayValue.values` according to the OTLP/JSON spec.

Pattern to remember: **a DRY refactor often reveals divergences**
between copies — they must be analyzed one by one instead of taking
"the most recent version" by default.

### Sharing via inheritance + functional callbacks (P2/P3)

For `InMemory{Span,Metric,LogRecord}Exporter` (P2) and
`Batch{Span,LogRecord}Processor` (P3), classic inheritance works
well because the subclasses implement DIFFERENT INTERFACES
(SpanExporter/MetricExporter/LogRecordExporter, SpanProcessor/LogRecordProcessor).

Humboldt pattern:
- Abstract `InMemoryExporterBase<T>`: `CopyOnWriteArrayList<T>` storage +
  `addAll/flushBase/shutdownBase` helpers. Subclasses (~30 lines):
  call the helpers from their interface impl.
- Abstract `AbstractBatchProcessor<T>`: VT worker + queue + flush/shutdown.
  Constructor takes `Consumer<List<T>> exportBatch + Supplier<CompletableResultCode>
  flushExporter + Runnable shutdownExporter` (functional callbacks that
  encapsulate the specific exporter interface). Subclasses call
  `offer(T)` from their callback (`onEnd` / `onEmit`).

Advantages of functional callbacks (vs abstract methods on the processor side):
- No need to template Base with `<X extends Exporter>` (less coupling)
- Explicit construction on the subclass side (the Builder constructor passes
  the lambdas) — IDE readability preserved
- Reusable beyond the Span/Log pair if a BatchMetricProcessor
  with a different signature is added

Measurements (M-block refactor P1+P2+P3):
- P1: −74 net lines (JSON encoders)
- P2: −90 net lines (InMemory exporters)
- P3: −80 net lines (Batch processors)
- Total cleanup: **−244 net lines** across 12 modules, 0 regression

### Java unicode preprocessor eats `\u00XX` even in comments

Surprising but documented: Java runs the unicode preprocessor BEFORE
the parser, on the whole source (comments included). So a `\u00XX`
sequence in a `/** ... */` comment is interpreted as a real
unicode character. If the result breaks syntax (e.g. an accidental `*/`
that prematurely closes a block comment), you get the compile
error "illegal unicode escape" — very cryptic.

Humboldt solution: avoid `\u` in comments (use `u00XX`
without the backslash, or escape the backslash as `\\u`).

### Manual OTLP/JSON encoding with StringBuilder

For the M3 MVP, the OTLP/JSON encoder is handwritten with StringBuilder (no
champollion JSON-P, no Jackson). The OTLP schema is fixed and limited
(~10 value types). The overhead of bringing in a JSON parser for this use
is disproportionate. The spec: <https://github.com/open-telemetry/opentelemetry-proto/blob/main/docs/specification.md#json-protobuf-encoding>

Details to remember:
- `traceId`/`spanId` are **hex strings** in OTLP/JSON (bytes in protobuf)
- timestamps are `string` values representing a nano long (not a JSON Number)
- `kind` int: INTERNAL=1, SERVER=2, CLIENT=3, PRODUCER=4, CONSUMER=5
- `status.code` int: UNSET=0, OK=1, ERROR=2
- AnyValue: `{"stringValue":"..."}`, `{"intValue":"..."}` (long → string!), etc.
