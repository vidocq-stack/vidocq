package io.vidocq.runtime.it.humboldt;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Target CDI bean — verifies that the {@code BuildCompatibleExtension} of
 * {@code humboldt-cdi} adds the binding {@code @SpanBinding} on the
 * annotated methods {@link WithSpan @WithSpan} OTel, which activates
 * {@code WithSpanInterceptor} upon invocation.
 */
@ApplicationScoped
public class TraceTestService {

    @WithSpan("traced.work")
    public String doWork() {
        return "work-result";
    }

    @WithSpan(value = "traced.boom", kind = SpanKind.INTERNAL)
    public String alwaysFails() {
        throw new IllegalStateException("boom from traced.boom");
    }

    /** NO annotation method — checks for absence of span for non-annotated ones. */
    public String plain() {
        return "plain-result";
    }
}
