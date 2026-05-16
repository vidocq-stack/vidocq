package io.vidocq.heisenberg.cdi.internal;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import jakarta.interceptor.InterceptorBinding;

/**
 * Binding marqueur pour l'unique {@link FaultToleranceInterceptor} Heisenberg.
 *
 * <p><strong>Pourquoi un marqueur ?</strong>
 * La spec Jakarta Interceptors §2.6 impose un ET logique entre les bindings d'un
 * intercepteur : un intercepteur déclarant {@code @Retry} + {@code @Timeout} + ...
 * ne s'active que si <em>toutes</em> ces annotations sont présentes simultanément
 * sur la classe/méthode du bean. Or les beans MP FT ne portent en général qu'une
 * seule annotation FT à la fois ({@code @Retry} seul, par exemple).</p>
 *
 * <p>Pattern aligné sur SmallRye Fault Tolerance : un marqueur interne
 * {@code @FaultToleranceBinding} est l'unique binding déclaré sur
 * {@link FaultToleranceInterceptor}, et un BCE {@link HeisenbergExtension}
 * l'ajoute programmatiquement (via {@code ClassConfig.addAnnotation}) à toute
 * classe portant une des annotations MicroProfile Fault Tolerance 4.1
 * ({@code @Retry @Timeout @CircuitBreaker @Bulkhead @Asynchronous @Fallback}).</p>
 *
 * <p>L'annotation est intentionnellement réservée à un usage interne : elle ne
 * doit pas être posée à la main par les applications. Heisenberg garantit qu'elle
 * apparaît exactement là où une politique FT doit s'appliquer.</p>
 */
@Inherited
@InterceptorBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface FaultToleranceBinding {
}

