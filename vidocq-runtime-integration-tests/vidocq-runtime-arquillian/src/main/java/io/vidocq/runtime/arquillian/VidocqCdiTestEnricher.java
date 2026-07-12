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
package io.vidocq.runtime.arquillian;

import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.inject.Inject;
import jakarta.inject.Qualifier;
import org.jboss.arquillian.test.spi.TestEnricher;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;

/**
 * Enriches Arquillian test class instances through the public
 * {@link VaubanContainer} lookup API: every {@code @Inject} field is resolved
 * against the running container with its CDI qualifiers ({@code @RestClient},
 * {@code @ConfigProperty}, {@code @Claim}, ...). No implementation internals
 * are touched; reflection is limited to the test class's own fields, which is
 * the standard Arquillian enrichment contract.
 */
public class VidocqCdiTestEnricher implements TestEnricher {

    @Override
    public void enrich(Object testCase) {
        VaubanContainer container = VaubanContainer.current();
        if (container == null || testCase == null) {
            return;
        }
        // Enrichment runs once per test method — recycle the request context so
        // every test starts with fresh @RequestScoped state, exactly like a
        // servlet-based Arquillian container serving each test in its own HTTP
        // request (the FT TCK's stateful services rely on it).
        var requestContext = container.requestContext();
        if (requestContext.isActive()) {
            requestContext.deactivate();
        }
        requestContext.activate();
        for (Class<?> type = testCase.getClass(); type != null && type != Object.class;
                type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!field.isAnnotationPresent(Inject.class)) {
                    continue;
                }
                Object value = container.resolveParameter(field.getType(), field.getGenericType(),
                        null, qualifiersOf(field), field);
                try {
                    field.setAccessible(true);
                    field.set(testCase, value);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(
                            "Cannot inject test field " + type.getName() + "#" + field.getName(), e);
                }
            }
        }
    }

    @Override
    public Object[] resolve(Method method) {
        // TCK test methods may declare CDI-resolvable parameters, e.g.
        // metricInjectionIntoTest(@Metric Counter counter) in the MP Metrics TCK.
        // Resolve what the container can provide; leave the rest null so other
        // registered enrichers (@ArquillianResource, ...) can fill their slots.
        Object[] values = new Object[method.getParameterCount()];
        VaubanContainer container = VaubanContainer.current();
        if (container == null) {
            return values;
        }
        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            try {
                values[i] = container.resolveParameter(parameter.getType(),
                        parameter.getParameterizedType(), null, qualifiersOf(parameter),
                        method, null, parameter, i);
            } catch (RuntimeException e) {
                values[i] = null;
            }
        }
        return values;
    }

    private static Annotation[] qualifiersOf(AnnotatedElement element) {
        List<Annotation> qualifiers = new ArrayList<>();
        for (Annotation annotation : element.getAnnotations()) {
            if (annotation.annotationType().isAnnotationPresent(Qualifier.class)) {
                qualifiers.add(annotation);
            }
        }
        return qualifiers.toArray(Annotation[]::new);
    }
}
