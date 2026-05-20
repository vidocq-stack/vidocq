package io.vidocq.humboldt.cdi;

import jakarta.interceptor.InterceptorBinding;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * {@link InterceptorBinding} interne — marker CDI utilisé par Humboldt pour
 * activer {@link WithSpanInterceptor}.
 *
 * <p>L'utilisateur final ne devrait JAMAIS écrire {@code @SpanBinding}
 * directement. C'est {@link HumboldtBuildCompatibleExtension} qui l'ajoute
 * automatiquement (au build time CDI) sur toute classe ou méthode portant
 * {@link io.opentelemetry.instrumentation.annotations.WithSpan} —
 * l'annotation API publique standard d'OpenTelemetry attendue par le TCK
 * MicroProfile Telemetry 2.1.</p>
 *
 * <p>L'annotation est exposée pour des raisons de visibilité technique
 * (l'extension BCE ne peut ajouter que des annotations publiquement
 * accessibles), pas pour usage applicatif.</p>
 */
@InterceptorBinding
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface SpanBinding {
}
