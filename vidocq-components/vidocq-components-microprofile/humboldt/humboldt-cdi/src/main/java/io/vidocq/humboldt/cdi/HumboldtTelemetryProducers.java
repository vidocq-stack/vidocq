package io.vidocq.humboldt.cdi;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.InjectionPoint;

import java.lang.reflect.Proxy;

/**
 * CDI producers for the standard OpenTelemetry types required by the
 * MicroProfile Telemetry 2.1 spec §"Required CDI beans".
 *
 * <ul>
 *   <li>{@link OpenTelemetry} — resolved via {@link GlobalOpenTelemetry#get()}</li>
 *   <li>{@link Tracer} — resolved via the scope name derived from the injection point</li>
 *   <li>{@link Span} — {@link Span#current()} at resolution time</li>
 *   <li>{@link Baggage} — {@link Baggage#current()} at resolution time</li>
 * </ul>
 *
 * <p>Lets applications simply write {@code @Inject Tracer tracer}
 * or {@code @Inject Span current} without declaring their own producers.</p>
 */
@ApplicationScoped
public class HumboldtTelemetryProducers {

    /**
     * Producer for {@link OpenTelemetry} — the global instance configured by
     * {@link io.vidocq.humboldt.runtime.HumboldtAutoConfigure} (or
     * {@link GlobalOpenTelemetry#set(OpenTelemetry)} if the app does not use
     * autoconfig).
     */
    @Produces
    public OpenTelemetry produceOpenTelemetry() {
        return GlobalOpenTelemetry.get();
    }

    /**
     * Producer for {@link Tracer} — the tracer name is derived from the injection point:
     * declaring class by default. OTel convention: {@code getTracer(scope)}.
     */
    @Produces
    public Tracer produceTracer(InjectionPoint ip) {
        String scope = ip != null && ip.getMember() != null
                ? ip.getMember().getDeclaringClass().getName()
                : "io.vidocq.humboldt.cdi";
        return GlobalOpenTelemetry.get().getTracer(scope);
    }

    /**
     * Producer for {@link Span} — returns a dynamic proxy that delegates each method
     * call to {@link Span#current()} at invocation time (not at injection time).
     * MP Telemetry 2.1 spec §"Required CDI beans": {@code SpanBeanTest.spanBeanChange}
     * mutates the Context after injection and expects subsequent accesses to
     * {@code injectedSpan} to reflect the new current span.
     */
    @Produces
    public Span produceCurrentSpan() {
        return (Span) Proxy.newProxyInstance(
                Span.class.getClassLoader(),
                new Class<?>[]{Span.class},
                (proxy, method, args) -> method.invoke(Span.current(), args));
    }

    /**
     * Producer for {@link Baggage} — dynamic proxy that delegates to
     * {@link Baggage#current()} on each call. For
     * {@code BaggageBeanTest.baggageBeanChange} which mutates the Context after
     * injection (see {@link #produceCurrentSpan()} for the same approach).
     */
    @Produces
    public Baggage produceCurrentBaggage() {
        return (Baggage) Proxy.newProxyInstance(
                Baggage.class.getClassLoader(),
                new Class<?>[]{Baggage.class},
                (proxy, method, args) -> method.invoke(Baggage.current(), args));
    }

    /**
     * Producer for {@link Meter} — the meter name is derived from the injection point:
     * declaring class by default. OTel convention: {@code getMeter(scope)}.
     */
    @Produces
    public Meter produceMeter(InjectionPoint ip) {
        String scope = ip != null && ip.getMember() != null
                ? ip.getMember().getDeclaringClass().getName()
                : "io.vidocq.humboldt.cdi";
        return GlobalOpenTelemetry.get().getMeter(scope);
    }

    /**
     * Producer for {@link Logger} (OTel logs) — the logger name is derived from
     * the injection point.
     */
    @Produces
    public Logger produceLogger(InjectionPoint ip) {
        String scope = ip != null && ip.getMember() != null
                ? ip.getMember().getDeclaringClass().getName()
                : "io.vidocq.humboldt.cdi";
        return GlobalOpenTelemetry.get().getLogsBridge().get(scope);
    }
}
