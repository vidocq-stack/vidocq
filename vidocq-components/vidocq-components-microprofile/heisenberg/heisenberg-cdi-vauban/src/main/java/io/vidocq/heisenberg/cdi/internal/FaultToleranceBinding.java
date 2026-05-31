package io.vidocq.heisenberg.cdi.internal;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import jakarta.interceptor.InterceptorBinding;

/**
 * Marker binding for the single Heisenberg {@link FaultToleranceInterceptor}.
 *
 * <p><strong>Why a marker?</strong>
 * Jakarta Interceptors spec §2.6 imposes a logical AND between an interceptor's
 * bindings: an interceptor declaring {@code @Retry} + {@code @Timeout} + ...
 * is activated only if <em>all</em> these annotations are simultaneously present
 * on the bean class/method. Yet MP FT beans generally carry only one FT
 * annotation at a time ({@code @Retry} alone, for example).</p>
 *
 * <p>Pattern aligned with SmallRye Fault Tolerance: an internal marker
 * {@code @FaultToleranceBinding} is the sole binding declared on
 * {@link FaultToleranceInterceptor}, and a BCE {@link HeisenbergExtension}
 * adds it programmatically (via {@code ClassConfig.addAnnotation}) to any
 * class carrying one of the MicroProfile Fault Tolerance 4.1 annotations
 * ({@code @Retry @Timeout @CircuitBreaker @Bulkhead @Asynchronous @Fallback}).</p>
 *
 * <p>The annotation is intentionally reserved for internal use: applications
 * must not apply it manually. Heisenberg guarantees that it appears exactly
 * where an FT policy must apply.</p>
 */
@Inherited
@InterceptorBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface FaultToleranceBinding {
}

