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
package io.vidocq.mansart.data.cdi;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MansartDataExtension}'s {@code @Enhancement} looks at every class of the archive: it loads only those that can
 * be a repository implementation it vetoes — one the Mansart index lists, or one named by another provider's convention
 * ({@code <repository>_}) — instead of loading and introspecting every class at startup.
 */
class VetoCandidateTest {

    @TempDir
    Path classes;

    @Test
    void onlyIndexedImplementationsAndForeignNamesAreCandidates() throws Exception {
        Path index = classes.resolve("META-INF/mansart-repositories.list");
        Files.createDirectories(index.getParent());
        Files.writeString(index, "# comment\nshop.Books=shop.BooksImpl\n");
        Path external = classes.resolve("META-INF/mansart-repositories-external.list");
        Files.writeString(external, "lib.Notes=app.gen.NotesImpl\n");
        try (var loader = new URLClassLoader(new URL[] {classes.toUri().toURL()}, null)) {
            assertTrue(MansartDataExtension.vetoCandidate("shop.BooksImpl", loader));
            assertTrue(MansartDataExtension.vetoCandidate("app.gen.NotesImpl", loader));
            assertTrue(MansartDataExtension.vetoCandidate("shop.Books_", loader));
            assertFalse(MansartDataExtension.vetoCandidate("shop.BookResource", loader));
            assertFalse(MansartDataExtension.vetoCandidate("shop.OtherImpl", loader));
        }
    }
}
