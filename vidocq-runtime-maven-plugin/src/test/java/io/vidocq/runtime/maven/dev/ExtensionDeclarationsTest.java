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
package io.vidocq.runtime.maven.dev;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The start-time declarations this repository ships, read from the source tree: each parses without a warning and
 * declares its technology. How each classifies is in {@code HotSwapClassifierTest}.
 */
class ExtensionDeclarationsTest {

    static final Path CORE = resources("vidocq-runtime-core");
    static final Path FOY = resources("vidocq-runtime-extensions", "vidocq-runtime-extensions-jakartaee-web",
            "vidocq-runtime-foy-servlet-extension");
    static final Path CASSINI = resources("vidocq-runtime-extensions", "vidocq-runtime-extensions-jakartaee-core",
            "vidocq-runtime-cassini-rest-extension");
    static final Path VAADIN = resources("vidocq-runtime-extensions", "vidocq-runtime-extensions-jakartaee-web",
            "vidocq-runtime-vaadin-extension");

    private final List<String> warnings = new ArrayList<>();

    @Test
    void coreDeclaresCdi() {
        var read = read(CORE);

        assertTrue(read.longLivedAnnotations().containsAll(List.of("jakarta.enterprise.context.ApplicationScoped",
                "jakarta.enterprise.context.SessionScoped", "jakarta.inject.Singleton")), read::toString);
        assertTrue(read.longLivedTypes().contains("io.vidocq.runtime.spi.VidocqExtension"), read::toString);
        assertTrue(read.startAnnotations().containsAll(List.of("jakarta.annotation.PostConstruct",
                "jakarta.enterprise.inject.Produces")), read::toString);
        assertTrue(read.observerAnnotations().containsAll(List.of("jakarta.enterprise.event.Observes",
                "jakarta.enterprise.event.ObservesAsync")), read::toString);
        assertTrue(read.startEvents().containsAll(List.of("jakarta.enterprise.event.Startup",
                "jakarta.enterprise.context.Initialized")), read::toString);
    }

    @Test
    void foyDeclaresServlet() {
        var read = read(FOY);

        assertTrue(read.longLivedTypes().containsAll(List.of("jakarta.servlet.Servlet", "jakarta.servlet.Filter",
                "jakarta.servlet.ServletContextListener", "jakarta.servlet.http.HttpSessionListener",
                "jakarta.servlet.ServletRequestListener", "jakarta.servlet.ServletContainerInitializer")),
                read::toString);
        assertTrue(read.startCallbacks().containsAll(List.of(
                callback("jakarta.servlet.Servlet#init(Ljakarta/servlet/ServletConfig;)V"),
                callback("jakarta.servlet.GenericServlet#init()V"),
                callback("jakarta.servlet.Filter#init(Ljakarta/servlet/FilterConfig;)V"),
                callback("jakarta.servlet.ServletContextListener#contextInitialized"
                        + "(Ljakarta/servlet/ServletContextEvent;)V"),
                callback("jakarta.servlet.ServletContainerInitializer#onStartup"
                        + "(Ljava/util/Set;Ljakarta/servlet/ServletContext;)V"))), read::toString);
    }

    @Test
    void cassiniDeclaresJaxRs() {
        var read = read(CASSINI);

        assertTrue(read.longLivedAnnotations().contains("jakarta.ws.rs.ext.Provider"), read::toString);
        assertTrue(read.longLivedTypes().contains("jakarta.ws.rs.core.Application"), read::toString);
        assertTrue(read.startCallbacks().containsAll(List.of(
                callback("jakarta.ws.rs.core.Application#getClasses()Ljava/util/Set;"),
                callback("jakarta.ws.rs.core.Application#getSingletons()Ljava/util/Set;"),
                callback("jakarta.ws.rs.core.Application#getProperties()Ljava/util/Map;"),
                callback("jakarta.ws.rs.core.Feature#configure(Ljakarta/ws/rs/core/FeatureContext;)Z"),
                callback("jakarta.ws.rs.container.DynamicFeature#configure"
                        + "(Ljakarta/ws/rs/container/ResourceInfo;Ljakarta/ws/rs/core/FeatureContext;)V"))),
                read::toString);
    }

    @Test
    void vaadinDeclaresTheTokenItsBuildDeletesOnExit() {
        assertEquals(Set.of("META-INF/VAADIN/config/flow-build-info.json"), read(VAADIN).buildFiles());
    }

    @Test
    void vaadinDeclaresItsScopes() {
        var read = read(VAADIN);

        assertTrue(read.longLivedAnnotations().containsAll(List.of("com.vaadin.cdi.annotation.UIScoped",
                "com.vaadin.cdi.annotation.NormalUIScoped", "com.vaadin.cdi.annotation.VaadinSessionScoped",
                "com.vaadin.cdi.annotation.RouteScoped", "com.vaadin.cdi.annotation.NormalRouteScoped",
                "com.vaadin.cdi.annotation.VaadinServiceScoped", "com.vaadin.flow.router.PreserveOnRefresh")),
                read::toString);
        assertTrue(read.startCallbacks().contains(callback("com.vaadin.flow.server.VaadinServiceInitListener"
                + "#serviceInit(Lcom/vaadin/flow/server/ServiceInitEvent;)V")), read::toString);
    }

    @Test
    void noExtensionNamesAnotherOnesTechnology() {
        assertFalse(read(CORE).toString().contains("jakarta.servlet"));
        assertFalse(read(CORE).toString().contains("com.vaadin"));
        assertFalse(read(FOY).toString().contains("com.vaadin"));
        assertFalse(read(CASSINI).toString().contains("jakarta.servlet"));
    }

    private StartTimeDeclarations read(Path resources) {
        var read = StartTimeDeclarations.read(List.of(resources), warnings::add);
        assertEquals(List.of(resources.toString()), read.sources(), "no declaration file under " + resources);
        assertEquals(List.of(), warnings);
        return read;
    }

    private static StartTimeDeclarations.Callback callback(String entry) {
        int hash = entry.indexOf('#');
        int paren = entry.indexOf('(');
        return new StartTimeDeclarations.Callback(entry.substring(0, hash), entry.substring(hash + 1, paren),
                entry.substring(paren));
    }

    private static Path resources(String... module) {
        return Path.of("..", module).resolve(Path.of("src", "main", "resources"));
    }
}
