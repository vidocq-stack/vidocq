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
package io.vidocq.runtime.extensions.essentials.migration.liquibase;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Serves a file by name under a {@code vauban:} URL whose handler only this URL object carries, and lists no
 * directory: what the Vauban loader of an application layer does (vidocq#96). Files and classes come from the
 * test's own loader.
 */
final class OpaqueUrlLoader extends ClassLoader {

    OpaqueUrlLoader() {
        super(OpaqueUrlLoader.class.getClassLoader());
    }

    @Override
    public URL getResource(String name) {
        URL real = super.getResource(name);
        if (real == null || isDirectory(real)) {
            return null;
        }
        URLStreamHandler handler = new URLStreamHandler() {
            @Override
            protected URLConnection openConnection(URL u) throws IOException {
                return real.openConnection();
            }
        };
        try {
            return URL.of(URI.create("vauban:test!/" + name), handler);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Enumeration<URL> getResources(String name) {
        URL url = getResource(name);
        return Collections.enumeration(url == null ? List.of() : List.of(url));
    }

    private static boolean isDirectory(URL url) {
        try {
            return "file".equals(url.getProtocol()) && Files.isDirectory(Path.of(url.toURI()));
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
