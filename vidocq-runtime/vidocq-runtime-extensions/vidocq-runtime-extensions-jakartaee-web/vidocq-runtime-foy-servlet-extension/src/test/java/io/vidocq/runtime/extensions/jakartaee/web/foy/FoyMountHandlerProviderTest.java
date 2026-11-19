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
import io.vidocq.runtime.extensions.essentials.chappe.spi.MountConfig;
import io.vidocq.runtime.spi.DevHandover;
import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.VidocqConfiguration;
import io.vidocq.runtime.spi.config.VidocqConfig;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.inject.spi.BeanManager;
import io.vidocq.runtime.extensions.jakartaee.web.foy.fixture.LifecycleListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class FoyMountHandlerProviderTest {

    private static final Optional<DevHandover> NO_HANDOVER = Optional.empty();

    private final FoyDeployments deployments = new FoyDeployments();

    @AfterEach
    void forget() {
        deployments.closeAll(Optional.empty());
    }

    @Test
    void servesTheServletType() {
        assertEquals("servlet", new FoyMountHandlerProvider().type());
    }

    @Test
    void keepsTheMountPrefixForFoy() {
        assertFalse(new FoyMountHandlerProvider().stripPrefix());
    }

    @Test
    void deploysWithTheMountPrefixAsContextPath(@TempDir Path root) throws Exception {
        MountConfig cfg = mount(Map.of());

        assertNotNull(new FoyMountHandlerProvider().create(cfg, deployments, null,
                FoyExtensionTest.loaderWith(root, FoyExtensionTest.WEB_XML), Optional.empty()));

        FoyDeployments.Live live = deployments.all().getFirst();
        assertEquals("web", live.name());
        assertEquals("/web", live.mounted().servletContext().getContextPath());
    }

    @Test
    void mountSessionTimeoutOverridesTheGlobalOne(@TempDir Path root) throws Exception {
        MountConfig cfg = mount(Map.of("vidocq.servlet.session-timeout", "600",
                "vidocq.http.mount.web.session-timeout", "120"));

        new FoyMountHandlerProvider().create(cfg, deployments, null, FoyExtensionTest.loaderWith(root, FoyExtensionTest.WEB_XML),
                Optional.empty());

        assertEquals(2, deployments.all().getFirst().mounted().servletContext().getSessionTimeout());
    }

    @Test
    void aServletMountTakesTheResponseEncodingOfTheConfiguration(@TempDir Path root) throws Exception {
        new FoyMountHandlerProvider().create(mount(Map.of()), deployments, null,
                FoyExtensionTest.loaderWith(root, FoyExtensionTest.WEB_XML), Optional.empty());
        assertEquals("UTF-8", deployments.all().getFirst().mounted().servletContext().getResponseCharacterEncoding());
        deployments.closeAll(Optional.empty());

        new FoyMountHandlerProvider().create(mount(Map.of("vidocq.servlet.response-character-encoding", "ISO-8859-1")),
                deployments, null, FoyExtensionTest.loaderWith(root, FoyExtensionTest.WEB_XML), Optional.empty());

        assertEquals("ISO-8859-1", deployments.all().getFirst().mounted().servletContext()
                .getResponseCharacterEncoding());
    }

    @Test
    void aDeclarativeServletMountImportsBeforeItReturnsItsHandler(@TempDir Path a, @TempDir Path b) throws Exception {
        var handover = new MapHandover();
        Handler before = new FoyMountHandlerProvider().create(mount(Map.of()), deployments, null,
                FoyExtensionTest.loaderWith(a, FoyExtensionTest.SESSION_WEB_XML), Optional.of(handover));
        String cookie = FoyExtensionTest.cookieOf(FoyExtensionTest.get(before, "/web/set", null));
        handover.exporting = true;
        deployments.closeAll(Optional.of(handover));
        handover.exporting = false;

        Handler after = new FoyMountHandlerProvider().create(mount(Map.of()), deployments, null,
                FoyExtensionTest.loaderWith(b, FoyExtensionTest.SESSION_WEB_XML), Optional.of(handover));

        assertEquals("kept", FoyExtensionTest.text(FoyExtensionTest.get(after, "/web/get", cookie)));
    }

    @Test
    void aMountTakesTheHandoverOfItsBoot(@TempDir Path root) throws Exception {
        var handover = new MapHandover();
        handover.values.put("servlet:sessions:/web", new byte[] {1, 2, 3});
        MountConfig cfg = new MountConfig("web", "/web", "default", 0, FoyExtensionTest.config(Map.of()),
                contextHanding(handover));
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(FoyExtensionTest.loaderWith(root, FoyExtensionTest.SESSION_WEB_XML));
        try {
            assertNotNull(new FoyMountHandlerProvider().create(cfg));
        } finally {
            thread.setContextClassLoader(previous);
            FoyDeployments.forBoot(null).closeAll(Optional.empty());
            FoyDeployments.forget(null);
        }

        assertTrue(handover.values.isEmpty(), "the mount took its key");
    }

    /** The context of a boot of the reload loop, without a container, as the other fakes of the runtime. */
    private static ExtensionContext contextHanding(DevHandover handover) {
        return new ExtensionContext() {
            @Override
            public VaubanContainer container() {
                return null;
            }

            @Override
            public BeanManager beanManager() {
                return null;
            }

            @Override
            public VidocqConfiguration configuration() {
                return key -> Optional.empty();
            }

            @Override
            public VidocqConfig config() {
                return FoyExtensionTest.config(Map.of());
            }

            @Override
            public Optional<DevHandover> devHandover() {
                return Optional.of(handover);
            }
        };
    }

    @Test
    void declarativeMountWithoutComponentsFails(@TempDir Path root) throws Exception {
        MountConfig cfg = mount(Map.of());

        FoyMountHandlerProvider provider = new FoyMountHandlerProvider();
        ClassLoader loader = FoyExtensionTest.loaderWith(root, null);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> provider.create(cfg, deployments, null, loader, NO_HANDOVER));
        assertTrue(e.getMessage().contains("vidocq.http.mount.web.type=servlet"), e.getMessage());
    }

    @Test
    void stopClosesDeclarativeDeployments(@TempDir Path root) throws Exception {
        new FoyMountHandlerProvider().create(mount(Map.of()), deployments, null,
                FoyExtensionTest.loaderWith(root, FoyExtensionTest.WEB_XML), Optional.empty());
        int before = LifecycleListener.DESTROYED.get();

        new FoyExtension((l, p, h) -> {}, deployments).onStop();

        assertEquals(before + 1, LifecycleListener.DESTROYED.get());
    }

    @Test
    void theServletSecurityConfigurationReachesFoy(@TempDir Path root) throws Exception {
        MountConfig cfg = mount(Map.of("vidocq.servlet.confidential-port", "70000"));

        FoyMountHandlerProvider provider = new FoyMountHandlerProvider();
        ClassLoader loader = FoyExtensionTest.loaderWith(root, FoyExtensionTest.WEB_XML);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> provider.create(cfg, deployments, null, loader, NO_HANDOVER));

        assertTrue(e.getMessage().contains("vidocq.servlet.confidential-port"), e.getMessage());
    }

    @Test
    void aServletMountThatStripsItsPrefixIsRefused(@TempDir Path root) throws Exception {
        MountConfig cfg = mount(Map.of("vidocq.http.mount.web.strip-prefix", "true"));

        FoyMountHandlerProvider provider = new FoyMountHandlerProvider();
        ClassLoader loader = FoyExtensionTest.loaderWith(root, FoyExtensionTest.WEB_XML);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> provider.create(cfg, deployments, null, loader, NO_HANDOVER));

        assertTrue(e.getMessage().contains("vidocq.http.mount.web.strip-prefix"), e.getMessage());
        assertTrue(deployments.all().isEmpty());
    }

    @Test
    void aSecondServletMountIsRefused(@TempDir Path root) throws Exception {
        ClassLoader loader = FoyExtensionTest.loaderWith(root, FoyExtensionTest.WEB_XML);
        new FoyMountHandlerProvider().create(mount(Map.of()), deployments, null, loader, Optional.empty());
        MountConfig admin = new MountConfig("admin", "/admin", "default", 0, FoyExtensionTest.config(Map.of()), null);

        FoyMountHandlerProvider provider = new FoyMountHandlerProvider();
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> provider.create(admin, deployments, null, loader, NO_HANDOVER));

        assertTrue(e.getMessage().contains("vidocq.http.mount.admin"), e.getMessage());
        assertTrue(e.getMessage().contains("vidocq.http.mount.web"), e.getMessage());
        assertEquals(1, deployments.all().size());
    }

    private static MountConfig mount(Map<String, String> values) {
        return new MountConfig("web", "/web", "default", 0, FoyExtensionTest.config(values), null);
    }
}
