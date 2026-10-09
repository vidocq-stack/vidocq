/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.runtime.it.mansart;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;

final class PersistenceTestDeployment {
    private static final String MANAGED_ACCESS_SERVICE =
            "META-INF/services/io.vidocq.mansart.jpa.core.spi.ManagedAccessProvider";
    private static final String VAUBAN_COMPONENTS_SERVICE =
            "META-INF/services/io.vidocq.vauban.api.VaubanComponentProvider";

    private PersistenceTestDeployment() {
    }

    static JavaArchive create(String archiveName) {
        return ShrinkWrap.create(JavaArchive.class, archiveName)
                .addClass(PersistenceRecord.class)
                .addClass(PersistenceResource.class)
                .addClass(TestDataSource.class)
                .addClass(generated("io.vidocq.runtime.it.mansart._MansartJpaAccess"))
                .addClass(generated("io.vidocq.runtime.it.mansart._VaubanComponents"))
                .addAsResource("META-INF/persistence.xml")
                .addAsResource(MANAGED_ACCESS_SERVICE)
                .addAsResource(VAUBAN_COMPONENTS_SERVICE);
    }

    private static Class<?> generated(String className) {
        try {
            return Class.forName(className, true, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException("Required generated deployment class is missing: " + className, failure);
        }
    }
}
