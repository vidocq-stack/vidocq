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
package io.vidocq.runtime.core;

import io.vidocq.runtime.spi.VidocqApp;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * An application class with a bounded type variable. It is a bean, necessarily {@code @Dependent} (CDI 4.1, managed
 * beans), and its raw class is not one of the types it can be looked up by (CDI 4.1 section 5.2.4). It declares a
 * producer of a parameterized type: a producer is a bean of the class that declares it too, and it is not the
 * application.
 *
 * @param <T> unused by the application
 */
@Dependent
public class BoundedApplication<T extends Number> implements VidocqApp {

    static final AtomicInteger DESTROYED = new AtomicInteger();

    @Inject
    ApplicationCollaborator collaborator;

    @Produces
    Supplier<Integer> answer() {
        return () -> 1;
    }

    @PreDestroy
    void destroyed() {
        DESTROYED.incrementAndGet();
    }

    /** Returns 1 when the container made this instance, 0 when its constructor alone did. */
    @Override
    public int run(String... args) {
        return collaborator == null ? 0 : collaborator.answer();
    }
}
