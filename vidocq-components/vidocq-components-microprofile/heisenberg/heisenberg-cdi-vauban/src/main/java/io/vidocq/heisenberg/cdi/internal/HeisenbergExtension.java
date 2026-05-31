package io.vidocq.heisenberg.cdi.internal;

import io.vidocq.heisenberg.internal.ConfigResolver;
import io.vidocq.heisenberg.internal.FallbackResolver;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
 * Vauban Build Compatible Extension for Heisenberg — validates at container startup
 * the consistency of MicroProfile Fault Tolerance 4.1 annotations:
 * <ul>
 *   <li>{@code @Fallback(fallbackMethod=...)}: existing method with a compatible signature.</li>
 *   <li>{@code @Asynchronous}: return type {@code CompletionStage} or {@code Future}.</li>
 *   <li>{@code @Bulkhead} + {@code @Asynchronous}: {@code waitingTaskQueue} ≥ 0.</li>
 * </ul>
 *
 * <p>M8 §9: reads the MP Config property
 * {@code mp.fault.tolerance.interceptor.priority} <strong>exactly once at startup</strong> and applies it to the {@link FaultToleranceInterceptor}
 * via {@link Enhancement} (rewriting the {@link Priority} annotation).</p>
 */
public class HeisenbergExtension implements BuildCompatibleExtension {

	private final FallbackResolver fallbackResolver = new FallbackResolver();
	private final Set<String> bindingEnhancedClasses = ConcurrentHashMap.newKeySet();

	/**
	 * M8 §9: applies at startup the priority configured through MP Config
	 * (key {@code mp.fault.tolerance.interceptor.priority}). The property is read
	 * only once; if it is absent, the default priority
	 * {@link ConfigResolver#DEFAULT_INTERCEPTOR_PRIORITY} (4010) remains in effect.
	 */
	@Enhancement(types = FaultToleranceInterceptor.class)
	public void configureInterceptorPriority(ClassConfig classConfig) {
		int priority = ConfigResolver.interceptorPriority();
		if (priority == ConfigResolver.DEFAULT_INTERCEPTOR_PRIORITY) {
			return; // default priority already declared statically
		}
		classConfig.removeAnnotation(a -> Priority.class.getName().equals(a.name()));
		classConfig.addAnnotation(new PriorityLiteral(priority));
	}

	/**
	 * Adds the marker binding {@link FaultToleranceBinding} to any class carrying
	 * a MicroProfile Fault Tolerance 4.1 annotation
	 * ({@code @Retry @Timeout @CircuitBreaker @Bulkhead @Asynchronous @Fallback}) —
	 * directly or on one of its methods. Without this enhancement, the
	 * {@link FaultToleranceInterceptor} (which declares only
	 * {@code @FaultToleranceBinding}) would never be selected by CDI for these
	 * beans: Jakarta Interceptors spec §2.6 requires that <em>at least one</em> interceptor
	 * binding be present on the target.
	 *
	 * <p>The addition is done at class level — the interceptor therefore executes on
	 * <em>all</em> methods of the bean; {@code AnnotationReader} then decides, method by method,
	 * which policies to apply (no-op if no relevant FT annotation
	 * is read).</p>
	 *
	 * <p><strong>Vauban prerequisite</strong>: requires the Vauban fix
	 * "propagation of class-level added @InterceptorBinding annotations to
	 * BeanDescriptor.interceptorBindings" — otherwise the binding added here is
	 * silently ignored by the container.</p>
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

	/** Portable implementation of {@link Priority} (CDI {@link AnnotationLiteral} pattern). */
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
			if (Modifier.isAbstract(beanClass.getModifiers())
					&& fallback.fallbackMethod() != null
					&& !fallback.fallbackMethod().isEmpty()) {
				return;
			}
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




