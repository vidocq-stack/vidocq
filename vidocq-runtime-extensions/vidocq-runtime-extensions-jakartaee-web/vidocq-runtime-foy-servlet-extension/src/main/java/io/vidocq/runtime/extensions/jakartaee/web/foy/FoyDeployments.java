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
import io.vidocq.foy.spi.security.RequestAuthentication;
import io.vidocq.runtime.extensions.jakartaee.web.foy.live.ServletMount;
import io.vidocq.runtime.extensions.jakartaee.web.foy.live.ServletMounts;
import io.vidocq.runtime.spi.ApplicationLayer;
import io.vidocq.runtime.spi.DevHandover;
import io.vidocq.runtime.spi.config.VidocqConfig;
import io.vidocq.runtime.extensions.jakartaee.web.foy.spi.ServletAuthentication;
import io.vidocq.runtime.extensions.jakartaee.web.foy.spi.ServletDeploymentListener;
import io.vidocq.runtime.extensions.jakartaee.web.foy.spi.ServletInitParameters;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.servlet.ServletException;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private static final System.Logger LOG = System.getLogger(FoyDeployments.class.getName());

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
     * Deploys the application with Foy.
     *
     * @param contextPath {@code null}: the descriptors' {@code <default-context-path>}, else {@code /}
     * @param responseCharacterEncoding the context's default response encoding when no descriptor
     *                    declares one
     * @param config      read for the {@linkplain ServletSecurity security settings}
     * @return empty when the application has no servlet, filter, listener nor initializer
     */
    static Optional<FoyChappeBoot.Mounted> deploy(BeanManager beanManager, ClassLoader loader,
                                                  String contextPath, int sessionTimeoutSeconds,
                                                  String responseCharacterEncoding, VidocqConfig config) {
        return deploy(beanManager, loader, contextPath, sessionTimeoutSeconds, responseCharacterEncoding, config,
                java.util.ServiceLoader.load(ServletInitParameters.class));
    }

    /** As above, with the {@link ServletInitParameters} given instead of found among the runtime extensions. */
    static Optional<FoyChappeBoot.Mounted> deploy(BeanManager beanManager, ClassLoader loader,
                                                  String contextPath, int sessionTimeoutSeconds,
                                                  String responseCharacterEncoding, VidocqConfig config,
                                                  Iterable<ServletInitParameters> initParameters) {
        FoyChappeBoot.Builder builder = FoyChappeBoot.builder()
                .beanManager(beanManager)
                .classLoader(loader)
                // vidocq#228: the components of a fragment the ordering excludes stay out.
                .componentSource(type -> ComponentSources.classFile(loader, type))
                // The JSP engine and its compiled pages are looked up in the application layer (vidocq#232).
                .applicationLayer(ApplicationLayer.current().orElse(ModuleLayer.boot()))
                .sessionTimeoutSeconds(sessionTimeoutSeconds)
                // web.xml and the fragments win, and an initializer or a listener can still set another one.
                .defaultResponseCharacterEncoding(responseCharacterEncoding);
        if (contextPath != null) builder.contextPath(contextPath);
        ServletSecurity.from(config).applyTo(builder, ServletSecurity.identityStoreBean(beanManager));
        requestAuthentication(java.util.ServiceLoader.load(ServletAuthentication.class), beanManager)
                .ifPresent(builder::requestAuthentication);
        accessControl(java.util.ServiceLoader.load(ServletAuthentication.class), beanManager)
                .ifPresent(builder::accessControl);
        initParameters(initParameters, config).forEach(builder::initParameter);
        try {
            Optional<FoyChappeBoot.Mounted> mounted = builder.build();
            mounted.ifPresent(m -> deployed(m.servletContext(), beanManager));
            return mounted;
        } catch (ServletException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /** The hook of the extension that takes over the authentication; empty when none does, two fail. */
    static Optional<RequestAuthentication> requestAuthentication(Iterable<ServletAuthentication> providers,
                                                                 BeanManager beanManager) {
        RequestAuthentication hook = null;
        for (ServletAuthentication provider : providers) {
            Optional<RequestAuthentication> given = Objects.requireNonNull(provider.requestAuthentication(beanManager),
                    () -> provider.getClass().getName() + " returned no answer");
            if (given.isEmpty()) continue;
            if (hook != null) {
                throw new IllegalStateException("Two extensions take over the authentication of the servlet "
                        + "deployments: keep one");
            }
            hook = given.get();
        }
        return Optional.ofNullable(hook);
    }

    /** The access decision of the extension that takes it over; empty when none does, two fail. */
    static Optional<io.vidocq.foy.spi.security.AccessControl> accessControl(Iterable<ServletAuthentication> providers,
                                                                            BeanManager beanManager) {
        io.vidocq.foy.spi.security.AccessControl control = null;
        for (ServletAuthentication provider : providers) {
            Optional<io.vidocq.foy.spi.security.AccessControl> given = Objects.requireNonNull(
                    provider.accessControl(beanManager), () -> provider.getClass().getName() + " returned no answer");
            if (given.isEmpty()) continue;
            if (control != null) {
                throw new IllegalStateException("Two extensions decide the access to the resources of the servlet "
                        + "deployments: keep one");
            }
            control = given.get();
        }
        return Optional.ofNullable(control);
    }

    /** The init parameters of every provider; a name two providers give fails. */
    static Map<String, String> initParameters(Iterable<ServletInitParameters> providers, VidocqConfig config) {
        Map<String, String> merged = new LinkedHashMap<>();
        for (ServletInitParameters provider : providers) {
            Map<String, String> given = Objects.requireNonNull(provider.initParameters(config),
                    () -> provider.getClass().getName() + " returned no init parameters");
            given.forEach((name, value) -> {
                Objects.requireNonNull(name, "init parameter name");
                Objects.requireNonNull(value, () -> "init parameter " + name);
                if (merged.putIfAbsent(name, value) != null) {
                    throw new IllegalStateException("Two extensions give the servlet init parameter " + name);
                }
            });
        }
        return merged;
    }

    /** Tells the {@link ServletDeploymentListener}s of the runtime extensions that an application is deployed. */
    static void deployed(jakarta.servlet.ServletContext context, BeanManager beanManager) {
        for (ServletDeploymentListener listener : java.util.ServiceLoader.load(ServletDeploymentListener.class)) {
            listener.deployed(context, beanManager);
        }
    }

    synchronized void register(String name, String listener, FoyChappeBoot.Mounted mounted) {
        live.add(new Live(name, listener, mounted));
        if (boot != null) {
            String contextPath = mounted.servletContext().getContextPath();
            ServletMounts.register(boot, new ServletMount(name, listener, contextPath.isEmpty() ? "/" : contextPath,
                    mounted::activeSessions));
        }
    }

    synchronized List<Live> all() {
        return List.copyOf(live);
    }

    /**
     * Closes every deployment of this boot, the last mounted first.
     * <p>
     * During the stop of a {@code vidocq:dev} swap that keeps the HTTP sessions ({@code handover} exporting), each
     * deployment exports its sessions into the handover, under its {@linkplain #handoverKey key}, and logs nothing: the
     * next deployment's import gives the exact count. Otherwise closing invalidates the sessions of each, and the log
     * says how many when there were some: an open page then starts a new session (a Vaadin UI reloads).
     *
     * @return the number of HTTP sessions the close invalidated, 0 when it exported them
     */
    synchronized int closeAll(Optional<DevHandover> handover) {
        DevHandover exportTo = handover.filter(DevHandover::exporting).orElse(null);
        RuntimeException failure = null;
        int sessions = 0;
        for (Live l : live.reversed()) {
            try {
                if (exportTo != null) {
                    String key = handoverKey(l.mounted().servletContext().getContextPath());
                    exportTo.put(key, l.mounted().closeExportingSessions());
                } else {
                    sessions += l.mounted().activeSessions();
                    l.mounted().close();
                }
            } catch (RuntimeException e) {
                if (failure == null) failure = e; else failure.addSuppressed(e);
            }
        }
        live.clear();
        if (sessions > 0) {
            LOG.log(System.Logger.Level.INFO, "Undeploying the servlet application invalidated "
                    + (sessions == 1 ? "1 HTTP session: its client starts a new one on its next request"
                    : sessions + " HTTP sessions: their clients start new ones on their next request"));
        }
        if (failure != null) throw failure;
        return sessions;
    }

    /** The key of a deployment's HTTP sessions in a dev handover: {@code servlet:sessions:} and its context path. */
    static String handoverKey(String contextPath) {
        return "servlet:sessions:" + (contextPath.isEmpty() ? "/" : contextPath);
    }

    /**
     * Before the mount of {@code mounted}: the HTTP sessions the previous deployment exported under the same context
     * path, during the {@code vidocq:dev} swap that started this boot. The import never fails the start: bytes Foy
     * refuses are logged, and the deployment starts without them.
     */
    static void importSessions(FoyChappeBoot.Mounted mounted, Optional<DevHandover> handover) {
        Optional<byte[]> exported = handover
                .flatMap(h -> h.take(handoverKey(mounted.servletContext().getContextPath())));
        if (exported.isEmpty()) return;
        try {
            int kept = mounted.importSessions(exported.get());
            if (kept > 0) {
                LOG.log(System.Logger.Level.INFO, "Hot reload - kept "
                        + (kept == 1 ? "1 HTTP session" : kept + " HTTP sessions") + " across the reload");
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Hot reload - the HTTP sessions of the previous deployment could not "
                    + "be imported, starting without them: " + e);
        }
    }
}
