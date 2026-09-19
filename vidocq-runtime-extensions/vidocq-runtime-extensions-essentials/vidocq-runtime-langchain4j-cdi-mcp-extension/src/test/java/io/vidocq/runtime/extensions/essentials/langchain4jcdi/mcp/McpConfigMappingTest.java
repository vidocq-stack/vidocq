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
package io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp;

import dev.langchain4j.cdi.mcp.server.transport.McpMrtrMode;
import dev.langchain4j.cdi.mcp.server.transport.McpServerConfig;
import io.vidocq.runtime.spi.VidocqConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** U2 of Vidocq/vidocq#94: every {@code vidocq.mcp.*} key reaches its builder method; a bad value names its key only. */
class McpConfigMappingTest {

    private static final String SECRET = "a-request-state-secret-of-40-characters!";

    @Test
    void noKeyMeansNoMapping() {
        assertTrue(McpConfigMapping.map(config(Map.of())).isEmpty());
        assertTrue(McpConfigMapping.map(config(Map.of(McpConfigMapping.SERVER_NAME, "  "))).isEmpty(),
                "a blank value is not a value");
    }

    @Test
    void everyKeyReachesTheConfiguration() {
        McpServerConfig mapped = McpConfigMapping.map(config(Map.of(
                McpConfigMapping.SERVER_NAME, "time-server",
                McpConfigMapping.SERVER_VERSION, "1.2.3",
                McpConfigMapping.ALLOWED_ORIGINS, " https://a.example , https://b.example ,",
                McpConfigMapping.MRTR_MODE, "continuation",
                McpConfigMapping.REQUEST_STATE_SECRET, SECRET,
                McpConfigMapping.REQUEST_STATE_TTL, "PT20M",
                McpConfigMapping.CONTINUATION_TIMEOUT, "PT2M",
                McpConfigMapping.CACHE_TTL, "PT30S",
                McpConfigMapping.CACHE_SCOPE, "PRIVATE"))).orElseThrow();

        assertEquals("time-server", mapped.getServerName());
        assertEquals("1.2.3", mapped.getServerVersion());
        assertEquals(List.of("https://a.example", "https://b.example"), mapped.getAllowedOrigins());
        assertEquals(McpMrtrMode.CONTINUATION, mapped.getMrtrMode());
        assertEquals(SECRET, mapped.getRequestStateSecret());
        assertEquals(Duration.ofMinutes(20), mapped.getRequestStateTtl());
        assertEquals(Duration.ofMinutes(2), mapped.getContinuationTimeout());
        assertEquals(Duration.ofSeconds(30), mapped.getCacheTtl());
        assertEquals(McpServerConfig.CACHE_SCOPE_PRIVATE, mapped.getCacheScope());
    }

    @Test
    void oneKeyKeepsTheServerDefaultsForTheOthers() {
        McpServerConfig mapped = McpConfigMapping.map(config(Map.of(McpConfigMapping.SERVER_NAME, "time-server")))
                .orElseThrow();
        McpServerConfig defaults = new McpServerConfig();

        assertEquals("time-server", mapped.getServerName());
        assertEquals(defaults.getServerVersion(), mapped.getServerVersion());
        assertEquals(defaults.getAllowedOrigins(), mapped.getAllowedOrigins());
        assertEquals(defaults.getMrtrMode(), mapped.getMrtrMode());
        assertEquals(null, mapped.getRequestStateSecret());
        assertEquals(defaults.getRequestStateTtl(), mapped.getRequestStateTtl());
        assertEquals(defaults.getContinuationTimeout(), mapped.getContinuationTimeout());
        assertEquals(defaults.getCacheTtl(), mapped.getCacheTtl());
        assertEquals(defaults.getCacheScope(), mapped.getCacheScope());
    }

    @ParameterizedTest
    @CsvSource({
            "vidocq.mcp.mrtrMode,            STICKY,                    REPLAY or CONTINUATION",
            "vidocq.mcp.requestStateTtl,     ten minutes,               an ISO-8601 duration",
            "vidocq.mcp.continuationTimeout, -PT1M,                     a duration of zero or more",
            "vidocq.mcp.cacheTtl,            later,                     an ISO-8601 duration",
            "vidocq.mcp.cacheScope,          shared,                    public or private",
            "vidocq.mcp.requestStateSecret,  short-secret-31-bytes-long-xyz!, at least 32 bytes in UTF-8"
    })
    void anInvalidValueFailsNamingTheKeyButNeverTheValue(String key, String value, String expected) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> McpConfigMapping.map(config(Map.of(key, value))));

        assertTrue(failure.getMessage().contains(key), failure.getMessage());
        assertTrue(failure.getMessage().contains(expected), failure.getMessage());
        assertFalse(failure.getMessage().contains(value.strip()), failure.getMessage());
    }

    @Test
    void theExtensionDeclaresExactlyTheNineKeys() {
        assertEquals(Set.of("vidocq.mcp.serverName", "vidocq.mcp.serverVersion", "vidocq.mcp.allowedOrigins",
                "vidocq.mcp.mrtrMode", "vidocq.mcp.requestStateSecret", "vidocq.mcp.requestStateTtl",
                "vidocq.mcp.continuationTimeout", "vidocq.mcp.cacheTtl", "vidocq.mcp.cacheScope"),
                new McpExtension().configKeys());
    }

    static VidocqConfiguration config(Map<String, String> values) {
        return key -> Optional.ofNullable(values.get(key));
    }
}
