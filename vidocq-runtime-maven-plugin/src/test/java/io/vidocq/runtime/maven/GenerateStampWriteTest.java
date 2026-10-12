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

import io.vidocq.runtime.core.GenerateStamp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code vidocq:generate} stamps the application's classes, so that the boot of an application an IDE built alone
 * can tell the difference and index the unprocessed bean archives itself (Vidocq/vidocq#241).
 */
class GenerateStampWriteTest {

    @Test
    void theStampRecordsTheIndexGenerateLeaves(@TempDir Path classes) throws Exception {
        Path index = classes.resolve(GenerateStamp.INDEX);
        Files.createDirectories(index.getParent());
        Files.writeString(index, "acme.app.Greeter\nacme.lib.Clock\n");

        VidocqGenerateMojo.writeStamp(classes);

        assertEquals(GenerateStamp.content(Files.readAllBytes(index)), Files.readString(classes.resolve(GenerateStamp.PATH)));
    }

    @Test
    void withoutAnIndexTheStampSaysSo(@TempDir Path classes) throws Exception {
        VidocqGenerateMojo.writeStamp(classes);

        assertEquals(GenerateStamp.content(null), Files.readString(classes.resolve(GenerateStamp.PATH)));
    }
}
