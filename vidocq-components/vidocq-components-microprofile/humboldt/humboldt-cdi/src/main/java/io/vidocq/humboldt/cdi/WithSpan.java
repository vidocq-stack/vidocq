package io.vidocq.humboldt.cdi;

import io.opentelemetry.api.trace.SpanKind;
import jakarta.interceptor.InterceptorBinding;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation interceptor-binding qui demande à Humboldt d'envelopper la méthode
 * dans un span OpenTelemetry.
 *
 * <p>Usage :</p>
 * <pre>{@code
 * @ApplicationScoped
 * public class OrderService {
 *     @WithSpan(value = "OrderService.process", kind = SpanKind.INTERNAL)
 *     public Order process(Cart cart) { ... }
 * }
 * }</pre>
 *
 * <p>Si {@link #value()} est vide, le nom du span est dérivé du nom de méthode.
 * Le span hérite du Context courant comme parent ; si aucun parent, démarre
 * une nouvelle trace.</p>
 *
 * <p>Future alignement (M7) : à confronter à
 * {@code org.eclipse.microprofile.telemetry.tracing.WithSpan} ou
 * {@code io.opentelemetry.instrumentation.annotations.WithSpan} selon
 * les exigences du TCK MP Telemetry 2.1.</p>
 */
@InterceptorBinding
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface WithSpan {

    /**
     * @return le nom du span. Vide → dérivé du {@code Class.simpleName + "." + methodName}.
     */
    String value() default "";

    /**
     * @return le {@link SpanKind} du span (par défaut INTERNAL).
     */
    SpanKind kind() default SpanKind.INTERNAL;
}
