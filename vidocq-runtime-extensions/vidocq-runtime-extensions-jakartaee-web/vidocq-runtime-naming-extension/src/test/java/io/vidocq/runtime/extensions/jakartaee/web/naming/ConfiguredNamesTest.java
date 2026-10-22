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
import io.vidocq.vauban.naming.Binder;
import io.vidocq.vauban.naming.JndiName;
import io.vidocq.vauban.naming.JndiTarget;
import io.vidocq.vauban.naming.Namespace;
import jakarta.enterprise.inject.literal.NamedLiteral;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The java: names an application declares in vidocq.properties: vidocq.naming.<name without java:> = <target>. */
class ConfiguredNamesTest {

    /** The configuration of the test: these keys only. */
    private record Config(Map<String, String> keys) implements VidocqConfiguration {
        @Override public Optional<String> property(String key) { return Optional.ofNullable(keys.get(key)); }
        @Override public Iterable<String> propertyNames() { return keys.keySet(); }
    }

    /** Records what is bound. */
    private static final class Recorder implements Binder {
        final Map<String, JndiTarget> bound = new LinkedHashMap<>();
        @Override public Binder bind(String name, JndiTarget target) { bound.put(name, target); return this; }
    }

    @AfterEach
    void clear() {
        ConfiguredNames.declare(Map.of());
    }

    private static Map<String, String> declarations(String... keysAndValues) {
        var keys = new LinkedHashMap<String, String>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            keys.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return NamingExtension.declarations(new Config(keys));
    }

    private static Map<String, JndiTarget> bound(Map<String, String> declarations) {
        ConfiguredNames.declare(declarations);
        var recorder = new Recorder();
        new ConfiguredNames().bind(recorder);
        return recorder.bound;
    }

    @Test
    void aKeyDeclaresTheNameAfterJava() {
        assertEquals(Map.of("java:comp/env/jdbc/orders", "javax.sql.DataSource @Named(\"orders\")"),
                declarations("vidocq.naming.comp/env/jdbc/orders", " javax.sql.DataSource @Named(\"orders\") ",
                        "vidocq.pool.orders.url", "jdbc:h2:mem:orders"));
    }

    @Test
    void eachOfTheFourNamespacesIsAccepted() {
        assertEquals(Set.of("java:comp/a", "java:module/env/b", "java:app/env/c", "java:global/d"), declarations(
                "vidocq.naming.comp/a", "java.lang.String", "vidocq.naming.module/env/b", "java.lang.String",
                "vidocq.naming.app/env/c", "java.lang.String", "vidocq.naming.global/d", "java.lang.String").keySet());
    }

    @Test
    void aValueStartingWithJavaIsALink() {
        var bound = bound(declarations("vidocq.naming.app/env/jdbc/reporting", "java:comp/env/jdbc/orders"));

        assertEquals(new JndiTarget.Link(new JndiName(Namespace.COMP, "env/jdbc/orders")),
                bound.get("java:app/env/jdbc/reporting"));
    }

    @Test
    void anyOtherValueIsABeanDeclaration() {
        var bound = bound(declarations("vidocq.naming.comp/env/toto", "java.lang.String @Named(\"Toto\")"));

        var bean = (JndiTarget.Bean) bound.get("java:comp/env/toto");
        assertEquals(String.class, bean.type());
        assertEquals(Set.of(NamedLiteral.of("Toto")), bean.qualifiers());
    }

    @Test
    void aKeyOutsideTheFourNamespacesFailsTheStart() {
        for (String key : new String[] {"vidocq.naming.comp.env.toto", "vidocq.naming.java:comp/env/toto",
                "vidocq.naming.other/x", "vidocq.naming.comp"}) {
            var failure = assertThrows(IllegalStateException.class, () -> declarations(key, "java.lang.String"));
            assertTrue(failure.getMessage().contains(key), failure.getMessage());
        }
    }

    @Test
    void withoutKeysNothingIsDeclared() {
        assertEquals(Map.of(), declarations("vidocq.pool.url", "jdbc:h2:mem:x"));
        assertEquals(Map.of(), bound(Map.of()));
    }

    @Test
    void aBackslashOfTheDeclarationIsDoubledInAPropertiesFile() throws IOException {
        // A properties file reads \\ as \: the declaration @Named("say \"hi\"") is written with doubled backslashes.
        var file = new Properties();
        file.load(new StringReader("vidocq.naming.comp/env/greeting = java.lang.String @Named(\"say \\\\\"hi\\\\\"\")\n"));
        var keys = new LinkedHashMap<String, String>();
        file.stringPropertyNames().forEach(key -> keys.put(key, file.getProperty(key)));

        var bean = (JndiTarget.Bean) bound(NamingExtension.declarations(new Config(keys))).get("java:comp/env/greeting");

        assertEquals(Set.of(NamedLiteral.of("say \"hi\"")), bean.qualifiers());
    }
}
