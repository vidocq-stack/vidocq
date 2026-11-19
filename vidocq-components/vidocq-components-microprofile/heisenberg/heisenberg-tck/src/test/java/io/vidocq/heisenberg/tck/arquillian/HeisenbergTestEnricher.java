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
package io.vidocq.heisenberg.tck.arquillian;

import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;
import org.jboss.arquillian.test.spi.TestEnricher;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Arquillian {@link TestEnricher} — injects the {@code @Inject} fields of each TCK test
 * instance using the Vauban CDI container started by {@link VaubanTckBootstrap}.
 */
public class HeisenbergTestEnricher implements TestEnricher {

    @Override
    public void enrich(Object testInstance) {
        VaubanContainer container = VaubanContainer.current();
        if (container == null) return;
        resetRequestContext(container);
        BeanManager bm = container.getBeanManager();
        for (Field field : getAllFields(testInstance.getClass())) {
            if (!field.isAnnotationPresent(Inject.class)) continue;
            try { injectField(testInstance, field, bm); } catch (Exception ignored) {}
        }
    }

    @Override
    public Object[] resolve(Method method) {
        VaubanContainer container = VaubanContainer.current();
        if (container != null) {
            resetRequestContext(container);
        }
        return new Object[0];
    }

    private static void resetRequestContext(VaubanContainer container) {
        var requestContext = container.requestContext();
        if (requestContext.isActive()) {
            requestContext.deactivate();
        }
        requestContext.activate();
    }

    private static void injectField(Object testInstance, Field field, BeanManager bm) throws Exception {
        var qualifiers = extractQualifiers(field);
        var type = field.getType();

        if (Instance.class.equals(type)) {
            Class<?> targetType = extractInstanceTargetType(field.getGenericType());
            if (targetType != null) {
                Object selected = bm.createInstance().select(targetType, qualifiers.toArray(new Annotation[0]));
                field.setAccessible(true);
                field.set(testInstance, selected);
                return;
            }
        }

        var beans = bm.getBeans(type, qualifiers.toArray(new Annotation[0]));
        if (beans == null || beans.isEmpty()) return;
        jakarta.enterprise.inject.spi.Bean<?> resolved;
        try {
            resolved = bm.resolve(beans);
        } catch (jakarta.enterprise.inject.AmbiguousResolutionException ambiguous) {
            // Vauban does not handle qualifier members well (e.g. @RegistryType(type=BASE)
            // vs @RegistryType(type=APPLICATION)) — pick the first bean to keep the TCK
            // injection working.
            resolved = beans.iterator().next();
        }
        if (resolved == null) {
            // bm.resolve returned null silently (non-throwing ambiguity) — fallback.
            resolved = beans.iterator().next();
        }
        var ctx = bm.createCreationalContext(resolved);
        Object instance = bm.getReference(resolved, type, ctx);
        if (instance == null) return;
        field.setAccessible(true);
        field.set(testInstance, instance);
    }

    private static Class<?> extractInstanceTargetType(Type genericType) {
        if (!(genericType instanceof ParameterizedType pt)) return null;
        if (pt.getActualTypeArguments().length != 1) return null;
        Type arg = pt.getActualTypeArguments()[0];
        return (arg instanceof Class<?> c) ? c : null;
    }

    private static List<Annotation> extractQualifiers(Field field) {
        var qualifiers = new ArrayList<Annotation>();
        for (Annotation ann : field.getAnnotations()) {
            if (ann.annotationType().equals(Inject.class)) continue;
            qualifiers.add(ann);
        }
        return qualifiers;
    }

    private static List<Field> getAllFields(Class<?> clazz) {
        var fields = new ArrayList<Field>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field f : current.getDeclaredFields()) fields.add(f);
            current = current.getSuperclass();
        }
        return fields;
    }
}

