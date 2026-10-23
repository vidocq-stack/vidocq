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
package io.vidocq.runtime.tooling;

import java.lang.module.FindException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Which jars are Vidocq extensions: those whose module provides {@value #SPI}. The CLI asks it of the jars Maven
 * resolves for it, the Maven plugin of the project's artifacts (Vidocq/vidocq#314).
 */
public final class ExtensionJars {

    /** The service every Vidocq extension provides. */
    public static final String SPI = "io.vidocq.runtime.spi.VidocqExtension";

    private ExtensionJars() {}

    /**
     * Whether the jar's module — explicit {@code module-info} or, for an automatic module,
     * {@code META-INF/services} — provides the extension SPI.
     */
    public static boolean providesExtension(Path jar) {
        if (!Files.isRegularFile(jar)) {
            return false;
        }
        try {
            return ModuleFinder.of(jar).findAll().stream()
                    .flatMap(ref -> ref.descriptor().provides().stream())
                    .map(ModuleDescriptor.Provides::service)
                    .anyMatch(SPI::equals);
        } catch (FindException | SecurityException e) {
            return false;
        }
    }
}
