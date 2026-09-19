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
package io.vidocq.runtime.extensions.essentials.migration.flyway;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Serves a file by name, as the Vauban loader of an application layer does, and lists no directory, as it
 * does not either: {@code getResources("db/migration")} finds nothing (vidocq#96). Classes and files come
 * from the test's own loader.
 */
final class NameOnlyLoader extends ClassLoader {

    NameOnlyLoader() {
        super(NameOnlyLoader.class.getClassLoader());
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        URL url = getResource(name);
        return Collections.enumeration(url == null || isDirectory(url) ? List.of() : List.of(url));
    }

    private static boolean isDirectory(URL url) {
        try {
            return "file".equals(url.getProtocol()) && Files.isDirectory(Path.of(url.toURI()));
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
