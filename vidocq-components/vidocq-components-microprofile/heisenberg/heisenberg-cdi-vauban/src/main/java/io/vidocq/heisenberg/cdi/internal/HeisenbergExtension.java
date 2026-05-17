package io.vidocq.heisenberg.cdi.internal;

import io.vidocq.heisenberg.internal.ConfigResolver;
import io.vidocq.heisenberg.internal.FallbackResolver;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
import jakarta.annotation.Priority;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.util.AnnotationLiteral;
import org.eclipse.microprofile.faulttolerance.Asynchronous;
import org.eclipse.microprofile.faulttolerance.Bulkhead;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.eclipse.microprofile.faulttolerance.exceptions.FaultToleranceDefinitionException;

/**
 * Build Compatible Extension Vauban pour Heisenberg — valide au démarrage du container
 * la cohérence des annotations MicroProfile Fault Tolerance 4.1 :
 * <ul>
 *   <li>{@code @Fallback(fallbackMethod=...)} : méthode existante avec signature compatible.</li>
 *   <li>{@code @Asynchronous} : type de retour {@code CompletionStage} ou {@code Future}.</li>
 *   <li>{@code @Bulkhead} + {@code @Asynchronous} : {@code waitingTaskQueue} ≥ 0.</li>
 * </ul>
 *
 * <p>M8 §9 : lit une <strong>unique fois au démarrage</strong> la propriété MP Config
 * {@code mp.fault.tolerance.interceptor.priority} et l'applique au {@link FaultToleranceInterceptor}
 * via {@link Enhancement} (ré-écriture de l'annotation {@link Priority}).</p>
 */
public class HeisenbergExtension implements BuildCompatibleExtension {

	private final FallbackResolver fallbackResolver = new FallbackResolver();
	private final Set<String> bindingEnhancedClasses = ConcurrentHashMap.newKeySet();

	/**
	 * M8 §9 : applique au démarrage la priorité configurée via MP Config
	 * (clé {@code mp.fault.tolerance.interceptor.priority}). La propriété est lue
	 * une seule fois ; si elle est absente, la priorité par défaut
	 * {@link ConfigResolver#DEFAULT_INTERCEPTOR_PRIORITY} (4010) reste effective.
	 */
	@Enhancement(types = FaultToleranceInterceptor.class)
	public void configureInterceptorPriority(ClassConfig classConfig) {
		int priority = ConfigResolver.interceptorPriority();
		if (priority == ConfigResolver.DEFAULT_INTERCEPTOR_PRIORITY) {
			return; // priorité par défaut déjà déclarée statiquement
		}
		classConfig.removeAnnotation(a -> Priority.class.getName().equals(a.name()));
		classConfig.addAnnotation(new PriorityLiteral(priority));
	}

	/**
	 * Ajoute le binding marqueur {@link FaultToleranceBinding} à toute classe portant
	 * une annotation MicroProfile Fault Tolerance 4.1
	 * ({@code @Retry @Timeout @CircuitBreaker @Bulkhead @Asynchronous @Fallback}) —
	 * directement ou sur une de ses méthodes. Sans cet enhancement, le
	 * {@link FaultToleranceInterceptor} (qui ne déclare que
	 * {@code @FaultToleranceBinding}) ne serait jamais sélectionné par CDI pour ces
	 * beans : la spec Jakarta Interceptors §2.6 exige qu'<em>au moins un</em> binding
	 * de l'intercepteur soit présent sur la cible.
	 *
	 * <p>L'ajout est effectué au niveau classe — l'intercepteur s'exécute donc sur
	 * <em>toutes</em> les méthodes du bean ; {@code AnnotationReader} décide ensuite
	 * par méthode quelles politiques appliquer (no-op si aucune annotation FT
	 * pertinente n'est lue).</p>
	 *
	 * <p><strong>Pré-requis Vauban</strong> : exige le fix Vauban
	 * « propagation des @InterceptorBinding ajoutés en class-level vers
	 * BeanDescriptor.interceptorBindings » — sinon le binding ajouté ici est
	 * silencieusement ignoré par le container.</p>
	 */
	@Enhancement(
			types = Object.class,
			withSubtypes = true,
			withAnnotations = {
					Retry.class,
					Timeout.class,
					CircuitBreaker.class,
					Bulkhead.class,
					Asynchronous.class,
					Fallback.class
			}
	)
	public void addFaultToleranceBinding(ClassConfig classConfig) {
		String className = classConfig.info().name();
		if (!bindingEnhancedClasses.add(className)) {
			return;
		}
		validateClass(resolveClass(classConfig));
		classConfig.addAnnotation(FaultToleranceBinding.class);
	}

