package io.vidocq.humboldt.cdi;

import jakarta.interceptor.InterceptorBinding;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Internal {@link InterceptorBinding} — CDI marker used by Humboldt to
 * activate {@link WithSpanInterceptor}.
 *
 * <p>End users should NEVER write {@code @SpanBinding} directly. It is
 * {@link HumboldtBuildCompatibleExtension} that adds it automatically
 * (at CDI build time) on every class or method annotated with
 * {@link io.opentelemetry.instrumentation.annotations.WithSpan} —
 * the standard OpenTelemetry public API annotation expected by the
 * MicroProfile Telemetry 2.1 TCK.</p>
 *
 * <p>The annotation is exposed for technical visibility reasons
 * (the BCE extension can only add publicly accessible annotations),
 * not for application use.</p>
 */
@InterceptorBinding
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface SpanBinding {
}
