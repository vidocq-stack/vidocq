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

import io.vidocq.runtime.spi.report.StartupReportSection;
import io.vidocq.vauban.naming.JavaNaming;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.naming.Context;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** vidocq#297: the extension makes the java: namespace the JVM default, unless the application chose a factory. */
class NamingExtensionTest {

    private static final String APPLICATION_FACTORY = "com.example.naming.AppContextFactory";

    /** Records what the contributor writes. */
    private static final class Section implements StartupReportSection {
        String summary;
        final Map<String, String> rows = new LinkedHashMap<>();
        @Override public StartupReportSection summary(String text) { summary = text; return this; }
        @Override public StartupReportSection row(String key, Object value) { rows.put(key, String.valueOf(value)); return this; }
        @Override public StartupReportSection list(String key, Collection<String> items) { return this; }
        @Override public StartupReportSection secret(String key, boolean configured) { return this; }
        @Override public StartupReportSection listener(String name, String boundBaseUri) { return this; }
        @Override public StartupReportSection route(String listener, String method, String path, String handler) { return this; }
        @Override public StartupReportSection anomaly(String code, String message, String hint) { return this; }
    }

    @AfterEach
    void clear() {
        System.clearProperty(Context.INITIAL_CONTEXT_FACTORY);
    }

    @Test
    void configure_installs_the_factory_and_stop_removes_it() {
        var extension = new NamingExtension();

        extension.configure(null);
        assertEquals(JavaNaming.INITIAL_CONTEXT_FACTORY, System.getProperty(Context.INITIAL_CONTEXT_FACTORY));

        extension.onStop();
        assertNull(System.getProperty(Context.INITIAL_CONTEXT_FACTORY));
    }

    @Test
    void the_factory_the_application_chose_is_kept_through_start_and_stop() {
        System.setProperty(Context.INITIAL_CONTEXT_FACTORY, APPLICATION_FACTORY);
        var extension = new NamingExtension();

        extension.configure(null);
        extension.onStop();

        assertEquals(APPLICATION_FACTORY, System.getProperty(Context.INITIAL_CONTEXT_FACTORY));
    }

    /** A configuration with these keys only. */
    private record Config(Map<String, String> keys) implements io.vidocq.runtime.spi.VidocqConfiguration {
        @Override public java.util.Optional<String> property(String key) { return java.util.Optional.ofNullable(keys.get(key)); }
        @Override public Iterable<String> propertyNames() { return keys.keySet(); }
    }

    @Test
    void the_report_lists_each_declared_name() {
        var keys = new LinkedHashMap<String, String>();
        keys.put("vidocq.naming.comp/env/toto", "com.acme.Toto @Named(\"Toto\")");
        keys.put("vidocq.naming.comp/env/jdbc/legacy", "java:comp/env/jdbc/orders");
        var extension = new NamingExtension();
        extension.configure(new Config(keys));
        var section = new Section();
        extension.contribute(null, section);
        extension.onStop();

        assertEquals(java.util.List.of("java: names", "java:comp/env/jdbc/legacy", "java:comp/env/toto"),
                java.util.List.copyOf(section.rows.keySet()));
        assertEquals("java:comp/env/jdbc/orders", section.rows.get("java:comp/env/jdbc/legacy"));
        assertEquals("com.acme.Toto @Named(\"Toto\")", section.rows.get("java:comp/env/toto"));
    }

    @Test
    void the_extension_claims_the_naming_keys() {
        assertEquals(java.util.Set.of("vidocq.naming.*"), new NamingExtension().configKeys());
    }

    @Test
    void the_report_says_which_factory_answers_java_names() {
        var installed = new NamingExtension();
        installed.configure(null);
        var section = new Section();
        installed.contribute(null, section);
        installed.onStop();

        assertEquals("JNDI java: namespace", section.summary);
        assertEquals("the declared names, from the CDI container", section.rows.get("java: names"));

        System.setProperty(Context.INITIAL_CONTEXT_FACTORY, APPLICATION_FACTORY);
        var kept = new NamingExtension();
        kept.configure(null);
        var keptSection = new Section();
        kept.contribute(null, keptSection);

        assertEquals("the application's factory, " + APPLICATION_FACTORY, keptSection.rows.get("java: names"));
    }
}
