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
package io.vidocq.humboldt.cdi;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.logs.LoggerProvider;
import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.vidocq.humboldt.sdk.trace.SdkTracerProvider;
import io.vidocq.humboldt.sdk.trace.SimpleSpanProcessor;
import io.vidocq.humboldt.sdk.trace.export.InMemorySpanExporter;
import io.vidocq.humboldt.sdk.trace.samplers.Sampler;
import jakarta.enterprise.inject.spi.InjectionPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The CDI producers resolve OpenTelemetry objects from {@link GlobalOpenTelemetry}, scoped by the injection point,
 * and the injected {@link Span}/{@link Baggage} follow the current context (MP Telemetry 2.2 §"Access to the
 * OpenTelemetry Tracing API").
 */
class HumboldtTelemetryProducersTest {

    private static final String DEFAULT_SCOPE = "io.vidocq.humboldt.cdi";
    private static final String USE_METHOD = "use";

    private final HumboldtTelemetryProducers producers = new HumboldtTelemetryProducers();
    private final List<String> meterScopes = new ArrayList<>();
    private final List<String> loggerScopes = new ArrayList<>();
    private InMemorySpanExporter exporter;
    private SdkTracerProvider provider;
    private OpenTelemetry otel;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        provider = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        SdkTracerProvider tracerProvider = provider;
        MeterProvider meterProvider = name -> {
            meterScopes.add(name);
            return MeterProvider.noop().meterBuilder(name);
        };
        LoggerProvider loggerProvider = name -> {
            loggerScopes.add(name);
            return LoggerProvider.noop().loggerBuilder(name);
        };
        otel = new OpenTelemetry() {
            @Override public TracerProvider getTracerProvider() { return tracerProvider; }
            @Override public ContextPropagators getPropagators() { return ContextPropagators.noop(); }
            @Override public MeterProvider getMeterProvider() { return meterProvider; }
            @Override public LoggerProvider getLogsBridge() { return loggerProvider; }
        };
        GlobalOpenTelemetry.resetForTest();
        GlobalOpenTelemetry.set(otel);
    }

    @AfterEach
    void tearDown() {
        GlobalOpenTelemetry.resetForTest();
        provider.close();
    }

    @Test
    void open_telemetry_is_the_global_instance() {
        assertSame(GlobalOpenTelemetry.get(), producers.produceOpenTelemetry());
    }

    @Test
    void tracer_is_scoped_by_the_declaring_class_of_the_injection_point() throws NoSuchMethodException {
        producers.produceTracer(injectionPoint(Consumer.class.getMethod(USE_METHOD))).spanBuilder("a").startSpan().end();
        producers.produceTracer(injectionPoint(null)).spanBuilder("b").startSpan().end();
        producers.produceTracer(null).spanBuilder("c").startSpan().end();

        List<String> scopes = exporter.getFinishedSpans().stream()
                .map(s -> s.instrumentationScope().name())
                .toList();
        assertEquals(List.of(Consumer.class.getName(), DEFAULT_SCOPE, DEFAULT_SCOPE), scopes);
    }

    @Test
    void meter_is_scoped_by_the_injection_point() throws NoSuchMethodException {
        producers.produceMeter(injectionPoint(Consumer.class.getMethod(USE_METHOD)));
        producers.produceMeter(injectionPoint(null));
        producers.produceMeter(null);

        assertEquals(List.of(Consumer.class.getName(), DEFAULT_SCOPE, DEFAULT_SCOPE), meterScopes);
    }

    @Test
    void logger_is_scoped_by_the_injection_point() throws NoSuchMethodException {
        producers.produceLogger(injectionPoint(Consumer.class.getMethod(USE_METHOD)));
        producers.produceLogger(injectionPoint(null));
        producers.produceLogger(null);

        assertEquals(List.of(Consumer.class.getName(), DEFAULT_SCOPE, DEFAULT_SCOPE), loggerScopes);
    }

    @Test
    void injected_span_follows_the_current_span() {
        Span injected = producers.produceCurrentSpan();
        assertFalse(injected.getSpanContext().isValid(), "no current span yet");

        Span outer = provider.get("test").spanBuilder("outer").startSpan();
        try (var _ = outer.makeCurrent()) {
            assertEquals(outer.getSpanContext(), injected.getSpanContext());
        }
        outer.end();
        assertFalse(injected.getSpanContext().isValid());
    }

    @Test
    void injected_baggage_follows_the_current_baggage() {
        Baggage injected = producers.produceCurrentBaggage();
        assertEquals(0, injected.size());

        try (var _ = Baggage.builder().put("user", "naruto").build().makeCurrent()) {
            assertEquals("naruto", injected.getEntryValue("user"));
        }
        assertEquals(0, injected.size());
    }

    private static InjectionPoint injectionPoint(Member member) {
        return (InjectionPoint) Proxy.newProxyInstance(InjectionPoint.class.getClassLoader(),
                new Class<?>[]{InjectionPoint.class},
                (_, m, _) -> "getMember".equals(m.getName()) ? member : defaultFor(m));
    }

    private static Object defaultFor(Method m) {
        return m.getReturnType() == boolean.class ? Boolean.FALSE : null;
    }

    /** A class that would receive the injected objects. */
    public static class Consumer {
        public String use() {
            return "used";
        }
    }
}
