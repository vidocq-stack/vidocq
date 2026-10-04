/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.humboldt.otel.interop;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.lang.invoke.MethodType;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The OpenTelemetry OTLP exporter ({@code opentelemetry-exporter-otlp} 1.66, which an application adds to export
 * through the OpenTelemetry autoconfigure SPI) on the module path, next to the Humboldt explicit modules
 * {@code io.opentelemetry.api} and {@code io.opentelemetry.context}.
 *
 * <p>BUG-20261004-03: the OpenTelemetry SDK, exporter and sender jars use packages of those two modules that are
 * not public API ({@code io.opentelemetry.api.internal}, {@code io.opentelemetry.api.impl},
 * {@code io.opentelemetry.api.trace.propagation.internal}, {@code io.opentelemetry.context.internal.shaded}).
 * Upstream the API and context jars are automatic modules and export every package; Humboldt's explicit modules
 * must export those packages to them, and to them only. The tests export a span, a metric and a log record for
 * real — to a closed local port, so that the request fails on the connection and nowhere else.</p>
 *
 * <p>Surefire runs this module's tests on the class path, where no module check applies, so each test defines
 * a real {@link ModuleLayer} from the jars — Humboldt's explicit modules plus the OpenTelemetry jars as
 * automatic modules — and drives the exporter from inside it, as an application on the module path would. Each
 * test gets a layer of its own: a static initializer that fails in one layer must not affect the next test.</p>
 */
class OtlpExporterModuleLayerTest {

    private static final String INTEROP = "io.vidocq.humboldt.otel.interop";
    private static final String OTLP_INTERNAL = "io.opentelemetry.exporter.otlp.internal.";
    private static final String RESULT_CODE = "io.opentelemetry.sdk.common.CompletableResultCode";
    private static final String TESTING = "io.opentelemetry.sdk.testing.exporter.";

    /** Humboldt's explicit modules, each located by one of its classes. */
    private static final List<String> HUMBOLDT = List.of(
            INTEROP + ".MapConfigProperties",                        // humboldt-otel-interop
            "io.opentelemetry.context.Context",                      // humboldt-otel-context
            "io.opentelemetry.api.OpenTelemetry",                    // humboldt-otel-api
            "io.vidocq.humboldt.Humboldt",                           // humboldt-api
            "io.vidocq.humboldt.context.HumboldtContextStorageProvider", // humboldt-context
            "io.vidocq.humboldt.sdk.common.Resource",                // humboldt-sdk-common
            "io.vidocq.humboldt.sdk.trace.BatchSpanProcessor",       // humboldt-sdk-trace
            "io.vidocq.humboldt.sdk.metric.PeriodicMetricReader",    // humboldt-sdk-metric
            "io.vidocq.humboldt.propagator.w3c.W3CPropagators");     // humboldt-propagator-w3c

    /** The OpenTelemetry 1.66 SDK (automatic modules), as the Vidocq telemetry extension brings it. */
    private static final List<String> OTEL_SDK = List.of(
            "io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties",     // ...-sdk-extension-autoconfigure-spi
            "io.opentelemetry.sdk.OpenTelemetrySdk",                       // opentelemetry-sdk
            "io.opentelemetry.sdk.common.CompletableResultCode",           // opentelemetry-sdk-common
            "io.opentelemetry.sdk.trace.SdkTracerProvider",                // opentelemetry-sdk-trace
            "io.opentelemetry.sdk.metrics.SdkMeterProvider",               // opentelemetry-sdk-metrics
            "io.opentelemetry.sdk.logs.SdkLoggerProvider",                 // opentelemetry-sdk-logs
            TESTING + "InMemorySpanExporter");                            // opentelemetry-sdk-testing (test data)

    /** The OpenTelemetry 1.66 OTLP exporter (automatic modules), as an application adds it. */
    private static final List<String> OTEL_EXPORTER = List.of(
            "io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter",       // opentelemetry-exporter-otlp
            "io.opentelemetry.exporter.internal.otlp.traces.TraceRequestMarshaler", // ...-exporter-otlp-common
            "io.opentelemetry.exporter.internal.SenderUtil",                        // ...-exporter-common
            "io.opentelemetry.exporter.sender.jdk.internal.JdkHttpSenderProvider"); // ...-exporter-sender-jdk

    /** The exporter logs each failed export: expected here, kept out of the build output. */
    private static final Logger EXPORTER_LOGGER = Logger.getLogger("io.opentelemetry.exporter");
    private static Level exporterLoggerLevel;

