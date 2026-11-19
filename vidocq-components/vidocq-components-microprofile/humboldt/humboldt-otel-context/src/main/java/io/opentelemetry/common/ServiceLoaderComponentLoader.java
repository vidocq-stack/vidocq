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
package io.opentelemetry.common;

import java.util.ServiceLoader;

/**
 * Humboldt's own version of the OpenTelemetry class of the same name, which humboldt-otel-context ships in
 * place of the upstream one (excluded from the shaded {@code opentelemetry-common}): the {@link ComponentLoader}
 * that {@link ComponentLoader#forClassLoader(ClassLoader)} returns, used by every OpenTelemetry component that
 * is given no other loader.
 *
 * <p>Same contract as upstream (OpenTelemetry 1.66): a package-private class built from a {@link ClassLoader},
 * whose {@link #load(Class)} returns {@code ServiceLoader.load(spiClass, classLoader)} and whose
 * {@link #toString()} has the same form.</p>
 *
 * <p>One difference. Humboldt puts this package in the explicit module {@code io.opentelemetry.context}, and
 * {@link ServiceLoader} requires its caller's module to declare {@code uses} for the service. That module cannot
 * declare, statically, the services of optional OpenTelemetry artifacts — an exporter's {@code Compressor} or
 * {@code HttpSenderProvider}: their modules may be absent. Upstream the jar is an automatic module, which may use
 * any service. So {@link #load(Class)} first adds the service dependence from inside the module with
 * {@link Module#addUses(Class)} (a module may only update its own uses), then loads. On the class path the
 * module is unnamed and {@code addUses} does nothing (BUG-20261004-04).</p>
 */
class ServiceLoaderComponentLoader implements ComponentLoader {

    private final ClassLoader classLoader;

    ServiceLoaderComponentLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    @Override
    public <T> Iterable<T> load(Class<T> spiClass) {
        ServiceLoaderComponentLoader.class.getModule().addUses(spiClass);
        return ServiceLoader.load(spiClass, classLoader);
    }

    @Override
    public String toString() {
        return "ServiceLoaderComponentLoader{classLoader=" + classLoader + "}";
    }
}
