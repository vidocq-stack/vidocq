/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.heisenberg.cdi.internal;

import io.vidocq.heisenberg.internal.Companions;
import io.vidocq.heisenberg.internal.FallbackResolver;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
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
 * <p>Everything it does depends on the classes of the deployment only, so a build may freeze its result. The
 * priority of the interceptor depends on the configuration the application starts with: it is the work of
 * {@link InterceptorPriorityExtension}, an extension of its own that runs when the container starts.</p>
 */
public class HeisenbergExtension implements BuildCompatibleExtension {

    static {
        // Fallback methods of application modules are resolved through Vauban's module lookups.
        VaubanLookupSource.install();
    }

	private final FallbackResolver fallbackResolver = new FallbackResolver();
	private final Set<String> bindingEnhancedClasses = ConcurrentHashMap.newKeySet();

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
		Class<?> beanClass = resolveClass(classConfig);
		if (beanClass != null) {
			validateUnlessCheckedAtBuild(beanClass);
		}
		classConfig.addAnnotation(FaultToleranceBinding.class);
	}

	/**
	 * Loads the bean class for FT validation, or returns {@code null} when it is
	 * not loadable in the current context. This happens when the BCE runs inside
	 * the annotation processor (build-time codegen discovers it through
	 * {@code META-INF/services}): the classes under compilation are not on any
	 * class loader yet. The binding annotation is still added — validation is
	 * deferred to the runtime boot, where the class is loadable.
	 */
	private Class<?> resolveClass(ClassConfig classConfig) {
		String className = classConfig.info().name();
		try {
			return Class.forName(className, false, Thread.currentThread().getContextClassLoader());
		}
		catch (ClassNotFoundException _) {
			return null;
		}
	}

	/**
	 * Validates the definitions of {@code beanClass} by reflection, unless the Heisenberg processor did when it
	 * compiled the class (vidocq-workspace#40, item 6): its companion then says {@code validated()}, and the
	 * definition errors were compile errors. A class without a companion keeps this portable path.
	 */
	void validateUnlessCheckedAtBuild(Class<?> beanClass) {
		if (!Companions.validatedAtBuild(beanClass)) {
			validateClass(beanClass);
		}
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




