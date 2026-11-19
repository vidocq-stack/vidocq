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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.vauban.api.ExtensionPhase;
import jakarta.annotation.Priority;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.FieldConfig;
import jakarta.enterprise.inject.build.compatible.spi.MethodConfig;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.AnnotationMember;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.interceptor.Interceptor;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * MP Fault Tolerance 4.1, "Fault Tolerance Interceptor(s)": what the extension asks the container for the Fault
 * Tolerance interceptor, from {@code mp.fault.tolerance.interceptor.priority} (heisenberg#66). The value belongs
 * to the configuration the application starts with: nothing is asked while the application builds.
 */
class InterceptorPriorityExtensionTest {

    private static final String PRIORITY = "mp.fault.tolerance.interceptor.priority";

    private final InterceptorPriorityExtension extension = new InterceptorPriorityExtension();
    private final RecordingClassConfig interceptor = new RecordingClassConfig();

    @Test
    void atStartTheConfiguredPriorityReplacesTheDeclaredOne() throws Exception {
        try (var _ = TestConfigs.install(Map.of(PRIORITY, "5000"))) {
            extension.configureInterceptorPriority(interceptor);
        }

        assertEquals(1, interceptor.removals.size());
        Predicate<AnnotationInfo> removed = interceptor.removals.getFirst();
        assertTrue(removed.test(new NamedAnnotation(Priority.class.getName())), "the declared @Priority is removed");
        assertFalse(removed.test(new NamedAnnotation(Interceptor.class.getName())), "and nothing else");
        assertEquals(1, interceptor.additions.size());
        assertEquals(5000, assertInstanceOf(Priority.class, interceptor.additions.getFirst()).value());
    }

    @Test
    void theDefaultPriorityIsLeftAsDeclared() throws Exception {
        try (var _ = TestConfigs.install(Map.of())) {
            extension.configureInterceptorPriority(interceptor);
        }

        assertTrue(interceptor.untouched());
    }

    /** The build machine's configuration is not the application's: the build must not freeze a priority read there. */
    @Test
    void atBuildTimeThePriorityIsLeftToTheStart() throws Exception {
        try (var _ = TestConfigs.install(Map.of(PRIORITY, "5000"))) {
            ExtensionPhase.atBuildTime(() -> {
                extension.configureInterceptorPriority(interceptor);
                return null;
            });
        }

        assertTrue(interceptor.untouched());
    }

    /** Records what an extension asks for a class; the members of the class are not looked at. */
    private static final class RecordingClassConfig implements ClassConfig {
        final List<Annotation> additions = new ArrayList<>();
        final List<Predicate<AnnotationInfo>> removals = new ArrayList<>();

        boolean untouched() {
            return additions.isEmpty() && removals.isEmpty();
        }

        @Override
        public ClassConfig addAnnotation(Annotation annotation) {
            additions.add(annotation);
            return this;
        }

        @Override
        public ClassConfig removeAnnotation(Predicate<AnnotationInfo> predicate) {
            removals.add(predicate);
            return this;
        }

        @Override
        public ClassInfo info() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ClassConfig addAnnotation(Class<? extends Annotation> annotationType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ClassConfig addAnnotation(AnnotationInfo annotation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ClassConfig removeAllAnnotations() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Collection<MethodConfig> constructors() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Collection<MethodConfig> methods() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Collection<FieldConfig> fields() {
            throw new UnsupportedOperationException();
        }
    }

    /** An annotation known by its type name only, which is all a removal predicate reads here. */
    private record NamedAnnotation(String name) implements AnnotationInfo {

        @Override
        public ClassInfo declaration() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean hasMember(String member) {
            return false;
        }

        @Override
        public AnnotationMember member(String member) {
            return null;
        }

        @Override
        public Map<String, AnnotationMember> members() {
            return Map.of();
        }
    }
}
