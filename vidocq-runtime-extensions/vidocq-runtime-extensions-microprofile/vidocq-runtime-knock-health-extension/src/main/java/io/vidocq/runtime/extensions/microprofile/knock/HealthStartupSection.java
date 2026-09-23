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
package io.vidocq.runtime.extensions.microprofile.knock;

import io.vidocq.knock.spi.HealthCheckRegistry;
import io.vidocq.knock.spi.ProbeType;
import io.vidocq.runtime.spi.report.StartupReportContext;
import io.vidocq.runtime.spi.report.StartupReportSection;
import io.vidocq.runtime.spi.report.Verbosity;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Writes the {@code health} section of the startup report, which is also the boot facts of the {@code health} panel
 * of the dev console.
 *
 * <ul>
 *   <li>the summary: how many checks each probe has, {@code 3 checks: 1 liveness, 2 readiness, 0 startup};</li>
 *   <li>a {@code Health} link to {@code GET /health}, at the path the {@code rest} section declared for Knock's
 *       resource, such as {@code /api/health} under a {@code restful} mount, or {@code /health} when it declared none;
 *       </li>
 *   <li>a list per probe that has checks, at {@link Verbosity#DETAILED} only: the checks by the name the panel shows
 *       them under.</li>
 * </ul>
 * The names are read from Knock's registry: no check is called and none is created.
 */
final class HealthStartupSection {

    /** The Jakarta REST resource Knock serves the probes with, as the {@code rest} section names its routes. */
    static final String HEALTH_RESOURCE = "io.vidocq.knock.jaxrs.KnockHealthResource";

    /** The listener of the link: the one Cassini mounts on unless configured otherwise. */
    static final String LISTENER = "default";

    /** The path of {@code GET /health} on Knock's resource, before any mount prefix. */
    static final String HEALTH_PATH = "/health";

    private static final List<ProbeType> PROBES = List.of(ProbeType.LIVENESS, ProbeType.READINESS, ProbeType.STARTUP);

    private HealthStartupSection() {}

    /**
     * Writes the section.
     *
     * @param registry the existing registry, or {@code null} when there is none
     * @param absence  why there is none, such as {@code registry not created yet}; read only when {@code registry}
     *                 is {@code null}
     * @param context  the report being written
     * @param section  where the section goes
     */
    static void write(HealthCheckRegistry registry, String absence, StartupReportContext context,
            StartupReportSection section) {
        // The link before the level check: the dev console shows it whatever the log's level.
        section.link("Health", LISTENER, healthPath(context));
        if (registry == null) {
            section.summary("no health check (" + absence + ")");
            return;
        }
        StringBuilder summary = new StringBuilder();
        int total = 0;
        for (ProbeType probe : PROBES) {
            int count = registry.getCheckNames(probe).size();
            total += count;
            summary.append(summary.isEmpty() ? "" : ", ").append(count).append(' ').append(label(probe));
        }
        section.summary(total + (total == 1 ? " check: " : " checks: ") + summary);
        if (context.verbosity() != Verbosity.DETAILED) {
            return;
        }
        for (ProbeType probe : PROBES) {
            TreeSet<String> names = new TreeSet<>();
            registry.getCheckNames(probe).forEach(name -> names.add(CheckKeys.display(name)));
            if (!names.isEmpty()) {
                section.list(label(probe), List.copyOf(names));
            }
        }
    }

    /**
     * The path of {@code GET /health} on its listener: taken from the route the {@code rest} section declared for
     * Knock's resource, which carries the mount prefix, else {@value #HEALTH_PATH}.
     */
    static String healthPath(StartupReportContext context) {
        for (String url : context.routeUrls(HEALTH_RESOURCE)) {
            try {
                String path = URI.create(url).getPath();
                if (path != null && path.endsWith(HEALTH_PATH)) {
                    return path;
                }
            } catch (IllegalArgumentException malformed) {
                // not a URL this section can read: try the next one
            }
        }
        return HEALTH_PATH;
    }

    private static String label(ProbeType probe) {
        return probe.name().toLowerCase(Locale.ROOT);
    }
}
