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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Maps the {@code vidocq.mcp.*} keys to the {@link McpServerConfig} that langchain4j-cdi reads as its
 * {@code @Named("mcp-server")} bean.
 *
 * <p>No key set means no mapping: the server keeps its own defaults, or the application's producer. A value that
 * cannot be used fails the boot with an {@link IllegalArgumentException} that names the key and what it accepts,
 * never the value, since {@value #REQUEST_STATE_SECRET} is a secret.
 */
final class McpConfigMapping {

    static final String SERVER_NAME = "vidocq.mcp.serverName";
    static final String SERVER_VERSION = "vidocq.mcp.serverVersion";
    static final String ALLOWED_ORIGINS = "vidocq.mcp.allowedOrigins";
    static final String MRTR_MODE = "vidocq.mcp.mrtrMode";
    static final String REQUEST_STATE_SECRET = "vidocq.mcp.requestStateSecret";
    static final String REQUEST_STATE_TTL = "vidocq.mcp.requestStateTtl";
    static final String CONTINUATION_TIMEOUT = "vidocq.mcp.continuationTimeout";
    static final String CACHE_TTL = "vidocq.mcp.cacheTtl";
    static final String CACHE_SCOPE = "vidocq.mcp.cacheScope";

    /** The nine keys, exactly: {@code VidocqExtension.configKeys()} returns them so a typo is reported. */
    static final Set<String> KEYS = Set.of(SERVER_NAME, SERVER_VERSION, ALLOWED_ORIGINS, MRTR_MODE,
            REQUEST_STATE_SECRET, REQUEST_STATE_TTL, CONTINUATION_TIMEOUT, CACHE_TTL, CACHE_SCOPE);

    /** The shortest request-state secret langchain4j-cdi accepts, in UTF-8 bytes ({@code McpMrtrSupport}). */
    static final int MIN_SECRET_BYTES = 32;

    private McpConfigMapping() {}

    /**
     * The configuration the {@code vidocq.mcp.*} keys describe.
     *
     * @param config the Vidocq configuration
     * @return the mapped configuration; empty when no key is set to a non-blank value
     * @throws IllegalArgumentException when a key has a value it does not accept; the message names the key only
     */
    static Optional<McpServerConfig> map(VidocqConfiguration config) {
        if (KEYS.stream().noneMatch(key -> value(config, key).isPresent())) {
            return Optional.empty();
        }
        McpServerConfig.McpServerConfigBuilder builder = McpServerConfig.builder();
        value(config, SERVER_NAME).ifPresent(builder::serverName);
        value(config, SERVER_VERSION).ifPresent(builder::serverVersion);
        value(config, ALLOWED_ORIGINS).map(McpConfigMapping::origins).ifPresent(builder::allowedOrigins);
        value(config, MRTR_MODE).map(McpConfigMapping::mrtrMode).ifPresent(builder::mrtrMode);
        value(config, REQUEST_STATE_SECRET).map(McpConfigMapping::secret).ifPresent(builder::requestStateSecret);
        duration(config, REQUEST_STATE_TTL).ifPresent(builder::requestStateTtl);
        duration(config, CONTINUATION_TIMEOUT).ifPresent(builder::continuationTimeout);
        duration(config, CACHE_TTL).ifPresent(builder::cacheTtl);
        value(config, CACHE_SCOPE).map(McpConfigMapping::cacheScope).ifPresent(builder::cacheScope);
        return Optional.of(builder.build());
    }

    private static Optional<String> value(VidocqConfiguration config, String key) {
        return config.property(key).map(String::strip).filter(value -> !value.isEmpty());
    }

    private static List<String> origins(String value) {
        return Arrays.stream(value.split(",")).map(String::strip).filter(origin -> !origin.isEmpty()).toList();
    }

    private static McpMrtrMode mrtrMode(String value) {
        try {
            return McpMrtrMode.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw invalid(MRTR_MODE, "REPLAY or CONTINUATION");
        }
    }

    private static String secret(String value) {
        if (value.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw invalid(REQUEST_STATE_SECRET, "at least " + MIN_SECRET_BYTES + " bytes in UTF-8");
        }
        return value;
    }

    private static Optional<Duration> duration(VidocqConfiguration config, String key) {
        return value(config, key).map(value -> {
            Duration duration;
            try {
                duration = Duration.parse(value);
            } catch (DateTimeParseException unparsable) {
                throw invalid(key, "an ISO-8601 duration such as PT10M");
            }
            if (duration.isNegative()) {
                throw invalid(key, "a duration of zero or more, such as PT10M");
            }
            return duration;
        });
    }

    private static String cacheScope(String value) {
        String scope = value.toLowerCase(Locale.ROOT);
        if (!scope.equals(McpServerConfig.CACHE_SCOPE_PUBLIC) && !scope.equals(McpServerConfig.CACHE_SCOPE_PRIVATE)) {
            throw invalid(CACHE_SCOPE, "public or private");
        }
        return scope;
    }

    private static IllegalArgumentException invalid(String key, String expected) {
        return new IllegalArgumentException("Invalid " + key + ": expected " + expected
                + " (the configured value is not repeated here)");
    }
}
