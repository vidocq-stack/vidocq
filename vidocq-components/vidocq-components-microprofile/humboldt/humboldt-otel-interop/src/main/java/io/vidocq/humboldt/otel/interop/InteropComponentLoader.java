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
package io.vidocq.humboldt.otel.interop;

import io.opentelemetry.common.ComponentLoader;

import java.util.ServiceLoader;

/**
 * {@link ComponentLoader} that looks services up from this module, so that it works on the module path.
 *
 * <p>The OpenTelemetry default, {@code ComponentLoader.forClassLoader}, calls {@code ServiceLoader.load} from
 * {@code io.opentelemetry.context} (Humboldt ships {@code opentelemetry-common} inside that explicit module).
 * {@code ServiceLoader} requires its caller's module to declare {@code uses} for the service, and
 * {@code io.opentelemetry.context} cannot declare the services of optional OpenTelemetry components (an
 * exporter's {@code HttpSenderProvider}, for example): on the module path every such lookup fails with a
 * {@code ServiceConfigurationError} (BUG-20261004-01).</p>
 *
 * <p>Here the caller is this module, which adds the service dependence itself through
 * {@link Module#addUses(Class)} — allowed because a module may only update its own uses — before loading.
 * On the class path the module is unnamed: {@code addUses} does nothing and no check applies.</p>
 */
final class InteropComponentLoader implements ComponentLoader {

    private final ClassLoader classLoader;

    InteropComponentLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    @Override
    public <T> Iterable<T> load(Class<T> spiClass) {
        InteropComponentLoader.class.getModule().addUses(spiClass);
        return ServiceLoader.load(spiClass, classLoader);
    }

    @Override
    public String toString() {
        return "InteropComponentLoader{classLoader=" + classLoader + '}';
    }
}
