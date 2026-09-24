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

import io.vidocq.runtime.core.config.ExternalFileConfigSource;
import io.vidocq.runtime.core.config.PropertiesFileConfigSource;
import io.vidocq.runtime.spi.config.ConfigSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Reads the {@code vidocq.dev.*} tuning keys the application would itself read (spec §5): a lookup a
 * {@link DefaultDevServiceContext} can fall back to, after its seed, system properties and environment
 * variables, for a key starting with {@code vidocq.dev.}.
 */
public final class ApplicationFiles {

    private ApplicationFiles() {}

    /**
     * The {@code vidocq.dev.*} keys of the application's files, as the application will read them: the
     * external file when {@code vidocq.config.dir} or {@code VIDOCQ_CONFIG_DIR} names one, over
     * {@code vidocq.properties} and {@code application.properties} of {@code classesDir}. Other keys are
     * never answered (spec §5).
     */
    public static Function<String, Optional<String>> of(Path classesDir) {
        Map<String, String> tuning = new HashMap<>();
        // Lowest precedence first: a later source overwrites.
        copyTuning(classpathSource(classesDir), tuning);
        if (System.getProperty("vidocq.config.dir") != null || System.getenv("VIDOCQ_CONFIG_DIR") != null) {
            copyTuning(new ExternalFileConfigSource(), tuning);
        }
        Map<String, String> frozen = Map.copyOf(tuning);
        return key -> Optional.ofNullable(frozen.get(key));
    }

    /** {@code vidocq.properties} and {@code application.properties} of {@code classesDir} only, never the plugin's. */
    private static ConfigSource classpathSource(Path classesDir) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try (URLClassLoader loader =
                new URLClassLoader(new URL[] {classesDir.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            thread.setContextClassLoader(loader);
            return new PropertiesFileConfigSource();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the application's files in " + classesDir, e);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private static void copyTuning(ConfigSource source, Map<String, String> into) {
        for (String key : source.getPropertyNames()) {
            if (key.startsWith("vidocq.dev.")) {
                String value = source.getValue(key);
                if (value != null) {
                    into.put(key, value);
                }
            }
        }
    }
}
