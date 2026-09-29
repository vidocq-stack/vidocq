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
package io.vidocq.runtime.devservices.host;

import io.vidocq.runtime.devservices.spi.DevServiceContext;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The {@link DevServiceContext} seen by {@link io.vidocq.runtime.devservices.spi.DevService}
 * providers during a {@code vidocq:dev} run.
 *
 * <p>Configuration resolves, in decreasing precedence: the goal's collected properties (the
 * {@code vidocq.dev.systemProperties}/explicit {@code -D} values seeded at construction, then the
 * outputs of earlier providers folded in via {@link #merge(Map)}), then the JVM system properties,
 * then environment variables (MP-Config dotted-to-underscore mapping).</p>
 *
 * <p>Two kinds of keys after that: <b>opt-out keys</b>, such as {@code vidocq.pool[.<name>].url} or
 * {@code mp.jwt.verify.issuer}, decide whether a provider starts at all, and are never resolved from the
 * application's own files — a baked-in default such as {@code vidocq.pool.url=jdbc:postgresql://localhost:5432/...}
 * must not suppress a dev service; only an <i>explicit</i> override (a {@code -D}, an env var, or the dev
 * goal's configuration) opts a provider out via {@link io.vidocq.runtime.devservices.spi.DevService#appliesWhen}.
 * <b>Tuning keys</b>, everything starting with {@code vidocq.dev.}, say how to start a provider, and are also
 * resolved, as a last resort, from the {@code applicationFiles} lookup given at construction.</p>
 *
 * <p>Apart from both, {@link #applicationProperty} answers any key of the application's files (the
 * {@code applicationValues} lookup) and {@link #onApplicationClasspath} whether its class path holds a class: what a
 * provider reads to learn what the application is configured for, never to opt out.</p>
 */
public final class DefaultDevServiceContext implements DevServiceContext {

    private final Path basedir;
    private final Map<String, String> resolved;
    private final System.Logger logger;
    private final Function<String, Optional<String>> applicationFiles;
    private final Function<String, Optional<String>> applicationValues;
    private final Predicate<String> applicationClasspath;

    public DefaultDevServiceContext(Path basedir, Map<String, String> seed) {
        this(basedir, seed, key -> Optional.empty());
    }

    public DefaultDevServiceContext(
            Path basedir, Map<String, String> seed, Function<String, Optional<String>> applicationFiles) {
        this(basedir, seed, applicationFiles, key -> Optional.empty(), className -> false);
    }

    /**
     * @param basedir              the project base directory
     * @param seed                 the goal's explicit values
     * @param applicationFiles     the {@code vidocq.dev.*} keys of the application's files, {@link ApplicationFiles#of}
     * @param applicationValues    every key of the same files, {@link ApplicationFiles#allOf}, for
     *                             {@link #applicationProperty}
     * @param applicationClasspath whether the application's class path holds a class, for
     *                             {@link #onApplicationClasspath}: an {@link ApplicationClasspath}'s {@code contains}
     */
    public DefaultDevServiceContext(Path basedir, Map<String, String> seed,
            Function<String, Optional<String>> applicationFiles, Function<String, Optional<String>> applicationValues,
            Predicate<String> applicationClasspath) {
        this.basedir = basedir;
        this.resolved = new LinkedHashMap<>(seed);
        this.logger = System.getLogger("vidocq.dev.devservices");
        this.applicationFiles = applicationFiles;
        this.applicationValues = applicationValues;
        this.applicationClasspath = applicationClasspath;
    }

    /** Fold the outputs of a provider so later providers can read them. */
    public void merge(Map<String, String> props) {
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
        if (isPresent(v)) {
            return Optional.of(v);
        }
        if (key.startsWith("vidocq.dev.")) {
            return applicationFiles.apply(key).filter(DefaultDevServiceContext::isPresent);
        }
        return Optional.empty();
    }

    /** From the {@code applicationValues} lookup given at construction; a blank value is absent. */
    @Override
    public Optional<String> applicationProperty(String key) {
        return applicationValues.apply(key).filter(DefaultDevServiceContext::isPresent);
    }

    @Override
    public boolean onApplicationClasspath(String className) {
        return applicationClasspath.test(className);
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
