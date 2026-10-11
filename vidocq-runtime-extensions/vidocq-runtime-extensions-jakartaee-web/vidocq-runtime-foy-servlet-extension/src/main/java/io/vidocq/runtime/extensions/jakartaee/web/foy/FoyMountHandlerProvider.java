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
import io.vidocq.foy.chappe.FoyChappeBoot;
import io.vidocq.runtime.extensions.essentials.chappe.spi.MountConfig;
import io.vidocq.runtime.extensions.essentials.chappe.spi.MountHandlerProvider;
import jakarta.enterprise.inject.spi.BeanManager;

import java.util.List;

/**
 * {@code vidocq.http.mount.<n>.type=servlet}: mounts the application's servlets on a declared
 * prefix and listener. The prefix is the context path; {@code vidocq.http.mount.<n>.session-timeout}
 * overrides {@code vidocq.servlet.session-timeout} for this mount. One servlet mount per application,
 * which strips its prefix: {@code strip-prefix=false} and a second {@code type=servlet} mount fail.
 */
public final class FoyMountHandlerProvider implements MountHandlerProvider {

    @Override
    public String type() {
        return "servlet";
    }

    @Override
    public Handler create(MountConfig cfg) {
        return create(cfg, FoyDeployments.forBoot(cfg.extensionContext().container()),
                cfg.extensionContext().beanManager(), Thread.currentThread().getContextClassLoader());
    }

    Handler create(MountConfig cfg, FoyDeployments deployments, BeanManager beanManager, ClassLoader loader) {
        String key = "vidocq.http.mount." + cfg.name();
        if ("false".equalsIgnoreCase(cfg.property("strip-prefix").map(String::trim).orElse("true"))) {
            // Foy would see the full path, its context path included: every servlet path would be off.
            throw new IllegalStateException(key + ".strip-prefix=false: a servlet mount strips its prefix, "
                    + "which is the context path; remove the key");
        }
        List<FoyDeployments.Live> live = deployments.all();
        if (!live.isEmpty()) {
            String other = live.getFirst().name();
            throw new IllegalStateException(key + ".type=servlet: the application is already mounted by "
                    + (FoyExtension.AUTO_MOUNT.equals(other) ? other : "vidocq.http.mount." + other)
                    + "; deploying it twice would start it twice (two ServletContexts). Keep one servlet mount");
        }
        int global = ServletSettings.seconds(cfg.vidocqConfig().getValue(ServletSettings.SESSION_TIMEOUT).orElse(null));
        int timeout = ServletSettings.seconds(key + ".session-timeout",
                cfg.property("session-timeout").orElse(null), global);
        String contextPath = cfg.prefix().isEmpty() || "/".equals(cfg.prefix()) ? "/" : cfg.prefix();
        FoyChappeBoot.Mounted mounted = FoyDeployments.deploy(beanManager, loader, contextPath, timeout, cfg.vidocqConfig())
                .orElseThrow(() -> new IllegalStateException(key
                        + ".type=servlet, but the application has no servlet, filter, listener, web.xml "
                        + "nor ServletContainerInitializer"));
        deployments.record(cfg.name(), cfg.listener(), mounted);
        return mounted.handler();
    }
}
