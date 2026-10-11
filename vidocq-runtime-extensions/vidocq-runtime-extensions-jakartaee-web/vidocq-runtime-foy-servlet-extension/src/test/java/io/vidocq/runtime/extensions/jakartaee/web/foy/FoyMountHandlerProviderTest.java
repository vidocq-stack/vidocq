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

import io.vidocq.runtime.extensions.essentials.chappe.spi.MountConfig;
import io.vidocq.runtime.extensions.jakartaee.web.foy.fixture.LifecycleListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FoyMountHandlerProviderTest {

    @AfterEach
    void forget() {
        FoyDeployments.closeAll();
    }

    @Test
    void servesTheServletType() {
        assertEquals("servlet", new FoyMountHandlerProvider().type());
    }

    @Test
    void deploysWithTheMountPrefixAsContextPath(@TempDir Path root) throws Exception {
        MountConfig cfg = mount(Map.of());

        assertNotNull(new FoyMountHandlerProvider().create(cfg, null,
                FoyExtensionTest.loaderWith(root, FoyExtensionTest.WEB_XML)));

        FoyDeployments.Live live = FoyDeployments.all().getFirst();
        assertEquals("web", live.name());
        assertEquals("/web", live.mounted().servletContext().getContextPath());
    }

    @Test
    void mountSessionTimeoutOverridesTheGlobalOne(@TempDir Path root) throws Exception {
        MountConfig cfg = mount(Map.of("vidocq.servlet.session-timeout", "600",
                "vidocq.http.mount.web.session-timeout", "120"));

        new FoyMountHandlerProvider().create(cfg, null, FoyExtensionTest.loaderWith(root, FoyExtensionTest.WEB_XML));

        assertEquals(2, FoyDeployments.all().getFirst().mounted().servletContext().getSessionTimeout());
    }

    @Test
    void declarativeMountWithoutComponentsFails(@TempDir Path root) throws Exception {
        MountConfig cfg = mount(Map.of());

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new FoyMountHandlerProvider().create(cfg, null, FoyExtensionTest.loaderWith(root, null)));
        assertTrue(e.getMessage().contains("vidocq.http.mount.web.type=servlet"), e.getMessage());
    }

    @Test
    void stopClosesDeclarativeDeployments(@TempDir Path root) throws Exception {
        new FoyMountHandlerProvider().create(mount(Map.of()), null,
                FoyExtensionTest.loaderWith(root, FoyExtensionTest.WEB_XML));
        int before = LifecycleListener.DESTROYED.get();

        new FoyExtension((l, p, h) -> {}).onStop();

        assertEquals(before + 1, LifecycleListener.DESTROYED.get());
    }

    private static MountConfig mount(Map<String, String> values) {
        return new MountConfig("web", "/web", "default", 0, FoyExtensionTest.config(values), null);
    }
}
