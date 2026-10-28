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
package io.vidocq.humboldt.cdi;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.MethodConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link HumboldtBuildCompatibleExtension} binds {@link SpanBinding} where {@code @WithSpan} is declared.
 */
class HumboldtBuildCompatibleExtensionTest {

    private static final String ADD_ANNOTATION = "addAnnotation";

    private final List<String> added = new ArrayList<>();

    @Test
    void class_level_with_span_binds_the_class() {
        new HumboldtBuildCompatibleExtension().addSpanBinding(classConfig("Traced", true, List.of()));

        assertEquals(List.of("Traced"), added);
    }

    @Test
    void only_the_annotated_methods_are_bound() {
        ClassConfig config = classConfig("Mixed", false,
                List.of(methodConfig("traced", true), methodConfig("plain", false)));

        new HumboldtBuildCompatibleExtension().addSpanBinding(config);

        assertEquals(List.of("traced"), added);
    }

    private ClassConfig classConfig(String name, boolean annotated, List<MethodConfig> methods) {
        Object info = annotationTarget(jakarta.enterprise.lang.model.declarations.ClassInfo.class, name, annotated);
        return proxy(ClassConfig.class, (self, method, args) -> switch (method.getName()) {
            case "info" -> info;
            case "methods" -> methods;
            case ADD_ANNOTATION -> record(name, args, self);
            default -> null;
        });
    }

    private MethodConfig methodConfig(String name, boolean annotated) {
        Object info = annotationTarget(jakarta.enterprise.lang.model.declarations.MethodInfo.class, name, annotated);
        return proxy(MethodConfig.class, (self, method, args) -> switch (method.getName()) {
            case "info" -> info;
            case ADD_ANNOTATION -> record(name, args, self);
            default -> null;
        });
    }

    private Object record(String name, Object[] args, Object self) {
        assertEquals(SpanBinding.class, args[0]);
        added.add(name);
        return self;
    }

    /** A ClassInfo / MethodInfo fake: only {@code name()} and {@code hasAnnotation(WithSpan.class)}. */
    private static Object annotationTarget(Class<?> type, String name, boolean annotated) {
        return proxy(type, (_, method, args) -> switch (method.getName()) {
                    case "name" -> name;
                    case "hasAnnotation" -> annotated && args[0] == WithSpan.class;
                    default -> null;
                });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(HumboldtBuildCompatibleExtensionTest.class.getClassLoader(),
                new Class<?>[]{type}, handler);
    }
}
