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
package io.vidocq.ravel.internal;

import io.vidocq.ravel.spi.LayeredConfigProviderResolver;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLClassLoader;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@DisplayName("RavelConfigProviderResolver - releasing the configurations of a layer")
class RavelLayeredReleaseTest {

    private static ClassLoader loader(ClassLoader parent) {
        return new URLClassLoader(new URL[0], parent);
    }

    @Test
    @DisplayName("the resolver of Ravel is a LayeredConfigProviderResolver")
    void resolverIsLayered() {
        assertInstanceOf(LayeredConfigProviderResolver.class, new RavelConfigProviderResolver());
    }

    @Test
    @DisplayName("releases the root and every descendant loader, and only those")
    void releasesTheRootAndItsDescendants() {
        RavelConfigProviderResolver resolver = new RavelConfigProviderResolver();
        ClassLoader root = loader(getClass().getClassLoader());
        ClassLoader child = loader(root);
        ClassLoader grandChild = loader(child);
        ClassLoader unrelated = loader(getClass().getClassLoader());
        Config rootConfig = resolver.getConfig(root);
        Config childConfig = resolver.getConfig(child);
        Config grandChildConfig = resolver.getConfig(grandChild);
        Config unrelatedConfig = resolver.getConfig(unrelated);

        resolver.releaseConfigs(root);

        assertNotSame(rootConfig, resolver.getConfig(root));
        assertNotSame(childConfig, resolver.getConfig(child));
        assertNotSame(grandChildConfig, resolver.getConfig(grandChild));
        assertSame(unrelatedConfig, resolver.getConfig(unrelated));
    }

    @Test
    @DisplayName("a null root, or a loader the resolver never saw, releases nothing")
    void nothingToRelease() {
        RavelConfigProviderResolver resolver = new RavelConfigProviderResolver();
        ClassLoader known = loader(getClass().getClassLoader());
        Config knownConfig = resolver.getConfig(known);

        resolver.releaseConfigs(null);
        resolver.releaseConfigs(loader(getClass().getClassLoader()));

        assertSame(knownConfig, resolver.getConfig(known));
    }

    @Test
    @DisplayName("a released layer, and the loaders built on it, can be garbage collected")
    void releasedLayerIsCollectable() {
        RavelConfigProviderResolver resolver = new RavelConfigProviderResolver();
        WeakReference<ClassLoader> root = readAndRelease(resolver);

        for (int i = 0; i < 50 && root.get() != null; i++) {
            System.gc();
        }
        assertNull(root.get(), "the registry still reaches the released layer");
    }

    private WeakReference<ClassLoader> readAndRelease(RavelConfigProviderResolver resolver) {
        ClassLoader root = loader(getClass().getClassLoader());
        ClassLoader composite = loader(root);
        resolver.getConfig(root);
        resolver.getConfig(composite);
        resolver.releaseConfigs(root);
        return new WeakReference<>(root);
    }
}
