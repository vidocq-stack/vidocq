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
package io.vidocq.runtime.extensions.jakartaee.web.foy;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.security.CodeSource;

/**
 * The jar or directory a component class comes from, for Foy's ordering exclusions (vidocq#228).
 *
 * <p>Foy attributes a class to the code source it was defined from. A loader may define a class from
 * one place and resolve its class file from another: the TCK harness loads classes parent-first from
 * the TCK jar but resolves resources from the war's own jars first. The class file the application's
 * loader resolves names the jar the application ships the class in, so a {@code @WebListener} of a web
 * fragment an absolute ordering excludes is dropped with that fragment. Where both agree, as in a
 * deployed application, nothing changes.
 */
final class ComponentSources {

    private ComponentSources() {}

    /**
     * The root holding {@code type}'s class file as {@code loader} resolves it: {@code jar:...!/} for a
     * jar, the directory otherwise; {@code type}'s code source when {@code loader} does not see the file.
     */
    static URL classFile(ClassLoader loader, Class<?> type) {
        String resource = type.getName().replace('.', '/') + ".class";
        URL file = loader.getResource(resource);
        String spelling = file == null ? null : file.toString();
        if (spelling != null && spelling.endsWith(resource)) {
            try {
                return URI.create(spelling.substring(0, spelling.length() - resource.length())).toURL();
            } catch (IllegalArgumentException | MalformedURLException _) {
                // Not a URL once cut: fall back to the code source.
            }
        }
        CodeSource source = type.getProtectionDomain().getCodeSource();
        return source == null ? null : source.getLocation();
    }
}
