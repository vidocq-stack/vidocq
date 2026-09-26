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
package io.vidocq.runtime.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * The jars that are development tools (Vidocq/vidocq#143): their manifest says {@code Vidocq-Dev-Only: true}. Only
 * {@code vidocq:dev} adds them; {@code vidocq:run} and the packaging goals drop them, even when the project declares
 * them. A jar that cannot be read, or has no manifest, is not dev-only: the filter never fails a build.
 */
public final class DevOnlyJars {

    /** The manifest's main attribute that marks a dev-only jar. */
    public static final String MANIFEST_ENTRY = "Vidocq-Dev-Only";

    private DevOnlyJars() {
    }

    public static boolean isDevOnly(Path jar) {
        if (jar == null || !Files.isRegularFile(jar)) {
            return false;
        }
        try (JarFile file = new JarFile(jar.toFile())) {
            Manifest manifest = file.getManifest();
            return manifest != null
                    && "true".equalsIgnoreCase(manifest.getMainAttributes().getValue(MANIFEST_ENTRY));
        } catch (IOException | SecurityException unreadable) {
            return false;
        }
    }

    public static String droppedWarning(String artifactId) {
        return artifactId + " is dev-only: not packaged; remove the dependency, vidocq:dev brings it";
    }
}
