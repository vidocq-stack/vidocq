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
package io.vidocq.runtime.extensions.essentials.chappe;

import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.StartupReportContext;
import io.vidocq.runtime.spi.report.StartupReportSection;
import io.vidocq.runtime.spi.report.Verbosity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Vidocq/vidocq#111: the {@code http} section declares the listeners of the configuration with the address each one
 * really bound, so that the report prints the routes of the other sections as absolute URLs.
 */
class ChappeListenersSectionTest {

    private ChappeServerBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        new ChappeEngineExtension().configure(null);
        bootstrap = new ChappeServerBootstrap();
    }

    @AfterEach
    void tearDown() {
        bootstrap.onStop();
        ChappeMountPoint.uninstall();
    }

    /** What the section received: its summary and the listeners it declared, in order. */
    static final class Recorded implements StartupReportSection {
        String summary;
        final Map<String, String> listeners = new LinkedHashMap<>();
        final List<String> other = new ArrayList<>();

        @Override public StartupReportSection summary(String text) { summary = text; return this; }
        @Override public StartupReportSection row(String key, Object value) { other.add(key); return this; }
        @Override public StartupReportSection list(String key, Collection<String> items) { other.add(key); return this; }
        @Override public StartupReportSection secret(String key, boolean configured) { other.add(key); return this; }
        @Override public StartupReportSection listener(String name, String uri) { listeners.put(name, uri); return this; }
        @Override public StartupReportSection route(String l, String m, String p, String h) { other.add(p); return this; }
        @Override public StartupReportSection anomaly(String c, String m, String h) { other.add(c); return this; }
    }

    private static final StartupReportContext CONTEXT = new StartupReportContext() {
        @Override public Verbosity verbosity() { return Verbosity.DETAILED; }
        @Override public LaunchMode launchMode() { return LaunchMode.DEV; }
        @Override public boolean hasBeanOfType(String typeName) { return false; }
        @Override public <T> Optional<T> lookup(Class<T> type) { return Optional.empty(); }
        @Override public List<String> routeUrls(String handlerClassName) { return List.of(); }
    };

    private Recorded contribute() {
        Recorded section = new Recorded();
        new ChappeEngineExtension().contribute(CONTEXT, section);
        return section;
    }

    private static FakeExtensionContext context(String... extra) {
        Map<String, String> config = new java.util.HashMap<>(Map.of(
                "vidocq.chappe.listener.default.host", "127.0.0.1",
                "vidocq.chappe.listener.default.port", "0"));
        for (int i = 0; i < extra.length; i += 2) {
            config.put(extra[i], extra[i + 1]);
        }
        return new FakeExtensionContext(TestConfig.of(config));
    }

    @Test
    void isTheHttpSection() {
        ChappeEngineExtension engine = new ChappeEngineExtension();

        assertEquals("http", engine.id());
        assertEquals("HTTP (Chappe)", engine.title());
    }

    @Test
    void declaresEachConfiguredListenerWithTheAddressItBound() {
        bootstrap.onStart(context());
        int port = bootstrap.boundAddresses().get(ChappeListener.DEFAULT).getPort();

        Recorded section = contribute();

        String url = "http://127.0.0.1:" + port + "/";
        assertEquals(Map.of("default", url), section.listeners);
        assertEquals("default " + url, section.summary);
        assertEquals(Optional.of(url), ChappeMountPoint.instance().boundUrl(ChappeListener.DEFAULT));
    }

    @Test
    void leavesAListenerOfAnExtensionToItsOwner() {
        ChappeMountPoint.instance().declareListener(ChappeListener.http("dev", "127.0.0.1", 0),
                new ListenerOptions(false, true, null, null));
        bootstrap.onStart(context());

        Recorded section = contribute();

        assertEquals(List.of("default"), List.copyOf(section.listeners.keySet()),
                "the dev console declares its own listener in its own section");
        assertTrue(ChappeMountPoint.instance().boundUrl("dev").isPresent(), "its address is still known");
    }

    @Test
    void saysSoWhenNothingListens() {
        Recorded section = contribute();

        assertEquals("no listener started", section.summary);
        assertTrue(section.listeners.isEmpty());
        assertEquals(Optional.empty(), ChappeMountPoint.instance().boundUrl(ChappeListener.DEFAULT));
    }

    @Test
    void writesNothingOnceTheEngineIsGone() {
        ChappeMountPoint.uninstall();

        Recorded section = contribute();

        assertEquals("no listener started", section.summary);
    }
}
