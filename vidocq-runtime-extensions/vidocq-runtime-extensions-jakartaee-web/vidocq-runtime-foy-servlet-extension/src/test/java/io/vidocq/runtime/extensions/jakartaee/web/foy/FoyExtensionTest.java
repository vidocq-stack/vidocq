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
package io.vidocq.runtime.extensions.jakartaee.web.foy;

import io.vidocq.chappe.api.Handler;
import io.vidocq.runtime.extensions.jakartaee.web.foy.fixture.LifecycleListener;
import io.vidocq.runtime.spi.config.ConfigSource;
import io.vidocq.runtime.spi.config.VidocqConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class FoyExtensionTest {

    record Mount(String listener, String prefix, Handler handler) {}

    /** One listener and one servlet, named in a {@code WEB-INF/web.xml}. */
    static final String WEB_XML = """
            <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.1">
              <listener>
                <listener-class>io.vidocq.runtime.extensions.jakartaee.web.foy.fixture.LifecycleListener</listener-class>
              </listener>
              <servlet>
                <servlet-name>hello</servlet-name>
                <servlet-class>io.vidocq.runtime.extensions.jakartaee.web.foy.fixture.HelloServlet</servlet-class>
              </servlet>
              <servlet-mapping>
                <servlet-name>hello</servlet-name>
                <url-pattern>/hello</url-pattern>
              </servlet-mapping>
            </web-app>
            """;

    private final List<Mount> mounts = new ArrayList<>();

    @AfterEach
    void forget() {
        FoyDeployments.closeAll();
    }

    @Test
    void noWebComponentMountsNothing(@TempDir Path root) throws Exception {
        FoyExtension extension = configured(Map.of());

        extension.start(null, config(Map.of()), loaderWith(root, null));

        assertTrue(mounts.isEmpty());
        assertTrue(FoyDeployments.all().isEmpty());
    }

    @Test
    void mountsTheDeploymentOnTheConfiguredListenerAndPrefix(@TempDir Path root) throws Exception {
        FoyExtension extension = configured(Map.of(
                "vidocq.servlet.context-path", "/app", "vidocq.servlet.listener", "admin"));

        extension.start(null, config(Map.of()), loaderWith(root, WEB_XML));

        assertEquals(1, mounts.size());
        assertEquals("admin", mounts.getFirst().listener());
        assertEquals("/app", mounts.getFirst().prefix());
        assertEquals(1, FoyDeployments.all().size());
    }

    @Test
    void rootContextPathMountsOnTheEmptyPrefix(@TempDir Path root) throws Exception {
        FoyExtension extension = configured(Map.of());

        extension.start(null, config(Map.of()), loaderWith(root, WEB_XML));

        assertEquals("", mounts.getFirst().prefix());
    }

    @Test
    void declarativeServletMountSkipsTheAutoMount(@TempDir Path root) throws Exception {
        FoyExtension extension = configured(Map.of());

        extension.start(null, config(Map.of("vidocq.http.mount.web.type", "servlet")), loaderWith(root, WEB_XML));

        assertTrue(mounts.isEmpty());
        assertTrue(FoyDeployments.all().isEmpty());
    }

    @Test
    void stopClosesTheDeployment(@TempDir Path root) throws Exception {
        FoyExtension extension = configured(Map.of());
        extension.start(null, config(Map.of()), loaderWith(root, WEB_XML));
        int before = LifecycleListener.DESTROYED.get();

        extension.onStop();

        assertEquals(before + 1, LifecycleListener.DESTROYED.get());
        assertTrue(FoyDeployments.all().isEmpty());
    }

    @Test
    void theServletSecurityConfigurationReachesFoy(@TempDir Path root) throws Exception {
        FoyExtension extension = configured(Map.of());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> extension.start(null,
                config(Map.of("vidocq.servlet.confidential-port", "70000")), loaderWith(root, WEB_XML)));

        assertTrue(e.getMessage().contains("vidocq.servlet.confidential-port"), e.getMessage());
    }

    private FoyExtension configured(Map<String, String> values) {
        FoyExtension extension = new FoyExtension((l, p, h) -> mounts.add(new Mount(l, p, h)));
        extension.configure(new MapConfiguration(values));
        return extension;
    }

    /** A child of the test loader that adds {@code root}, holding {@code WEB-INF/web.xml} when given. */
    static ClassLoader loaderWith(Path root, String webXml) throws Exception {
        if (webXml != null) {
            Files.createDirectories(root.resolve("WEB-INF"));
            Files.writeString(root.resolve("WEB-INF/web.xml"), webXml);
        }
        return new URLClassLoader(new URL[]{root.toUri().toURL()}, FoyExtensionTest.class.getClassLoader());
    }

    /** A {@link VidocqConfig} over a map of strings. */
    static VidocqConfig config(Map<String, String> values) {
        return new VidocqConfig() {
            @Override
            public Optional<String> getValue(String key) {
                return Optional.ofNullable(values.get(key));
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> Optional<T> getValue(String key, Class<T> type) {
                return (Optional<T>) getValue(key);
            }

            @Override
            public <T> List<T> getValues(String key, Class<T> elementType) {
                return List.of();
            }

            @Override
            public Iterable<String> getPropertyNames() {
                return values.keySet();
            }

            @Override
            public Iterable<ConfigSource> getConfigSources() {
                return List.of();
            }
        };
    }
}
