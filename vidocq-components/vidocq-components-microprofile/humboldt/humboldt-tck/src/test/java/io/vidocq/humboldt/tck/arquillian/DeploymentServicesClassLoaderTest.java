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
package io.vidocq.humboldt.tck.arquillian;

import io.opentelemetry.sdk.autoconfigure.spi.ResourceProvider;
import io.opentelemetry.sdk.autoconfigure.spi.traces.ConfigurableSpanExporterProvider;
import io.vidocq.humboldt.tck.arquillian.HumboldtOtelBridgeDeployTest.TckInMemorySpanExporterProvider;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * The class loader the container hands to {@code OtelSpiAutoConfiguration.discover}: each deployment must see
 * the OpenTelemetry autoconfigure SPI providers its archive declares, and only those — as the archive scanning
 * the container did before.
 */
public class DeploymentServicesClassLoaderTest {

    private static final ClassLoader TEST_LOADER = DeploymentServicesClassLoaderTest.class.getClassLoader();

    @Test
    public void finds_the_providers_a_jar_archive_declares() {
        JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "declares.jar")
                .addClass(TckInMemorySpanExporterProvider.class)
                .addAsServiceProvider(ConfigurableSpanExporterProvider.class, TckInMemorySpanExporterProvider.class);

        assertEquals(providerClasses(ConfigurableSpanExporterProvider.class, archive),
                List.of(TckInMemorySpanExporterProvider.class));
    }

    @Test
    public void finds_the_providers_a_web_archive_declares() {
        WebArchive archive = ShrinkWrap.create(WebArchive.class, "declares.war")
                .addClass(TckInMemorySpanExporterProvider.class)
                .addAsServiceProvider(ConfigurableSpanExporterProvider.class, TckInMemorySpanExporterProvider.class);

        assertEquals(providerClasses(ConfigurableSpanExporterProvider.class, archive),
                List.of(TckInMemorySpanExporterProvider.class));
    }

    @Test
    public void hides_the_providers_the_test_class_path_declares() {
        // opentelemetry-sdk-extension-autoconfigure declares a ResourceProvider on the test class path.
        assertTrue(ServiceLoader.load(ResourceProvider.class, TEST_LOADER).iterator().hasNext(),
                "precondition: a ResourceProvider is declared on the test class path");
        JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "declares-nothing.jar");

        assertEquals(providerClasses(ResourceProvider.class, archive), List.of());
    }

    @Test
    public void leaves_other_resources_to_the_parent() throws IOException {
        // opentelemetry-sdk-testing declares a ContextStorageProvider: not an autoconfigure SPI.
        String contextStorage = "META-INF/services/io.opentelemetry.context.ContextStorageProvider";
        ClassLoader loader = new DeploymentServicesClassLoader(
                ShrinkWrap.create(JavaArchive.class, "declares-nothing.jar"), TEST_LOADER);

        assertEquals(Collections.list(loader.getResources(contextStorage)),
                Collections.list(TEST_LOADER.getResources(contextStorage)));
        assertFalse(Collections.list(loader.getResources(contextStorage)).isEmpty(),
                "precondition: the test class path declares a ContextStorageProvider");
    }

    private static List<Class<?>> providerClasses(Class<?> service, Archive<?> archive) {
        ClassLoader loader = new DeploymentServicesClassLoader(archive, TEST_LOADER);
        return ServiceLoader.load(service, loader).stream()
                .<Class<?>>map(ServiceLoader.Provider::type)
                .toList();
    }
}
