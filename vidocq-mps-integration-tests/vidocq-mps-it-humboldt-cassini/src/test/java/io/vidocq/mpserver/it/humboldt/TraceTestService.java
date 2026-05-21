package io.vidocq.mpserver.it.humboldt;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Bean CDI cible — vérifie que la {@code BuildCompatibleExtension} de
 * {@code humboldt-cdi} ajoute bien le binding {@code @SpanBinding} sur les
 * méthodes annotées {@link WithSpan @WithSpan} OTel, ce qui active
 * {@code WithSpanInterceptor} à l'invocation.
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

    /** Méthode SANS annotation — vérifie l'absence de span pour les non-annotées. */
    public String plain() {
        return "plain-result";
    }
}
