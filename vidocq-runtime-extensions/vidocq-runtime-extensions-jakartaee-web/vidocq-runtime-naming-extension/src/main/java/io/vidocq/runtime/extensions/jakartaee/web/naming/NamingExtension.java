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

/**
 * The JNDI {@code java:} namespace (vidocq#297): bricks and applications that look a resource up with
 * {@code new InitialContext().lookup("java:comp/env/jdbc/orders")}, as on an application server, get the
 * {@code @Named("orders")} DataSource bean of the running container.
 *
 * <p>Vauban Naming does the work; this extension makes its factory the JVM default in {@link #configure},
 * before the first {@code InitialContext} of the application, unless the application chose a factory, and
 * removes it in {@link #onStop}. The contexts hold no container, so they follow a {@code vidocq:dev} restart.
 */
public final class NamingExtension implements VidocqExtension, StartupReportContributor {

    private static final String JAVA_NAMES = "java: names";

    private volatile boolean installed;
    private volatile String kept;

    @Override public String name() { return "naming"; }
    @Override public String id() { return "naming"; }
    @Override public String title() { return "Naming (JNDI)"; }

    @Override
    public void configure(VidocqConfiguration config) {
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
    }

    @Override
    public void contribute(StartupReportContext context, StartupReportSection section) {
        section.summary("JNDI java: namespace");
        if (installed) {
            section.row(JAVA_NAMES, "java:comp/env, from the CDI container");
        } else if (kept != null) {
            section.row(JAVA_NAMES, "the application's factory, " + kept);
        } else {
            section.row(JAVA_NAMES, "not initialized");
        }
    }
}
