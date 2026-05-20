package io.vidocq.humboldt.sdk.log.data;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.SpanContext;
import io.vidocq.humboldt.sdk.common.InstrumentationScope;
import io.vidocq.humboldt.sdk.common.Resource;

/**
 * Vue immutable d'un LogRecord — consommée par
 * {@link io.vidocq.humboldt.sdk.log.export.LogRecordExporter}.
 *
 * @param resource              attributs de la source télémétrique
 * @param scope                 identité de la bibliothèque source
 * @param timestampEpochNanos   timestamp métier de l'événement (0 si non fourni)
 * @param observedEpochNanos    timestamp observé à l'émission par le SDK
 * @param spanContext           SpanContext courant à l'émission (valid() = false si hors trace)
 * @param severity              severity OTel ({@link Severity#UNDEFINED_SEVERITY_NUMBER} si non fourni)
 * @param severityText          texte libre (ex. {@code "INFO"}) — peut être vide
 * @param body                  corps du message (souvent un String, peut être vide)
 * @param attributes            attributs additionnels (jamais {@code null})
 */
public record LogRecordData(
        Resource resource,
        InstrumentationScope scope,
        long timestampEpochNanos,
        long observedEpochNanos,
        SpanContext spanContext,
        Severity severity,
        String severityText,
        String body,
        Attributes attributes) {

    public LogRecordData {
        if (resource == null) resource = Resource.empty();
        if (scope == null) scope = InstrumentationScope.of("");
        if (severity == null) severity = Severity.UNDEFINED_SEVERITY_NUMBER;
        if (severityText == null) severityText = "";
        if (body == null) body = "";
        if (attributes == null) attributes = Attributes.empty();
    }
}
