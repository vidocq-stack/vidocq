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
package io.vidocq.ravel.spi;

/**
 * Implemented by the {@code ConfigProviderResolver} of Ravel: lets a runtime that boots the application in a
 * layer of its own, with a class loader of its own, take the configuration of that layer out of the resolver
 * when the layer stops.
 *
 * <p>The resolver keeps one {@code Config} per class loader for the life of the JVM, and a {@code Config}
 * reaches the application's classes (its custom sources and converters, the converters generated at build),
 * so a layer that is never released stays reachable after it stopped. The standard
 * {@code ConfigProviderResolver#releaseConfig(Config)} needs the {@code Config} itself, while a container
 * reads its configuration under several context class loaders (the layer's, and the ones the container builds
 * on top of it).</p>
 */
public interface LayeredConfigProviderResolver {

    /**
     * Releases the configuration registered for {@code root} and for every class loader that has {@code root}
     * among its ancestors (parent chain). The configuration of an unrelated class loader is left alone, and so
     * is a class loader the resolver never saw.
     *
     * @param root the class loader of the layer that stops; {@code null} releases nothing
     */
    void releaseConfigs(ClassLoader root);
}
