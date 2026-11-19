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
package io.vidocq.runtime.extensions.jakartaee.web.naming;

import io.vidocq.runtime.spi.VidocqConfiguration;
import io.vidocq.runtime.spi.VidocqExtension;
import io.vidocq.runtime.spi.report.StartupReportContext;
import io.vidocq.runtime.spi.report.StartupReportContributor;
import io.vidocq.runtime.spi.report.StartupReportSection;
import io.vidocq.vauban.naming.JavaNaming;

import javax.naming.Context;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The JNDI {@code java:} namespace (vidocq#297): bricks and applications that look a resource up with
 * {@code new InitialContext().lookup("java:comp/env/jdbc/orders")}, as on an application server, get the
 * bean {@code vidocq.properties} binds that name to.
 *
 * <p>Vauban Naming does the work; this extension makes its factory the JVM default in {@link #configure},
 * before the first {@code InitialContext} of the application, unless the application chose a factory, and
 * removes it in {@link #onStop}. The contexts hold no container, so they follow a {@code vidocq:dev} restart.
 *
 * <p>The names are declared with {@code vidocq.naming.<name without "java:"> = <target>}: a target starting with
 * {@code java:} links to that name, any other target is a bean written as text, such as
 * {@code javax.sql.DataSource @Named("orders")} (Vauban's {@code JndiTarget.bean(String, ClassLoader)}).
 */
public final class NamingExtension implements VidocqExtension, StartupReportContributor {

    private static final String JAVA_NAMES = "java: names";
    private static final String PREFIX = "vidocq.naming.";
    private static final Set<String> NAMESPACES = Set.of("comp/", "module/", "app/", "global/");

    private volatile boolean installed;
    private volatile String kept;
    private final AtomicReference<Map<String, String>> declared = new AtomicReference<>(Map.of());

    @Override public String name() { return "naming"; }
    @Override public String id() { return "naming"; }
    @Override public String title() { return "Naming (JNDI)"; }

    @Override
    public Set<String> configKeys() {
        return Set.of(PREFIX + "*");
    }

    /**
     * The names {@code config} declares: {@code "java:" + key without vidocq.naming.} to the trimmed target, sorted.
     *
     * @throws IllegalStateException for a key under {@code vidocq.naming.} outside the four namespaces, such as
     *                               {@code vidocq.naming.comp.env.toto}
     */
    static Map<String, String> declarations(VidocqConfiguration config) {
        Map<String, String> names = new TreeMap<>();
        if (config == null) {
            return names;
        }
        for (String key : config.propertyNames()) {
            if (!key.startsWith(PREFIX)) {
                continue;
            }
            String name = key.substring(PREFIX.length());
            if (NAMESPACES.stream().noneMatch(name::startsWith) || name.endsWith("/")) {
                throw new IllegalStateException("Unknown naming key " + key + ": a name starts with comp/, module/, "
                        + "app/ or global/, such as vidocq.naming.comp/env/jdbc/orders for java:comp/env/jdbc/orders");
            }
            config.property(key).ifPresent(target -> names.put("java:" + name, target.strip()));
        }
        return names;
    }

    @Override
    public void configure(VidocqConfiguration config) {
        declared.set(declarations(config));
        ConfiguredNames.declare(declared.get());
        installed = JavaNaming.installAsDefault(Thread.currentThread().getContextClassLoader());
        if (!installed) {
            String property = System.getProperty(Context.INITIAL_CONTEXT_FACTORY);
            kept = property != null ? property : "set in jndi.properties";
        }
    }

    @Override
    public void onStop() {
        if (installed) {
            JavaNaming.uninstall();
            installed = false;
        }
        kept = null;
        declared.set(Map.of());
        ConfiguredNames.declare(Map.of());
    }

    @Override
    public void contribute(StartupReportContext context, StartupReportSection section) {
        section.summary("JNDI java: namespace");
        if (installed) {
            section.row(JAVA_NAMES, "the declared names, from the CDI container");
        } else if (kept != null) {
            section.row(JAVA_NAMES, "the application's factory, " + kept);
        } else {
            section.row(JAVA_NAMES, "not initialized");
        }
        declared.get().forEach(section::row);
    }
}
