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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/**
 * The {@value #RESOURCE} a first-party extension ships (Vidocq/vidocq#314): its short id, description,
 * codegen bundle and the {@code module-info} directives the Vidocq CLI writes. The CLI builds its extension
 * catalog from these descriptors; checkpom reads the codegen bundle from them.
 */
final class ExtensionDescriptor {

    static final String RESOURCE = "META-INF/vidocq/extension.properties";

    private final Properties properties;

    private ExtensionDescriptor(Properties properties) {
        this.properties = properties;
    }

    /**
     * The descriptor of an extension jar, or of a reactor module's {@code target/classes}; empty when there is
     * none, as for a third-party extension, or when it cannot be read.
     */
    static Optional<ExtensionDescriptor> of(Path artifact) {
        if (artifact == null) {
            return Optional.empty();
        }
        try {
            if (Files.isDirectory(artifact)) {
                Path resource = artifact.resolve(RESOURCE);
                if (!Files.isRegularFile(resource)) {
                    return Optional.empty();
                }
                try (InputStream in = Files.newInputStream(resource)) {
                    return Optional.of(load(in));
                }
            }
            if (!Files.isRegularFile(artifact)) {
                return Optional.empty();
            }
            try (JarFile jar = new JarFile(artifact.toFile())) {
                ZipEntry entry = jar.getEntry(RESOURCE);
                if (entry == null) {
                    return Optional.empty();
                }
                try (InputStream in = jar.getInputStream(entry)) {
                    return Optional.of(load(in));
                }
            }
        } catch (IOException _) {
            // An unreadable artifact describes nothing.
            return Optional.empty();
        }
    }

    private static ExtensionDescriptor load(InputStream in) throws IOException {
        Properties properties = new Properties();
        properties.load(in);
        return new ExtensionDescriptor(properties);
    }

    /** The artifactId of the extension's codegen bundle, which shares its groupId and version; empty without one. */
    Optional<String> codegen() {
        return Optional.ofNullable(properties.getProperty("codegen")).map(String::strip).filter(s -> !s.isEmpty());
    }
}
