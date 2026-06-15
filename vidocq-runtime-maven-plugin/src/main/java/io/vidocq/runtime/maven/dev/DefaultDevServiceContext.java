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
package io.vidocq.runtime.maven.dev;

import io.vidocq.runtime.devservices.spi.DevServiceContext;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The {@link DevServiceContext} seen by {@link io.vidocq.runtime.devservices.spi.DevService}
 * providers during a {@code vidocq:dev} run.
 *
 * <p>Configuration resolves, in decreasing precedence: the goal's collected properties (the
 * {@code vidocq.dev.systemProperties}/explicit {@code -D} values seeded at construction, then the
 * outputs of earlier providers folded in via {@link #merge(Map)}), then the JVM system properties,
 * then environment variables (MP-Config dotted-to-underscore mapping).</p>
 *
 * <p><b>The application's {@code vidocq.properties} is deliberately NOT consulted.</b> A baked-in
 * default such as {@code vidocq.pool.url=jdbc:postgresql://localhost:5432/...} must not suppress a
 * dev service — only an <i>explicit</i> override (a {@code -D}, an env var, or the dev goal's
 * configuration) opts a provider out via {@link io.vidocq.runtime.devservices.spi.DevService#appliesWhen}.</p>
 */
final class DefaultDevServiceContext implements DevServiceContext {

    private final Path basedir;
    private final Map<String, String> resolved;
    private final System.Logger logger;

    DefaultDevServiceContext(Path basedir, Map<String, String> seed) {
        this.basedir = basedir;
        this.resolved = new LinkedHashMap<>(seed);
        this.logger = System.getLogger("vidocq.dev.devservices");
    }

    /** Fold the outputs of a provider so later providers can read them. */
    void merge(Map<String, String> props) {
        if (props != null) {
            resolved.putAll(props);
        }
    }

    @Override
    public Optional<String> property(String key) {
        String v = resolved.get(key);
        if (isPresent(v)) {
            return Optional.of(v);
        }
        v = System.getProperty(key);
        if (isPresent(v)) {
            return Optional.of(v);
        }
        v = System.getenv(toEnvVar(key));
        return isPresent(v) ? Optional.of(v) : Optional.empty();
    }

    @Override
    public Map<String, String> properties() {
        return Collections.unmodifiableMap(resolved);
    }

    @Override
    public Path basedir() {
        return basedir;
    }

    @Override
    public Path resolve(String relative) {
        return basedir.resolve(relative).normalize();
    }

    @Override
    public System.Logger log() {
        return logger;
    }

    private static boolean isPresent(String v) {
        return v != null && !v.isBlank();
    }

    /** MP-Config mapping: {@code vidocq.pool.url} → {@code VIDOCQ_POOL_URL}. */
    private static String toEnvVar(String key) {
        StringBuilder sb = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            sb.append(Character.isLetterOrDigit(c) ? Character.toUpperCase(c) : '_');
        }
        return sb.toString();
    }
}