    @BeforeAll
    static void silenceTheExpectedExportFailures() {
        exporterLoggerLevel = EXPORTER_LOGGER.getLevel();
        EXPORTER_LOGGER.setLevel(Level.OFF);
    }

    @AfterAll
    static void restoreTheExporterLogger() {
        EXPORTER_LOGGER.setLevel(exporterLoggerLevel);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "OtlpSpanExporterProvider, OtlpHttpSpanExporter",
            "OtlpMetricExporterProvider, OtlpHttpMetricExporter",
            "OtlpLogRecordExporterProvider, OtlpHttpLogRecordExporter"})
    void the_otlp_exporter_provider_creates_its_exporter_on_the_module_path(String provider, String exporter) {
        Layer layer = Layer.of(HUMBOLDT, OTEL_SDK, OTEL_EXPORTER);

        Object created = createExporter(layer, provider, Map.of());

        assertEquals(exporter, created.getClass().getSimpleName());
        shutdown(layer, created);
    }

    /**
     * Every signal marshals its resource and scope (through {@code io.opentelemetry.context.internal.shaded}), a
     * span its trace state ({@code io.opentelemetry.api.trace.propagation.internal}), and the JDK sender
     * suppresses the instrumentation of its own request ({@code io.opentelemetry.api.impl}).
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(Signal.class)
    void the_otlp_exporter_exports_a_signal_on_the_module_path(Signal signal) throws IOException {
        Layer layer = Layer.of(HUMBOLDT, OTEL_SDK, OTEL_EXPORTER);

        assertTheExportFailsOnTheConnectionOnly(layer, layer, signal);
    }

    /**
     * A qualified export reaches only target modules of the same layer or of a parent layer: an OTLP exporter
     * that an application brings in a child layer of the Humboldt modules gets none of them from the
     * descriptors. When humboldt-otel-interop discovers the OpenTelemetry providers of that layer, the owning
     * Humboldt modules extend their qualified exports to it.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(Signal.class)
    void interop_discovery_extends_the_qualified_exports_to_an_otlp_exporter_in_a_child_layer(Signal signal)
            throws IOException {
        Layer humboldt = Layer.of(HUMBOLDT, OTEL_SDK);
        Layer application = humboldt.child(OTEL_EXPORTER);
        Module api = humboldt.module("io.opentelemetry.api");
        Module context = humboldt.module("io.opentelemetry.context");
        Module otlpCommon = application.module("io.opentelemetry.exporter.internal.otlp");
        assertFalse(api.isExported("io.opentelemetry.api.internal", otlpCommon),
                "the descriptor's qualified export does not reach a child layer");
        assertFalse(context.isExported("io.opentelemetry.context.internal.shaded", otlpCommon));

        humboldt.callStatic(INTEROP + ".OtelSpiAutoConfiguration", "discover", Map.of(), application.loader());

        assertTrue(api.isExported("io.opentelemetry.api.internal", otlpCommon));
        assertTrue(context.isExported("io.opentelemetry.context.internal.shaded", otlpCommon));
        assertFalse(api.isExported("io.opentelemetry.api.internal", humboldt.module(INTEROP)),
                "only the modules the descriptor names");
        assertTheExportFailsOnTheConnectionOnly(humboldt, application, signal);
    }

    /**
     * The qualified exports of the layer helpers go to {@code io.vidocq.humboldt.otel.interop} by name, and a
     * qualified export reaches only target modules of the same layer or of a parent layer. When interop sits in a
     * child layer of the OpenTelemetry API module, it cannot read {@code io.vidocq.humboldt.otel.api.layer}: the
     * extension of the exports fails with an {@link IllegalAccessError}. That failure must not drop the provider
     * it was extending the exports for (the exports are an optimisation of the child-layer layout, not a
     * condition of discovering a provider).
     */
    @Test
    void interop_in_a_child_layer_keeps_a_provider_whose_exports_cannot_be_extended() {
        List<String> humboldtWithoutInterop = HUMBOLDT.subList(1, HUMBOLDT.size());
        Layer parent = Layer.of(humboldtWithoutInterop, OTEL_SDK);
        Layer application = parent.child(List.of(HUMBOLDT.get(0)), OTEL_EXPORTER);
        Module api = parent.module("io.opentelemetry.api");
        Module interop = application.module(INTEROP);
        assertFalse(api.isExported("io.vidocq.humboldt.otel.api.layer", interop),
                "the qualified export to interop does not reach a child layer");

        Logger logger = Logger.getLogger(INTEROP + ".OtelSpiAutoConfiguration");
        List<String> messages = new ArrayList<>();
        Handler capture = new Handler() {
            @Override public void publish(LogRecord record) {
                messages.add(MessageFormat.format(record.getMessage(), record.getParameters()));
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        boolean useParentHandlers = logger.getUseParentHandlers();
        Level loggerLevel = logger.getLevel();
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.FINE);
        capture.setLevel(Level.ALL);
        logger.addHandler(capture);
        try {
            application.callStatic(INTEROP + ".OtelSpiAutoConfiguration", "discover", Map.of(), application.loader());
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(loggerLevel);
            logger.setUseParentHandlers(useParentHandlers);
        }

        assertTrue(messages.stream().anyMatch(m -> m.contains("SpanExporterProvider discovered")
                        && m.contains("OtlpSpanExporterProvider")),
                "the OTLP span exporter provider must still be discovered, got: " + messages);
        assertTrue(messages.stream().anyMatch(m -> m.contains("Qualified exports not extended")
                        && m.contains("OtlpSpanExporterProvider")),
                "the failed extension of the exports must be logged at FINE, got: " + messages);
    }

    /**
     * BUG-20261004-04: any configured compression initialises the exporter's {@code CompressorUtil}, whose static
     * registry loads the {@code Compressor} services through {@code ComponentLoader.forClassLoader(...)}, the
     * upstream default that no {@code ConfigProperties} can replace — a {@code ServiceLoader.load} issued from
     * {@code io.opentelemetry.context}.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"gzip", "none"})
    void the_otlp_exporter_provider_applies_a_configured_compression_on_the_module_path(String compression) {
        Layer layer = Layer.of(HUMBOLDT, OTEL_SDK, OTEL_EXPORTER);

        Object created = createExporter(layer, "OtlpSpanExporterProvider",
                Map.of("otel.exporter.otlp.compression", compression));

        assertEquals("OtlpHttpSpanExporter", created.getClass().getSimpleName());
        String encoding = "none".equals(compression) ? "null" : compression;
        assertTrue(created.toString().contains("compressorEncoding=" + encoding), created.toString());
        shutdown(layer, created);
    }

    /**
     * An exporter built directly, without {@code setComponentLoader(...)}, loads its sender and its compressor
     * through the upstream default {@code ComponentLoader.forClassLoader(...)} — the residual of
     * BUG-20261004-01 that BUG-20261004-04 closes.
     */
    @Test
    void an_otlp_exporter_built_without_a_component_loader_finds_its_sender_and_compressor_on_the_module_path() {
        Layer layer = Layer.of(HUMBOLDT, OTEL_SDK, OTEL_EXPORTER);
        String exporterType = "io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter";

        Object builder = layer.callStatic(exporterType, "builder");
        layer.call(builder, exporterType + "Builder", "setCompression", "gzip");
        Object created = layer.call(builder, exporterType + "Builder", "build");

        assertEquals("OtlpHttpSpanExporter", created.getClass().getSimpleName());
        assertTrue(created.toString().contains("compressorEncoding=gzip"), created.toString());
        shutdown(layer, created);
    }

    @ParameterizedTest(name = "{1} to {2}")
    @CsvSource({
            "io.opentelemetry.api, io.opentelemetry.api.internal, io.opentelemetry.sdk.autoconfigure.spi",
            "io.opentelemetry.api, io.opentelemetry.api.impl, io.opentelemetry.exporter.sender.jdk.internal",
            "io.opentelemetry.api, io.opentelemetry.api.trace.propagation.internal,"
                    + " io.opentelemetry.exporter.internal.otlp",
            "io.opentelemetry.context, io.opentelemetry.context.internal.shaded,"
                    + " io.opentelemetry.exporter.internal.otlp"})
    void an_internal_package_is_exported_only_to_the_opentelemetry_modules_that_use_it(
            String owner, String pkg, String user) {
        Layer layer = Layer.of(HUMBOLDT, OTEL_SDK, OTEL_EXPORTER);
        Module source = layer.module(owner);

        assertFalse(source.isExported(pkg), "the export must stay qualified");
        assertFalse(source.isExported(pkg, layer.module(INTEROP)), "no Humboldt module uses it");
        assertFalse(source.isExported(pkg, layer.module("io.opentelemetry.sdk")), "opentelemetry-sdk does not use it");
        assertTrue(source.isExported(pkg, layer.module(user)));
    }

    /** The three signals, each with the OpenTelemetry SDK code that produces one item of it. */
    enum Signal {
        SPAN("OtlpSpanExporterProvider", "io.opentelemetry.sdk.trace.export.SpanExporter") {
            @Override
            Object produceOneItem(Layer sdk) {
                Object inMemory = sdk.callStatic(TESTING + "InMemorySpanExporter", "create");
                Object processor = sdk.callStatic("io.opentelemetry.sdk.trace.export.SimpleSpanProcessor", "create",
                        inMemory);
                Object builder = sdk.callStatic("io.opentelemetry.sdk.trace.SdkTracerProvider", "builder");
                sdk.call(builder, "io.opentelemetry.sdk.trace.SdkTracerProviderBuilder", "addSpanProcessor", processor);
                Object provider = sdk.call(builder, "io.opentelemetry.sdk.trace.SdkTracerProviderBuilder", "build");
                Object tracer = sdk.call(provider, "io.opentelemetry.api.trace.TracerProvider", "get", "fc4");
                Object spanBuilder = sdk.call(tracer, "io.opentelemetry.api.trace.Tracer", "spanBuilder", "exported");
                // A remote parent with a trace state: the span inherits it, and the OTLP marshaler encodes a
                // non-empty trace state only (W3CTraceContextEncoding).
                Object traceStateBuilder = sdk.callStatic("io.opentelemetry.api.trace.TraceState", "builder");
                sdk.call(traceStateBuilder, "io.opentelemetry.api.trace.TraceStateBuilder", "put", "fc4", "1");
                Object traceState = sdk.call(traceStateBuilder, "io.opentelemetry.api.trace.TraceStateBuilder",
                        "build");
                Object parent = sdk.callStatic("io.opentelemetry.api.trace.SpanContext", "createFromRemoteParent",
                        "0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331",
                        sdk.callStatic("io.opentelemetry.api.trace.TraceFlags", "getSampled"), traceState);
                Object parentSpan = sdk.callStatic("io.opentelemetry.api.trace.Span", "wrap", parent);
                Object root = sdk.callStatic("io.opentelemetry.context.Context", "root");
                Object context = sdk.call(root, "io.opentelemetry.context.Context", "with", parentSpan);
                sdk.call(spanBuilder, "io.opentelemetry.api.trace.SpanBuilder", "setParent", context);
                Object span = sdk.call(spanBuilder, "io.opentelemetry.api.trace.SpanBuilder", "startSpan");
                sdk.call(span, "io.opentelemetry.api.trace.Span", "end");
                return sdk.call(inMemory, TESTING + "InMemorySpanExporter", "getFinishedSpanItems");
            }
        },
        METRIC("OtlpMetricExporterProvider", "io.opentelemetry.sdk.metrics.export.MetricExporter") {
            @Override
            Object produceOneItem(Layer sdk) {
                Object reader = sdk.callStatic(TESTING + "InMemoryMetricReader", "create");
                Object builder = sdk.callStatic("io.opentelemetry.sdk.metrics.SdkMeterProvider", "builder");
                sdk.call(builder, "io.opentelemetry.sdk.metrics.SdkMeterProviderBuilder", "registerMetricReader",
                        reader);
                Object provider = sdk.call(builder, "io.opentelemetry.sdk.metrics.SdkMeterProviderBuilder", "build");
                Object meter = sdk.call(provider, "io.opentelemetry.api.metrics.MeterProvider", "get", "fc4");
                Object counterBuilder = sdk.call(meter, "io.opentelemetry.api.metrics.Meter", "counterBuilder",
                        "exported");
                Object counter = sdk.call(counterBuilder, "io.opentelemetry.api.metrics.LongCounterBuilder", "build");
                sdk.call(counter, "io.opentelemetry.api.metrics.LongCounter", "add", 1L);
                return sdk.call(reader, TESTING + "InMemoryMetricReader", "collectAllMetrics");
            }
        },
        LOG("OtlpLogRecordExporterProvider", "io.opentelemetry.sdk.logs.export.LogRecordExporter") {
            @Override
            Object produceOneItem(Layer sdk) {
                Object inMemory = sdk.callStatic(TESTING + "InMemoryLogRecordExporter", "create");
                Object processor = sdk.callStatic("io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor",
                        "create", inMemory);
                Object builder = sdk.callStatic("io.opentelemetry.sdk.logs.SdkLoggerProvider", "builder");
                sdk.call(builder, "io.opentelemetry.sdk.logs.SdkLoggerProviderBuilder", "addLogRecordProcessor",
                        processor);
                Object provider = sdk.call(builder, "io.opentelemetry.sdk.logs.SdkLoggerProviderBuilder", "build");
                Object logger = sdk.call(provider, "io.opentelemetry.api.logs.LoggerProvider", "get", "fc4");
                Object record = sdk.call(logger, "io.opentelemetry.api.logs.Logger", "logRecordBuilder");
                sdk.call(record, "io.opentelemetry.api.logs.LogRecordBuilder", "setBody", "exported");
                sdk.call(record, "io.opentelemetry.api.logs.LogRecordBuilder", "emit");
                return sdk.call(inMemory, TESTING + "InMemoryLogRecordExporter", "getFinishedLogRecordItems");
            }
        };

        final String provider;
        final String exporterType;

        Signal(String provider, String exporterType) {
            this.provider = provider;
            this.exporterType = exporterType;
        }

        /** The collection of one span, metric or log record that the SDK of {@code sdk} produced. */
        abstract Object produceOneItem(Layer sdk);
    }

    /**
     * Creates the OTLP exporter of {@code signal} in {@code exporters}, exports one item produced by the SDK of
     * {@code sdk} to a closed local port, and checks that the export fails on the connection — and on nothing
     * else: no {@link Error} (an {@code IllegalAccessError} above all), thrown or reported.
     */
    static void assertTheExportFailsOnTheConnectionOnly(Layer sdk, Layer exporters, Signal signal)
            throws IOException {
        Object items = signal.produceOneItem(sdk);
        Object exporter = createExporter(exporters, signal.provider, Map.of(
                "otel.exporter.otlp.endpoint", closedLocalEndpoint(),
                "otel.java.exporter.otlp.retry.disabled", "true"));

        Object result = exporters.call(exporter, signal.exporterType, "export", items);
        exporters.call(result, RESULT_CODE, "join", 10L, TimeUnit.SECONDS);

        assertFalse((Boolean) exporters.call(result, RESULT_CODE, "isSuccess"), "nothing listens on the port");
        Throwable failure = (Throwable) exporters.call(result, RESULT_CODE, "getFailureThrowable");
        boolean connectionFailure = false;
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof Error) {
                fail("the " + signal + " export failed on the module path: " + causes(failure), failure);
            }
            connectionFailure |= t instanceof IOException;
        }
        assertTrue(connectionFailure, "the export must fail on the connection, failure: " + causes(failure));
        shutdown(exporters, exporter);
    }

    /**
     * Lets the upstream exporter provider create its exporter from a humboldt-otel-interop
     * {@link MapConfigProperties} — the path humboldt-otel-interop's discovery takes.
     */
    static Object createExporter(Layer layer, String providerName, Map<String, String> extra) {
        Map<String, String> properties = new HashMap<>();
        properties.put("otel.exporter.otlp.protocol", "http/protobuf");
        properties.put("otel.exporter.otlp.endpoint", "http://127.0.0.1:4318");
        properties.putAll(extra);
        Object config = layer.newInstance(INTEROP + ".MapConfigProperties", properties, layer.loader());
        Object provider = layer.newInstance(OTLP_INTERNAL + providerName);
        return layer.call(provider, OTLP_INTERNAL + providerName, "createExporter", config);
    }

    static void shutdown(Layer layer, Object exporter) {
        layer.call(exporter, exporter.getClass().getName(), "shutdown");
    }

    /** An endpoint on a local port that was free a moment ago: connecting to it is refused. */
    private static String closedLocalEndpoint() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return "http://127.0.0.1:" + socket.getLocalPort();
        }
    }

    private static String causes(Throwable t) {
        StringBuilder sb = new StringBuilder(String.valueOf(t));
        for (Throwable c = t == null ? null : t.getCause(); c != null && c != c.getCause(); c = c.getCause()) {
            sb.append(" <- caused by ").append(c);
        }
        return sb.toString();
    }

    /**
     * A module layer defined from jars, with reflective calls on the objects its classes create. Calls go through
     * the public types of the layer's modules, the way the code of an application module would make them.
     */
    record Layer(ModuleLayer layer, ClassLoader loader) {

        /** One layer over the boot layer, with every module of {@code anchors}; the roots are all its modules. */
        @SafeVarargs
        static Layer of(List<String>... anchors) {
            return over(ModuleLayer.boot(), ClassLoader.getSystemClassLoader(), anchors);
        }

        /** A child layer of this one, with the modules of {@code anchors}. */
        @SafeVarargs
        final Layer child(List<String>... anchors) {
            return over(layer, loader, anchors);
        }

        @SafeVarargs
        private static Layer over(ModuleLayer parent, ClassLoader parentLoader, List<String>... anchors) {
            Path[] modulePath = Stream.of(anchors).flatMap(List::stream)
                    .map(Layer::locationOf)
                    .distinct()
                    .toArray(Path[]::new);
            ModuleFinder finder = ModuleFinder.of(modulePath);
            Set<String> roots = new HashSet<>();
            finder.findAll().forEach(reference -> roots.add(reference.descriptor().name()));
            Configuration configuration = parent.configuration().resolve(finder, ModuleFinder.of(), roots);
            ModuleLayer layer = parent.defineModulesWithOneLoader(configuration, parentLoader);
            ClassLoader loader = layer.modules().iterator().next().getClassLoader();
            Layer defined = new Layer(layer, loader);
            for (String explicit : List.of("io.opentelemetry.api", "io.opentelemetry.context")) {
                layer.findModule(explicit).ifPresent(module -> assertFalse(module.getDescriptor().isAutomatic(),
                        explicit + " must be the explicit Humboldt module"));
            }
            return defined;
        }

        /** The module {@code name} of this layer or of a parent layer. */
        Module module(String name) {
            return layer.findModule(name).orElseThrow(() -> new AssertionError("module not in the layers: " + name));
        }

        Class<?> type(String name) {
            try {
                return Class.forName(name, false, loader);
            } catch (ClassNotFoundException e) {
                throw new AssertionError("class not visible from the layer: " + name, e);
            }
        }

        Object newInstance(String type, Object... args) {
            for (var constructor : type(type).getConstructors()) {
                if (accepts(constructor.getParameterTypes(), args)) {
                    return inTheLayer("new " + type, () -> constructor.newInstance(args));
                }
            }
            throw new AssertionError("no constructor of " + type + " for " + Arrays.toString(args));
        }

        Object callStatic(String type, String method, Object... args) {
            return invoke(type(type), null, method, args);
        }

        Object call(Object target, String type, String method, Object... args) {
            return invoke(type(type), target, method, args);
        }

        private Object invoke(Class<?> type, Object target, String name, Object... args) {
            for (Method method : type.getMethods()) {
                if (method.getName().equals(name) && accepts(method.getParameterTypes(), args)) {
                    return inTheLayer(type.getSimpleName() + "." + name, () -> method.invoke(target, args));
                }
            }
            throw new AssertionError("no method " + type.getName() + "." + name + " for " + Arrays.toString(args));
        }

        /**
         * Runs {@code call} with this layer's loader as the context class loader, as in an application on the
         * module path: OpenTelemetry looks some services up through it ({@code ContextStorageProvider}), and the
         * class path of this test holds copies of the same classes.
         */
        private Object inTheLayer(String what, ReflectiveCall call) {
            Thread thread = Thread.currentThread();
            ClassLoader previous = thread.getContextClassLoader();
            thread.setContextClassLoader(loader);
            try {
                return call.run();
            } catch (InvocationTargetException e) {
                throw new AssertionError(what + " failed on the module path: " + causes(e.getCause()), e.getCause());
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            } finally {
                thread.setContextClassLoader(previous);
            }
        }

        private static boolean accepts(Class<?>[] parameters, Object[] args) {
            if (parameters.length != args.length) {
                return false;
            }
            for (int i = 0; i < args.length; i++) {
                Class<?> parameter = MethodType.methodType(parameters[i]).wrap().returnType();
                if (args[i] != null && !parameter.isInstance(args[i])) {
                    return false;
                }
            }
            return true;
        }

        private static Path locationOf(String className) {
            try {
                Class<?> type = Class.forName(className, false, OtlpExporterModuleLayerTest.class.getClassLoader());
                return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
            } catch (ClassNotFoundException | URISyntaxException e) {
                throw new IllegalStateException("cannot locate the jar of " + className, e);
            }
        }
    }

    @FunctionalInterface
    private interface ReflectiveCall {
        Object run() throws ReflectiveOperationException;
    }
}
