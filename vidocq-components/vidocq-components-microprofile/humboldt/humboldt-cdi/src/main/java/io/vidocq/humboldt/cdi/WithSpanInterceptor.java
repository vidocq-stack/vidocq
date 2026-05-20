package io.vidocq.humboldt.cdi;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.annotation.Priority;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

import java.lang.reflect.Method;

/**
 * Interceptor CDI qui enveloppe chaque méthode annotée {@link WithSpan} dans un span
 * OpenTelemetry.
 *
 * <p>Cycle de vie d'une invocation :</p>
 * <ol>
 *   <li>Récupère ou dérive le nom du span ({@code Class.simpleName + "." + methodName})</li>
 *   <li>Crée un {@link SpanBuilder} via le {@link Tracer} (parent = {@code Context.current()})</li>
 *   <li>{@code try (Scope = span.makeCurrent()) {...}} — le span est current pendant la méthode</li>
 *   <li>Si exception : {@code span.recordException(t)} + statut ERROR, puis rethrow</li>
 *   <li>{@code span.end()} en finally</li>
 * </ol>
 *
 * <p>Le {@link Tracer} est résolu via {@link #tracer()} qui pointe par défaut sur
 * {@link GlobalOpenTelemetry}. En M6b/M7, on le rendra configurable via CDI
 * (injection {@code @Inject Tracer}). Pour rester décorrélé de Weld/Vauban en
 * M6a, la méthode {@code tracer()} est protected pour permettre le subclassing
 * en test.</p>
 *
 * <p>Priorité : {@link Interceptor.Priority#APPLICATION} +1 — exécuté après les
 * interceptors plateforme (transaction, security) mais avant les interceptors
 * métier user-defined.</p>
 */
@Interceptor
@WithSpan
@Priority(Interceptor.Priority.APPLICATION + 1)
public class WithSpanInterceptor {

    @AroundInvoke
    public Object aroundInvoke(InvocationContext ctx) throws Exception {
        Method method = ctx.getMethod();
        WithSpan annotation = resolveAnnotation(method);
        String spanName = (annotation == null || annotation.value().isEmpty())
                ? defaultName(method)
                : annotation.value();
        var kind = annotation == null ? io.opentelemetry.api.trace.SpanKind.INTERNAL : annotation.kind();

        Tracer t = tracer();
        SpanBuilder builder = t.spanBuilder(spanName).setSpanKind(kind);
        Span span = builder.startSpan();
        try (Scope ignored = span.makeCurrent()) {
            return ctx.proceed();
        } catch (Throwable th) {
            span.recordException(th);
            span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR,
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
     * via CDI {@code @Inject} dans une variante M6b).
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
