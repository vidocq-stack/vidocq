package io.vidocq.humboldt.cdi;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.annotation.Priority;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

import java.lang.reflect.Method;

/**
 * Interceptor CDI qui enveloppe chaque méthode portant
 * {@link io.opentelemetry.instrumentation.annotations.WithSpan @WithSpan}
 * (annotation API publique standard OpenTelemetry) dans un span OTel.
 *
 * <p>Bindée via {@link SpanBinding} (marker interne ajouté automatiquement
 * par {@link HumboldtBuildCompatibleExtension} au build time CDI). L'utilisateur
 * final n'écrit donc qu'une seule annotation : {@code @WithSpan} d'OTel.</p>
 *
 * <p>Cycle de vie d'une invocation :</p>
 * <ol>
 *   <li>Résout l'annotation OTel sur la méthode (puis sur la classe en fallback)</li>
 *   <li>Dérive le nom du span — {@code @WithSpan.value()} si non vide,
 *       sinon {@code Class.simpleName + "." + methodName}</li>
 *   <li>Crée le span via {@link Tracer#spanBuilder(String)}</li>
 *   <li>{@code try (Scope = span.makeCurrent()) { proceed(); }}</li>
 *   <li>Si exception : {@code span.recordException(t)} + statut ERROR, rethrow</li>
 *   <li>{@code span.end()} en finally</li>
 * </ol>
 *
 * <p>Priorité : {@link Interceptor.Priority#APPLICATION} + 1 — exécuté après
 * les interceptors plateforme (transaction, security) mais avant les
 * interceptors métier user-defined.</p>
 */
@Interceptor
@SpanBinding
@Priority(Interceptor.Priority.APPLICATION + 1)
public class WithSpanInterceptor {

    @AroundInvoke
    public Object aroundInvoke(InvocationContext ctx) throws Exception {
        Method method = ctx.getMethod();
        WithSpan annotation = resolveAnnotation(method);
        String spanName = (annotation == null || annotation.value().isEmpty())
                ? defaultName(method)
                : annotation.value();
        SpanKind kind = annotation == null ? SpanKind.INTERNAL : annotation.kind();

        Tracer t = tracer();
        SpanBuilder builder = t.spanBuilder(spanName).setSpanKind(kind);
        Span span = builder.startSpan();
        try (Scope ignored = span.makeCurrent()) {
            return ctx.proceed();
        } catch (Throwable th) {
            span.recordException(th);
            span.setStatus(StatusCode.ERROR,
                    th.getClass().getSimpleName() + ": " + (th.getMessage() != null ? th.getMessage() : ""));
            if (th instanceof Exception ex) throw ex;
            if (th instanceof Error er) throw er;
            throw new RuntimeException(th);
        } finally {
            span.end();
        }
    }

    /**
     * Surchargeable en sous-classe pour fournir un Tracer non global (typiquement
     * via CDI {@code @Inject} dans une variante M6d).
     */
    protected Tracer tracer() {
        return openTelemetry().getTracer("io.vidocq.humboldt.cdi");
    }

    /**
     * Hook indirection vers {@link GlobalOpenTelemetry} — permet aux tests de
     * surcharger en évitant l'init globale.
     */
    protected OpenTelemetry openTelemetry() {
        return GlobalOpenTelemetry.get();
    }

    private static WithSpan resolveAnnotation(Method method) {
        WithSpan onMethod = method.getAnnotation(WithSpan.class);
        if (onMethod != null) return onMethod;
        return method.getDeclaringClass().getAnnotation(WithSpan.class);
    }

    private static String defaultName(Method method) {
        return method.getDeclaringClass().getSimpleName() + "." + method.getName();
    }
}
