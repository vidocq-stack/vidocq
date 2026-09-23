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
package io.vidocq.runtime.core.report;

import io.vidocq.runtime.core.report.Section.Cells;
import io.vidocq.runtime.core.report.Section.Items;
import io.vidocq.runtime.core.report.Section.Line;
import io.vidocq.runtime.core.report.Section.Row;
import io.vidocq.runtime.core.report.Section.Text;
import io.vidocq.runtime.spi.report.StartupReportSection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The section Vidocq hands one {@link io.vidocq.runtime.spi.report.StartupReportContributor}: it keeps what the
 * contributor writes, in order and as text, and becomes a {@link Section} once every contributor ran, so that a
 * route is joined with the base URI of its listener whichever section declared it.
 *
 * <p>Nothing is formatted here: {@link StartupReportRenderer} cleans and cuts every value when it prints the
 * section. An anomaly is logged at once, through the {@link StartupRecorder}, which cleans it as it cleans every
 * anomaly, since it reaches the log before any report. Every method accepts {@code null} and never throws.
 */
final class ContributedSection implements StartupReportSection {

    private final String id;
    private final StartupRecorder recorder;
    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, String> listeners = new LinkedHashMap<>();
    private String summary;

    /**
     * @param id       the id of the contributor, the {@linkplain Anomaly#source() source} of its anomalies
     * @param recorder where its anomalies are logged and kept
     */
    ContributedSection(String id, StartupRecorder recorder) {
        this.id = id;
        this.recorder = recorder;
    }

    /** What a section keeps in order: a line as it is written, or a route that is joined later. */
    private sealed interface Entry permits Written, Route, Link {}

    private record Written(Line line) implements Entry {}

    /**
     * A route: {@code handler} names its class, then {@code #} and its method.
     *
     * @param listener the name of the listener it is on
     * @param method   the HTTP method
     * @param path     the path on that listener
     * @param handler  what handles it, {@code com.acme.McpEndpoint#handlePost}
     */
    record Route(String listener, String method, String path, String handler) implements Entry {

        /** The binary name of the class that handles it: the handler up to its {@code #}. */
        String handlerClass() {
            int hash = handler.indexOf('#');
            return hash < 0 ? handler : handler.substring(0, hash);
        }

        /** The handler as the report prints it, its class by simple name: {@code McpEndpoint#handlePost}. */
        String shortHandler() {
            String type = handlerClass();
            return type.substring(type.lastIndexOf('.') + 1) + handler.substring(type.length());
        }

        /**
         * The absolute URL of this route, its path after the base URI of its listener, or the path alone when
         * no section declared that listener.
         */
        String url(Map<String, String> listeners) {
            String base = listeners.get(listener);
            return base == null ? path : join(base, path);
        }
    }

    /**
     * A link: joined with the base URI of its listener when the section is printed, like a route.
     *
     * @param label    what it is
     * @param listener the name of the listener it is on
     * @param path     the path on that listener
     */
    private record Link(String label, String listener, String path) implements Entry {}

    /** Whether {@code url} is absolute, joined with a listener, rather than a path alone. */
    private static boolean absolute(String url) {
        return url.startsWith("http://") || url.startsWith("https://");
    }

    @Override
    public StartupReportSection summary(String text) {
        summary = text;
        return this;
    }

    @Override
    public StartupReportSection row(String key, Object value) {
        entries.add(new Written(new Row(key, String.valueOf(value))));
        return this;
    }

    @Override
    public StartupReportSection list(String key, Collection<String> items) {
        if (items != null && !items.isEmpty()) {
            entries.add(new Written(new Items(key, new ArrayList<>(items))));
        }
        return this;
    }

    @Override
    public StartupReportSection secret(String key, boolean configured) {
        entries.add(new Written(new Row(key, configured ? "configured" : "not configured")));
        return this;
    }

    @Override
    public StartupReportSection listener(String name, String boundBaseUri) {
        entries.add(new Written(new Row(name, boundBaseUri)));
        listeners.putIfAbsent(String.valueOf(name), String.valueOf(boundBaseUri));
        return this;
    }

    @Override
    public StartupReportSection route(String listener, String method, String path, String handler) {
        entries.add(new Route(String.valueOf(listener), String.valueOf(method), String.valueOf(path),
                String.valueOf(handler)));
        return this;
    }

    @Override
    public StartupReportSection link(String label, String listener, String path) {
        entries.add(new Link(String.valueOf(label), String.valueOf(listener), String.valueOf(path)));
        return this;
    }

    @Override
    public StartupReportSection anomaly(String code, String message, String hint) {
        recorder.anomaly(String.valueOf(code), String.valueOf(message), hint, id);
        return this;
    }

    /** The routes written so far, in order. */
    List<Route> routes() {
        List<Route> routes = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry instanceof Route route) {
                routes.add(route);
            }
        }
        return routes;
    }

    /**
     * The section to print: {@code title} after the id, unless it is the id, and the time it took; the summary
     * line first, then everything else in the order it was written, each route joined with {@code listeners}.
     *
     * @param title     the title of the contributor, or {@code null}
     * @param nanos     how long the contributor took to write it, or {@code -1}
     * @param listeners the base URI of every listener of the report, by name
     */
    Section toSection(String title, long nanos, Map<String, String> listeners) {
        List<Line> lines = new ArrayList<>();
        if (summary != null) {
            lines.add(new Text(summary));
        }
        for (Entry entry : entries) {
            switch (entry) {
                case Written written -> lines.add(written.line());
                case Route route -> {
                    String url = route.url(listeners);
                    // A GET with no template variable is a URL a browser can open as it is.
                    boolean openable = "GET".equals(route.method()) && absolute(url) && url.indexOf('{') < 0;
                    lines.add(new Cells(List.of(route.method(), url, route.shortHandler()), openable ? url : null));
                }
                case Link link -> {
                    String base = listeners.get(link.listener());
                    String url = base == null ? link.path() : join(base, link.path());
                    lines.add(new Row(link.label(), url, absolute(url) ? url : null));
                }
            }
        }
        String headline = title == null || title.isBlank() || title.equals(id) ? null : title;
        return new Section(id, headline, summary, lines, nanos);
    }

    /** The listeners of {@code sections}, by name, the first section that declared a name keeping it. */
    static Map<String, String> listeners(List<ContributedSection> sections) {
        Map<String, String> all = new LinkedHashMap<>();
        for (ContributedSection section : sections) {
            section.listeners.forEach(all::putIfAbsent);
        }
        return all;
    }

    /** {@code base} and {@code path} with one {@code /} between them: {@code http://localhost:8081/} + {@code mcp}. */
    static String join(String base, String path) {
        String left = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return left + (path.startsWith("/") ? path : "/" + path);
    }
}
