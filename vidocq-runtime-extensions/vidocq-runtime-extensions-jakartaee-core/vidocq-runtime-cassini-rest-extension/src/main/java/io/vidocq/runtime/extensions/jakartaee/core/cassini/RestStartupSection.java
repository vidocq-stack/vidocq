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
package io.vidocq.runtime.extensions.jakartaee.core.cassini;

import io.vidocq.cassini.spi.http.RouteDescription;
import io.vidocq.runtime.extensions.essentials.chappe.ChappeMountPoint;
import io.vidocq.runtime.extensions.jakartaee.core.cassini.live.RestMount;
import io.vidocq.runtime.spi.report.StartupReportContext;
import io.vidocq.runtime.spi.report.StartupReportSection;
import io.vidocq.runtime.spi.report.Verbosity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Writes the {@code rest} section of the startup report from the mounts of this boot, read from memory: what
 * {@link RestMounts} recorded when the stacks were built.
 *
 * <p>Every route is declared with {@link StartupReportSection#route}, in match order, at every level: the report
 * prints them in its detailed form only, and a later section, such as {@code mcp}, finds the URL of its own resource
 * through {@link StartupReportContext#routeUrls}. The route table names handler classes, as
 * {@link StartupReportSection#route}'s contract has every routing brick do.
 */
final class RestStartupSection {

    /** How the report shows a sub-resource locator, whose HTTP method is resolved per request. */
    static final String ANY_METHOD = "*";

    private RestStartupSection() {}

    /**
     * Writes the section.
     *
     * @param mounts  the mounts of this boot
     * @param pages   the pages other extensions advertised on a listener, such as the Swagger UI: written as links,
     *                for every listener a mount is on, so that the {@code rest} panel offers to open them
     * @param context what the report lets a contributor read
     * @param section where the section is written
     */
    static void write(List<RestMount> mounts, Function<String, List<ChappeMountPoint.Page>> pages,
                      StartupReportContext context, StartupReportSection section) {
        if (mounts.isEmpty()) {
            section.summary("no resource class");
            return;
        }
        TreeSet<String> resources = new TreeSet<>();
        TreeSet<String> providers = new TreeSet<>();
        int routes = 0;
        List<String> prefixes = new ArrayList<>();
        for (RestMount mount : mounts) {
            resources.addAll(mount.resources());
            providers.addAll(mount.providers());
            routes += mount.routes().size();
            prefixes.add(mount.displayPrefix());
        }
        section.summary(count(resources.size(), "resource class", "resource classes") + ", "
                + count(routes, "route", "routes") + ", " + count(providers.size(), "provider", "providers")
                + " at " + String.join(", ", prefixes));

        for (RestMount mount : mounts) {
            for (RouteDescription route : mount.routes()) {
                section.route(mount.listener(), route.isLocator() ? ANY_METHOD : route.httpMethod(),
                        mount.pathOnListener(route), route.resourceClass() + "#" + route.methodName());
            }
        }

        // Links before the level check: the dev console shows them whatever the log's level.
        Set<String> listeners = new LinkedHashSet<>();
        mounts.forEach(mount -> listeners.add(mount.listener()));
        for (String listener : listeners) {
            for (ChappeMountPoint.Page page : pages.apply(listener)) {
                section.link(page.label(), listener, page.path());
            }
        }

        if (context.verbosity() != Verbosity.DETAILED) {
            return;
        }
        for (RestMount mount : mounts) {
            section.row("mount " + mount.name(), "listener " + mount.listener() + ", prefix " + mount.displayPrefix()
                    + (mount.stripPrefix() ? "" : " (not stripped)") + ", "
                    + count(mount.routes().size(), "route", "routes"));
        }
        section.list("resources", List.copyOf(resources));
        TreeMap<String, TreeSet<String>> byKind = new TreeMap<>();
        List<String> kinds = new ArrayList<>();
        for (RestMount mount : mounts) {
            for (RestMount.ProviderGroup group : mount.providerGroups()) {
                if (!byKind.containsKey(group.kind())) {
                    kinds.add(group.kind());
                }
                byKind.computeIfAbsent(group.kind(), k -> new TreeSet<>()).addAll(group.classes());
            }
        }
        for (String kind : kinds) {
            section.list(kind, List.copyOf(byKind.get(kind)));
        }
    }

    /** {@code 1 route}, {@code 0 routes}, {@code 2 resource classes}. */
    static String count(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }
}
