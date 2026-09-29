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
package io.vidocq.runtime.devservices.host;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link ApplicationFiles#allOf} answers every key of the files; {@link ApplicationFiles#of} still the dev ones only. */
class ApplicationFilesTest {

    @Test
    void allOfAnswersEveryKeyOfTheFilesAndOfStillOnlyTheDevOnes(@TempDir Path classes) throws Exception {
        Files.writeString(classes.resolve("vidocq.properties"),
                "vidocq.dev.postgres.port=55432\nvidocq.pool.url=jdbc:h2:mem:x\n");
        Files.writeString(classes.resolve("application.properties"), "vidocq.pool.audit.url=jdbc:mysql://h/db\n");

        Function<String, Optional<String>> all = ApplicationFiles.allOf(classes);
        assertEquals(Optional.of("jdbc:h2:mem:x"), all.apply("vidocq.pool.url"));
        assertEquals(Optional.of("jdbc:mysql://h/db"), all.apply("vidocq.pool.audit.url"));
        assertEquals(Optional.of("55432"), all.apply("vidocq.dev.postgres.port"));
        assertEquals(Optional.empty(), all.apply("absent.key"));

        Function<String, Optional<String>> dev = ApplicationFiles.of(classes);
        assertEquals(Optional.empty(), dev.apply("vidocq.pool.url"), "of(...) keeps its vidocq.dev. filter");
        assertEquals(Optional.of("55432"), dev.apply("vidocq.dev.postgres.port"));
    }

    @Test
    void aClassesDirectoryWithoutFilesAnswersNothing(@TempDir Path classes) {
        assertEquals(Optional.empty(), ApplicationFiles.allOf(classes).apply("vidocq.pool.url"));
    }
}
