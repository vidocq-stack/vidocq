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
package io.vidocq.humboldt.tck.arquillian;

import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.Node;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Class loader of one deployment, handed to {@code OtelSpiAutoConfiguration.discover} so that each deployment
 * sees the OpenTelemetry autoconfigure SPI providers its archive declares — and only those.
 *
 * <p>Classes come from the parent: with the Arquillian <em>Local</em> protocol the archive's classes are the
 * ones already on the test class path. The {@code META-INF/services} declarations of the OpenTelemetry
 * autoconfigure SPIs ({@code io.opentelemetry.sdk.autoconfigure.spi.*}) come from the archive only
 * ({@code /META-INF/services/} for a JAR, {@code /WEB-INF/classes/META-INF/services/} for a WAR): the ones on the
 * test class path are hidden (opentelemetry-sdk-extension-autoconfigure declares a {@code ResourceProvider}, the
 * trace propagators extension declares propagator providers), so a deployment does not pick up providers it
 * did not ask for. Every other resource is looked up as usual.</p>
 */
final class DeploymentServicesClassLoader extends ClassLoader {

    /** Resource name prefix of the OpenTelemetry autoconfigure SPI service declarations. */
    static final String OTEL_SPI_SERVICES = "META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.";

    private static final List<String> SERVICES_DIRECTORIES =
            List.of("/META-INF/services", "/WEB-INF/classes/META-INF/services");

    /** OpenTelemetry autoconfigure SPI declarations of the archive: resource name → content. */
    private final Map<String, byte[]> declarations = new HashMap<>();

    DeploymentServicesClassLoader(Archive<?> archive, ClassLoader parent) {
        super("humboldt-deployment[" + archive.getName() + "]", parent);
        for (String directory : SERVICES_DIRECTORIES) {
            Node services = archive.get(directory);
            if (services == null) continue;
            for (Node declaration : services.getChildren()) {
                String path = declaration.getPath().get();
                String resourceName = "META-INF/services/" + path.substring(path.lastIndexOf('/') + 1);
                if (resourceName.startsWith(OTEL_SPI_SERVICES) && declaration.getAsset() != null) {
                    // A JAR-style declaration wins over a WAR-style one, as in the archive scanning it replaces.
                    declarations.putIfAbsent(resourceName, read(declaration));
                }
            }
        }
    }

    @Override
    public URL getResource(String name) {
        if (name.startsWith(OTEL_SPI_SERVICES)) {
            byte[] content = declarations.get(name);
            return content == null ? null : urlOf(name, content);
        }
        return super.getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        if (name.startsWith(OTEL_SPI_SERVICES)) {
            URL url = getResource(name);
            return url == null ? Collections.emptyEnumeration() : Collections.enumeration(List.of(url));
        }
        return super.getResources(name);
    }

    private static byte[] read(Node declaration) {
        try (InputStream in = declaration.getAsset().openStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + declaration.getPath().get(), e);
        }
    }

    private static URL urlOf(String name, byte[] content) {
        URLStreamHandler handler = new URLStreamHandler() {
            @Override
            protected URLConnection openConnection(URL url) {
                return new URLConnection(url) {
                    @Override
                    public void connect() {
                        connected = true;
                    }

                    @Override
                    public InputStream getInputStream() {
                        return new ByteArrayInputStream(content);
                    }
                };
            }
        };
        try {
            return URL.of(URI.create("humboldt-deployment:/" + name), handler);
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }
}