	private Class<?> resolveClass(ClassConfig classConfig) {
		String className = classConfig.info().name();
		try {
			return Class.forName(className, false, Thread.currentThread().getContextClassLoader());
		}
		catch (ClassNotFoundException e) {
			throw new FaultToleranceDefinitionException("Unable to load bean class for FT validation: " + className, e);
		}
	}

	/** Implémentation portable de {@link Priority} (pattern CDI {@link AnnotationLiteral}). */
	private static final class PriorityLiteral extends AnnotationLiteral<Priority> implements Priority {
		private static final long serialVersionUID = 1L;
		private final int value;
		PriorityLiteral(int value) { this.value = value; }
		@Override public int value() { return value; }
	}

	public void validateClass(Class<?> beanClass) {
		for (Method method : beanClass.getDeclaredMethods()) {
			validateFallback(beanClass, method);
			validateAsynchronous(method);
			validateBulkhead(method);
			validateCircuitBreaker(method);
			validateRetry(method);
			validateTimeout(method);
		}
	}

	void validateFallback(Class<?> beanClass, Method method) {
		Fallback fallback = annotationOn(method, Fallback.class);
		if (fallback != null) {
			fallbackResolver.validateDefinition(beanClass, method, fallback);
		}
	}

	void validateAsynchronous(Method method) {
		Asynchronous asynchronous = annotationOn(method, Asynchronous.class);
		if (asynchronous == null) {
			return;
		}

		Class<?> returnType = method.getReturnType();
		if (!CompletionStage.class.isAssignableFrom(returnType) && !Future.class.isAssignableFrom(returnType)) {
			throw new FaultToleranceDefinitionException(
					"@Asynchronous requires Future or CompletionStage return type: " + method
			);
		}
	}

	void validateBulkhead(Method method) {
		Bulkhead bulkhead = annotationOn(method, Bulkhead.class);
		if (bulkhead == null) {
			return;
		}
		if (bulkhead.value() < 1) {
			throw new FaultToleranceDefinitionException("@Bulkhead value must be >= 1: " + method);
		}
		if (annotationOn(method, Asynchronous.class) != null && bulkhead.waitingTaskQueue() < 0) {
			throw new FaultToleranceDefinitionException("@Bulkhead waitingTaskQueue must be >= 0 for @Asynchronous: " + method);
		}
	}

	void validateCircuitBreaker(Method method) {
		CircuitBreaker circuitBreaker = annotationOn(method, CircuitBreaker.class);
		if (circuitBreaker == null) {
			return;
		}
		double failureRatio = circuitBreaker.failureRatio();
		if (failureRatio < 0.0d || failureRatio > 1.0d) {
			throw new FaultToleranceDefinitionException("@CircuitBreaker failureRatio must be within [0,1]: " + method);
		}
		if (circuitBreaker.requestVolumeThreshold() < 1) {
			throw new FaultToleranceDefinitionException("@CircuitBreaker requestVolumeThreshold must be >= 1: " + method);
		}
		if (circuitBreaker.successThreshold() < 1) {
			throw new FaultToleranceDefinitionException("@CircuitBreaker successThreshold must be >= 1: " + method);
		}
	}

	void validateRetry(Method method) {
		Retry retry = annotationOn(method, Retry.class);
		if (retry == null) {
			return;
		}
		if (retry.maxRetries() < -1) {
			throw new FaultToleranceDefinitionException("@Retry maxRetries must be >= -1: " + method);
		}
		if (retry.delay() < 0) {
			throw new FaultToleranceDefinitionException("@Retry delay must be >= 0: " + method);
		}
		if (retry.jitter() < 0) {
			throw new FaultToleranceDefinitionException("@Retry jitter must be >= 0: " + method);
		}
		if (retry.maxDuration() < 0) {
			throw new FaultToleranceDefinitionException("@Retry maxDuration must be >= 0: " + method);
		}
		if (retry.maxDuration() > 0 && retry.maxDuration() < retry.delay()) {
			throw new FaultToleranceDefinitionException("@Retry maxDuration must be >= delay when maxDuration > 0: " + method);
		}
	}

	void validateTimeout(Method method) {
		Timeout timeout = annotationOn(method, Timeout.class);
		if (timeout == null) {
			return;
		}
		if (timeout.value() < 0) {
			throw new FaultToleranceDefinitionException("@Timeout value must be >= 0: " + method);
		}
	}

	private <A extends java.lang.annotation.Annotation> A annotationOn(Method method, Class<A> annotationType) {
		A annotation = method.getAnnotation(annotationType);
		if (annotation != null) {
			return annotation;
		}
		return method.getDeclaringClass().getAnnotation(annotationType);
	}
}




