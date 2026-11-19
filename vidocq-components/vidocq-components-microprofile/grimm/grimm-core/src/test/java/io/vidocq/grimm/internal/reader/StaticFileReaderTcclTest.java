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
package io.vidocq.grimm.internal.reader;

import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The static document lives on the DEPLOYMENT class path (thread context class
 * loader), which is not necessarily the loader of the application classes: in
 * an embedded Arquillian deployment the classes resolve parent-first from the
 * test class path while META-INF/openapi.yaml only exists on the deployment
 * loader. The reader must always consult the TCCL, even when the deployment
 * types have their own class loaders (spec §4.4 static file step).
 */
class StaticFileReaderTcclTest {

    private static final String STATIC_DOC = """
            openapi: 3.1.0
            info:
              title: from-deployment-loader
              version: "1.0"
            paths: {}
            """;

    @Test
    void alwaysConsultsTheContextClassLoader() throws IOException {
        Path root = Files.createTempDirectory("grimm-static-");
        Files.createDirectories(root.resolve("META-INF"));
        Files.writeString(root.resolve("META-INF/openapi.yaml"), STATIC_DOC);

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        // Platform parent: the unit-test classpath carries its own
        // META-INF/openapi.yaml fixture which would win parent-first.
        try (URLClassLoader deploymentLoader = new URLClassLoader(
                new java.net.URL[]{root.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            Thread.currentThread().setContextClassLoader(deploymentLoader);

            // The known type is loaded by the app class loader, which has no
            // META-INF/openapi.yaml — the TCCL must still be consulted.
            Optional<OpenAPI> model = new StaticFileReader()
                    .readOpenAPI(List.of(StaticFileReaderTcclTest.class));

            assertTrue(model.isPresent(), "the TCCL's static document must be found");
            assertNotNull(model.get().getInfo());
            org.junit.jupiter.api.Assertions.assertEquals("from-deployment-loader",
                    model.get().getInfo().getTitle());
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }
}
