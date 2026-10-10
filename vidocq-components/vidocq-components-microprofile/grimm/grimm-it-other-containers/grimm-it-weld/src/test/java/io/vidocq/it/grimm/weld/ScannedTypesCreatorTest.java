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
package io.vidocq.it.grimm.weld;

import io.vidocq.grimm.cdi.ScannedTypes;
import io.vidocq.grimm.cdi.ScannedTypesCreator;
import io.vidocq.grimm.spi.gen.OpenApiContribution;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ScannedTypesCreator} adds the resources {@code grimm-processor} described to the classes the
 * extension saw (grimm#22): in {@code annotated} discovery mode a {@code @Path} class with no
 * bean-defining annotation is not a bean, so only its generated contribution names it.
 */
class ScannedTypesCreatorTest {

    /** A resource no CDI scan saw: in {@code annotated} mode, not a bean. */
    public static final class NotABeanResource {
    }

    /** What grimm-processor generates for {@link NotABeanResource}. */
    public static final class NotABeanContribution implements OpenApiContribution {
        @Override
        public Class<?> resourceClass() {
            return NotABeanResource.class;
        }

        @Override
        public String openApiJson() {
            return "{}";
        }
    }

    @Test
    void addsTheResourcesTheProcessorDescribed(@TempDir Path dir) throws IOException {
        Path services = dir.resolve("services");
        Files.writeString(services, NotABeanContribution.class.getName() + "\n");
        ClassLoader withServices = new ClassLoader(getClass().getClassLoader()) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                if (name.equals("META-INF/services/" + OpenApiContribution.class.getName())) {
                    return Collections.enumeration(List.of(services.toUri().toURL()));
                }
                return super.getResources(name);
            }
        };

        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(withServices);
        try {
            ScannedTypes created = new ScannedTypesCreator().create(null, names(String.class.getName()));
            assertEquals(List.of(String.class, NotABeanResource.class), created.classes());
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private static Parameters names(String... names) {
        return new Parameters() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> T get(String key, Class<T> type) {
                return (T) names;
            }

            @Override
            public <T> T get(String key, Class<T> type, T defaultValue) {
                return get(key, type);
            }
        };
    }
}
