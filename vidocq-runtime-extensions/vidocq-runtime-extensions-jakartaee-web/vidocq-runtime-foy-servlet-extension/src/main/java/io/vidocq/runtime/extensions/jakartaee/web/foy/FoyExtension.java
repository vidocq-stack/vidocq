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
import io.vidocq.runtime.extensions.essentials.chappe.ChappeMountPoint;
import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.VidocqConfiguration;
import io.vidocq.runtime.spi.VidocqExtension;
import io.vidocq.runtime.spi.config.VidocqConfig;
import io.vidocq.runtime.spi.report.StartupReportContext;
import io.vidocq.runtime.spi.report.StartupReportContributor;
import io.vidocq.runtime.spi.report.StartupReportSection;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.ServletContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Serves the application's Jakarta Servlet components through Foy on the shared Chappe web
 * server. Runs after the REST extension (500) and before the declarative mounts (7000) and the
 * server start (10000). Foy activates the CDI request context itself and, through
 * {@code foy-cdi-vauban}, the session context: the dispatch is not wrapped here.
 */
public final class FoyExtension implements VidocqExtension, StartupReportContributor {

    static final String AUTO_MOUNT = "vidocq.servlet";
    private static final System.Logger LOG = System.getLogger(FoyExtension.class.getName());

    private final MountTarget target;
    private ServletSettings settings;
    private FoyDeployments deployments;
    /** The container of the boot this extension started in: the key of its deployments. */
    private Object boot;

    public FoyExtension() {
        // Declared here, not in MountTarget: the mount point names the class that mounts in its errors.
        // No prefix stripping: Foy dispatches on the full request path, its context path included.
        this((listener, prefix, handler) -> ChappeMountPoint.instance().mount(listener, prefix, handler, false),
                new FoyDeployments());
    }

    FoyExtension(MountTarget target, FoyDeployments deployments) {
        this.target = target;
        this.deployments = deployments;
    }

    @Override
    public String name() {
        return "servlet-foy";
    }

    @Override
    public int priority() {
        return 600;
    }

    @Override
    public void configure(VidocqConfiguration config) {
        this.settings = ServletSettings.from(config);
    }

    @Override
    public Set<String> configKeys() {
        return Set.of(ServletSettings.CONTEXT_PATH, ServletSettings.LISTENER, ServletSettings.SESSION_TIMEOUT,
                ServletSettings.RESPONSE_CHARACTER_ENCODING,
                ServletSecurity.USERS + "*", ServletSecurity.CONFIDENTIAL_PORT, ServletSecurity.ROTATE_SESSION_ID);
    }

    @Override
    public void onStart(ExtensionContext context) {
        boot = context.container();
        deployments = FoyDeployments.forBoot(boot);
        start(context.beanManager(), context.config(), Thread.currentThread().getContextClassLoader());
    }

    void start(BeanManager beanManager, VidocqConfig config, ClassLoader loader) {
        if (hasDeclarativeServletMount(config)) {
            LOG.log(System.Logger.Level.INFO,
                    "A declarative vidocq.http.mount.<n>.type=servlet is configured, skipping the auto-mount");
            return;
        }
        Optional<FoyChappeBoot.Mounted> deployed = FoyDeployments.deploy(beanManager, loader,
                settings.contextPath(), settings.sessionTimeoutSeconds(), settings.responseCharacterEncoding(), config);
        if (deployed.isEmpty()) {
            LOG.log(System.Logger.Level.INFO, "No web component discovered, Foy Servlet extension inactive");
            return;
        }
        FoyChappeBoot.Mounted mounted = deployed.get();
        // Recorded before mounting: a refused mount (duplicate prefix) still leaves it to onStop.
        deployments.register(AUTO_MOUNT, settings.listener(), mounted);
        target.mount(settings.listener(), mounted.mountPrefix(), mounted.handler());
        LOG.log(System.Logger.Level.INFO, "Foy Servlet extension mounted on listener={0} prefix={1}",
                settings.listener(), mounted.mountPrefix().isEmpty() ? "/" : mounted.mountPrefix());
    }

    @Override
    public void onStop() {
        try {
            deployments.closeAll();
        } finally {
            if (boot != null) FoyDeployments.forget(boot);
        }
    }

    /** The deployments of this extension's boot. */
    FoyDeployments deployments() {
        return deployments;
    }

    @Override
    public String id() {
        return "servlet";
    }

    @Override
    public String title() {
        return "Servlet (Foy)";
    }

    @Override
    public void contribute(StartupReportContext context, StartupReportSection section) {
        List<FoyDeployments.Live> live = deployments.all();
        if (live.isEmpty()) {
            section.summary("inactive: no web component");
            return;
        }
        int servlets = 0;
        int filters = 0;
        List<String> prefixes = new ArrayList<>();
        for (FoyDeployments.Live l : live) {
            ServletContext sc = l.mounted().servletContext();
            servlets += sc.getServletRegistrations().size();
            filters += sc.getFilterRegistrations().size();
            prefixes.add(l.mounted().mountPrefix().isEmpty() ? "/" : l.mounted().mountPrefix());
        }
        section.summary(servlets + " servlet(s), " + filters + " filter(s) at " + String.join(", ", prefixes));
        // One deployment per boot: the auto-mount and the declarative servlet mount exclude each other,
        // and FoyMountHandlerProvider refuses a second servlet mount.
        FoyDeployments.Live first = live.getFirst();
        ServletContext sc = first.mounted().servletContext();
        int minutes = sc.getSessionTimeout();
        section.row("Listener", first.listener());
        section.row("Context path", sc.getContextPath().isEmpty() ? "/" : sc.getContextPath());
        section.row("Session timeout", minutes <= 0 ? "never" : minutes + " min");
        section.list("Servlets", sc.getServletRegistrations().values().stream()
                .map(r -> r.getName() + " → " + String.join(", ", r.getMappings()))
                .sorted().toList());
        section.list("Filters", sc.getFilterRegistrations().values().stream()
                .map(FoyExtension::filterLine).sorted().toList());
        section.list("Listeners", first.mounted().listenerClassNames());
    }

    /** {@code name → /pattern, servlet-name}: what a filter applies to. */
    private static String filterLine(FilterRegistration registration) {
        List<String> targets = new ArrayList<>(registration.getUrlPatternMappings());
        targets.addAll(registration.getServletNameMappings());
        return registration.getName() + " → " + String.join(", ", targets);
    }

    private static boolean hasDeclarativeServletMount(VidocqConfig config) {
        for (String key : config.getPropertyNames()) {
            if (!key.startsWith("vidocq.http.mount.") || !key.endsWith(".type")) continue;
            if ("servlet".equals(config.getValue(key, String.class, ""))) return true;
        }
        return false;
    }
}
