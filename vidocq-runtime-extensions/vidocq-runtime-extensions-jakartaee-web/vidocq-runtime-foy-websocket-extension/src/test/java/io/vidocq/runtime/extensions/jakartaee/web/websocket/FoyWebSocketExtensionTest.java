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
package io.vidocq.runtime.extensions.jakartaee.web.websocket;

import io.vidocq.runtime.extensions.jakartaee.web.foy.spi.ServletInitParameters;
import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.config.ConfigSource;
import io.vidocq.runtime.spi.config.VidocqConfig;
import io.vidocq.runtime.spi.report.StartupReportSection;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The WebSocket extension declares the origins key, forwards it to Foy and reports it. */
class FoyWebSocketExtensionTest {

    private static final String KEY = "vidocq.websocket.allowed-origins";
    private static final String FOY_PARAMETER = "io.vidocq.foy.websocket.allowed-origins";

    /** A configuration over a map. */
    private record MapConfig(Map<String, String> values) implements VidocqConfig {
        @Override public Optional<String> getValue(String key) { return Optional.ofNullable(values.get(key)); }
        @Override public <T> Optional<T> getValue(String key, Class<T> type) {
            return getValue(key).map(type::cast);
        }
        @Override public <T> List<T> getValues(String key, Class<T> elementType) { return List.of(); }
        @Override public Iterable<String> getPropertyNames() { return values.keySet(); }
        @Override public Iterable<ConfigSource> getConfigSources() { return List.of(); }
    }

    /** Records the rows the contributor writes. */
    private static final class Section implements StartupReportSection {
        final Map<String, String> rows = new LinkedHashMap<>();
        @Override public StartupReportSection summary(String text) { return this; }
        @Override public StartupReportSection row(String key, Object value) { rows.put(key, String.valueOf(value)); return this; }
        @Override public StartupReportSection list(String key, Collection<String> items) { return this; }
        @Override public StartupReportSection secret(String key, boolean configured) { return this; }
        @Override public StartupReportSection listener(String name, String boundBaseUri) { return this; }
        @Override public StartupReportSection route(String listener, String method, String path, String handler) { return this; }
        @Override public StartupReportSection anomaly(String code, String message, String hint) { return this; }
    }

    private static ExtensionContext contextOver(VidocqConfig config) {
        return (ExtensionContext) Proxy.newProxyInstance(ExtensionContext.class.getClassLoader(),
                new Class<?>[] {ExtensionContext.class}, (proxy, method, args) -> {
                    if ("config".equals(method.getName())) {
                        return config;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static String originsRow(String configured) {
        var values = configured == null ? Map.<String, String>of() : Map.of(KEY, configured);
        var extension = new FoyWebSocketExtension();
        extension.onStart(contextOver(new MapConfig(values)));
        var section = new Section();
        extension.contribute(null, section);
        return section.rows.get("allowed origins");
    }

    @Test
    void declaresTheOriginsKey() {
        var extension = new FoyWebSocketExtension();

        assertEquals("foy-websocket", extension.name());
        assertEquals(Set.of(KEY), extension.configKeys());
    }

    @Test
    void forwardsTheOriginsAsTheFoyInitParameter() {
        var parameters = new AllowedOriginsParameter()
                .initParameters(new MapConfig(Map.of(KEY, "https://a.example, https://b.example")));

        assertEquals(Map.of(FOY_PARAMETER, "https://a.example, https://b.example"), parameters);
    }

    @Test
    void aMalformedOriginIsRefusedWithTheNameOfTheKey() {
        var config = new MapConfig(Map.of(KEY, "ftp://x"));
        var parameters = new AllowedOriginsParameter();

        var failure = assertThrows(IllegalArgumentException.class, () -> parameters.initParameters(config));

        assertTrue(failure.getMessage().startsWith(KEY), failure.getMessage());
    }

    @Test
    void forwardsNothingWhenTheKeyIsAbsent() {
        assertEquals(Map.of(), new AllowedOriginsParameter().initParameters(new MapConfig(Map.of())));
    }

    @Test
    void reportsSameOriginOnlyWhenTheKeyIsAbsentOrBlank() {
        assertEquals("same origin only", originsRow(null));
        assertEquals("same origin only", originsRow("  "));
    }

    @Test
    void reportsAnyForTheWildcardAndTheListOtherwise() {
        assertEquals("any", originsRow("*"));
        assertEquals("any", originsRow("*, https://a.example"));
        assertEquals("https://a.example, https://b.example", originsRow("https://a.example, https://b.example"));
    }

    @Test
    void claimsNothingBeforeStartAndAfterStop() {
        var extension = new FoyWebSocketExtension();
        var section = new Section();
        extension.contribute(null, section);
        assertEquals("not initialized", section.rows.get("allowed origins"));

        extension.onStart(contextOver(new MapConfig(Map.of())));
        extension.onStop();
        var after = new Section();
        extension.contribute(null, after);
        assertEquals("not initialized", after.rows.get("allowed origins"));
    }

    @Test
    void theModuleProvidesTheInitParametersToFoysServiceLoader() {
        var provides = FoyWebSocketExtension.class.getModule().getDescriptor().provides().stream()
                .filter(p -> p.service().equals(ServletInitParameters.class.getName()))
                .findFirst().orElseThrow();

        assertEquals(List.of(AllowedOriginsParameter.class.getName()), provides.providers());
    }
}
