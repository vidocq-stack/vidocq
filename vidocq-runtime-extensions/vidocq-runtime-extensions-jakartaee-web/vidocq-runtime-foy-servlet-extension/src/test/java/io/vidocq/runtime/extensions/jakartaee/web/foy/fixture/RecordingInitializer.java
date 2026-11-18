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
package io.vidocq.runtime.extensions.jakartaee.web.foy.fixture;

import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletContext;
import jakarta.servlet.annotation.HandlesTypes;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Keeps the classes its last start was handed: the {@link Runnable} classes of the application (Servlet 6.1 §8.2.4). */
@HandlesTypes(Runnable.class)
public class RecordingInitializer implements ServletContainerInitializer {

    /** The classes of the last start, empty when it got none. */
    public static final AtomicReference<Set<Class<?>>> RECEIVED = new AtomicReference<>(Set.of());

    @Override
    public void onStartup(Set<Class<?>> classes, ServletContext context) {
        RECEIVED.set(classes == null ? Set.of() : Set.copyOf(classes));
    }
}
