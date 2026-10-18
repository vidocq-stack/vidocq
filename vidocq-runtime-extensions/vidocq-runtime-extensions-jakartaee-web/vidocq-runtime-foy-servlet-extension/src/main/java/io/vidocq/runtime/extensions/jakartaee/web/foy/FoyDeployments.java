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

import io.vidocq.foy.chappe.FoyChappeBoot;
import io.vidocq.runtime.extensions.jakartaee.web.foy.live.ServletMount;
import io.vidocq.runtime.extensions.jakartaee.web.foy.live.ServletMounts;
import io.vidocq.runtime.spi.ApplicationLayer;
import io.vidocq.runtime.spi.config.VidocqConfig;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.servlet.ServletException;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Deploys the application with Foy and keeps the live deployments of one boot: the startup report
 * reads them, {@link FoyExtension#onStop()} closes them (an in-JVM hot reload then starts from a
 * fresh deployment on the new class loader). The extension and the declarative mounts of a boot
 * share one instance, found by {@link #forBoot} with the boot's container: two live boots (parallel
 * tests, a reload that starts the new boot before stopping the old) never see each other's.
 */
final class FoyDeployments {

    /** A deployment mounted under {@code name} ({@code vidocq.servlet}, or a declarative mount's name). */
    record Live(String name, String listener, FoyChappeBoot.Mounted mounted) {}

    private static final Map<Object, FoyDeployments> BY_BOOT = new IdentityHashMap<>();

    private final List<Live> live = new ArrayList<>();
    /** The boot's container, under which the mounts are published for the dev panel; null in tests. */
    private final Object boot;

    FoyDeployments() {
        this(null);
    }

    private FoyDeployments(Object boot) {
        this.boot = boot;
    }

    /** The deployments of the boot identified by {@code boot}, its container. */
    static FoyDeployments forBoot(Object boot) {
        synchronized (BY_BOOT) {
            return BY_BOOT.computeIfAbsent(boot, FoyDeployments::new);
        }
    }

    /** Drops the deployments of a stopped boot. */
    static void forget(Object boot) {
        synchronized (BY_BOOT) {
            BY_BOOT.remove(boot);
        }
        ServletMounts.forget(boot);
    }

    /**
     * @param contextPath {@code null}: the descriptors' {@code <default-context-path>}, else {@code /}
     * @param config      read for the {@linkplain ServletSecurity security settings}
     * @return empty when the application has no servlet, filter, listener nor initializer
     */
    static Optional<FoyChappeBoot.Mounted> deploy(BeanManager beanManager, ClassLoader loader,
                                                  String contextPath, int sessionTimeoutSeconds,
                                                  VidocqConfig config) {
        FoyChappeBoot.Builder builder = FoyChappeBoot.builder()
                .beanManager(beanManager)
                .classLoader(loader)
                // vidocq#228: the components of a fragment the ordering excludes stay out.
                .componentSource(type -> ComponentSources.classFile(loader, type))
                // The JSP engine and its compiled pages are looked up in the application layer (vidocq#232).
                .applicationLayer(ApplicationLayer.current().orElse(ModuleLayer.boot()))
                .sessionTimeoutSeconds(sessionTimeoutSeconds);
        if (contextPath != null) builder.contextPath(contextPath);
        ServletSecurity.from(config).applyTo(builder, ServletSecurity.identityStoreBean(beanManager));
        try {
            return builder.build();
        } catch (ServletException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    synchronized void record(String name, String listener, FoyChappeBoot.Mounted mounted) {
        live.add(new Live(name, listener, mounted));
        if (boot != null) {
            String contextPath = mounted.servletContext().getContextPath();
            ServletMounts.record(boot, new ServletMount(name, listener, contextPath.isEmpty() ? "/" : contextPath,
                    mounted::activeSessions));
        }
    }

    synchronized List<Live> all() {
        return List.copyOf(live);
    }

    /** Closes every deployment of this boot, the last mounted first. */
    synchronized void closeAll() {
        RuntimeException failure = null;
        for (Live l : live.reversed()) {
            try {
                l.mounted().close();
            } catch (RuntimeException e) {
                if (failure == null) failure = e; else failure.addSuppressed(e);
            }
        }
        live.clear();
        if (failure != null) throw failure;
    }
}
