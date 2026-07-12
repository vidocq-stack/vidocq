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
package io.vidocq.runtime.arquillian;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link MaterializedDeployment}: a ShrinkWrap archive exploded on
 * disk and exposed through a dedicated deployment class loader so the runtime
 * (MP Config providers, JWT key resolution, OpenAPI static files, TCK
 * {@code META-INF/services} entries) can see the archive content as if it were
 * a regular application classpath.
 */
class MaterializedDeploymentTest {

    @Test
    void exposesJavaArchiveResourcesThroughClassLoader() throws Exception {
        JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "test.jar")
                .add(new StringAsset("-----BEGIN PUBLIC KEY-----"), "/publicKey.pem")
                .add(new StringAsset("com.example.impl.MySpiImpl"),
                        "/META-INF/services/com.example.Spi");

        try (MaterializedDeployment deployment = MaterializedDeployment.of(archive)) {
            URL pem = deployment.classLoader().getResource("publicKey.pem");
            assertNotNull(pem, "root resource must be visible through the class loader");

            try (InputStream in = deployment.classLoader()
                    .getResourceAsStream("META-INF/services/com.example.Spi")) {
                assertNotNull(in, "service file must be visible through the class loader");
                assertEquals("com.example.impl.MySpiImpl", new String(in.readAllBytes()).trim());
            }
        }
    }

    @Test
    void normalizesWebArchiveLayoutToClasspathRoots() throws Exception {
        WebArchive archive = ShrinkWrap.create(WebArchive.class, "test.war")
                .addClass(MaterializedDeploymentTest.class)
                .addAsResource(new StringAsset("greeting=hello"), "microprofile-config.properties")
                .addAsWebInfResource(new StringAsset("<beans/>"), "beans.xml");

        try (MaterializedDeployment deployment = MaterializedDeployment.of(archive)) {
            assertNotNull(deployment.classLoader().getResource("microprofile-config.properties"),
                    "WEB-INF/classes content must surface at the classpath root");
            assertNotNull(deployment.classLoader().getResource(
                            MaterializedDeploymentTest.class.getName().replace('.', '/') + ".class"),
                    "classes under WEB-INF/classes must surface at the classpath root");
        }
    }

    @Test
    void discoversBuildCompatibleExtensionsVisibleToTheDeploymentClassLoader() throws Exception {
        // Dynamic Arquillian archives are not APT-processed, so the container must
        // hand every ServiceLoader-registered BCE to the CDI boot explicitly for the
        // full build-compatible lifecycle (@Discovery..@Synthesis) to run against the
        // archive classes — the same thing a build-time-processed application gets
        // from its codegen. BCEs may come from the runtime jars (parent loader) or
        // from the archive itself.
        JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "test.jar")
                .add(new StringAsset("com.example.tck.ArchiveShippedExtension"),
                        "/META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension");

        try (MaterializedDeployment deployment = MaterializedDeployment.of(archive)) {
            var extensions = BuildCompatibleExtensions.discover(deployment.classLoader());
            assertTrue(extensions.contains("com.example.tck.ArchiveShippedExtension"),
                    "BCEs registered by the archive must be discovered");
        }
    }

    @Test
    void listsBeanClassesFromArchiveRootAndBundledLibraries() throws Exception {
        // CDI treats WEB-INF/lib jars as bean archives; several TCKs package their
        // client interfaces as libraries (ShrinkWrap addAsLibrary). The deployment
        // must surface those classes as bean class names, next to the classes of
        // WEB-INF/classes, or the CDI boot never sees them.
        JavaArchive library = ShrinkWrap.create(JavaArchive.class, "clients.jar")
                .addClass(MaterializedDeployment.class);
        WebArchive archive = ShrinkWrap.create(WebArchive.class, "test.war")
                .addClass(MaterializedDeploymentTest.class)
                .addAsLibrary(library);

        try (MaterializedDeployment deployment = MaterializedDeployment.of(archive)) {
            var classNames = deployment.beanClassNames();
            assertTrue(classNames.contains(MaterializedDeploymentTest.class.getName()),
                    "classes under WEB-INF/classes must be listed");
            assertTrue(classNames.contains(MaterializedDeployment.class.getName()),
                    "classes inside WEB-INF/lib jars must be listed");
        }
    }

    @Test
    void collectsMicroProfileConfigFromArchiveRootAndBundledLibraries() throws Exception {
        // TCK deployments ship microprofile-config.properties either under
        // WEB-INF/classes or inside a WEB-INF/lib jar (ShrinkWrap addAsLibrary +
        // addAsManifestResource). Both must reach the MP Config provider.
        JavaArchive library = ShrinkWrap.create(JavaArchive.class, "clients.jar")
                .add(new StringAsset("from.lib=yes"), "/META-INF/microprofile-config.properties");
        WebArchive archive = ShrinkWrap.create(WebArchive.class, "test.war")
                .addAsResource(new StringAsset("from.war=yes"), "META-INF/microprofile-config.properties")
                .addAsLibrary(library);

        try (MaterializedDeployment deployment = MaterializedDeployment.of(archive)) {
            var config = deployment.microProfileConfig();
            assertEquals("yes", config.get("from.war"), "war-level config must be collected");
            assertEquals("yes", config.get("from.lib"), "library-level config must be collected");
        }
    }

    @Test
    void closeRemovesMaterializedFilesAndStopsServingResources() throws Exception {
        JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "test.jar")
                .add(new StringAsset("data"), "/data.txt");

        MaterializedDeployment deployment = MaterializedDeployment.of(archive);
        assertTrue(Files.exists(deployment.root()), "archive must be materialized on disk");
        assertNotNull(deployment.classLoader().getResource("data.txt"));

        deployment.close();

        assertFalse(Files.exists(deployment.root()), "close() must delete the materialized files");
        assertNull(deployment.classLoader().getResource("data.txt"),
                "a closed deployment must not serve resources anymore");
    }
}
