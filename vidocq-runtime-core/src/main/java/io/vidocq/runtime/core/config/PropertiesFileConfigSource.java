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
package io.vidocq.runtime.core.config;

import io.vidocq.runtime.spi.config.ConfigSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

/**
 * Source reading {@code vidocq.properties} and {@code application.properties} from the classpath.
 * Ordinal 100.
 */
public final class PropertiesFileConfigSource implements ConfigSource {

    private static final System.Logger LOG =
            System.getLogger(PropertiesFileConfigSource.class.getName());

    private static final String[] FILES = {"vidocq.properties", "application.properties"};

    private final Properties properties;

    public PropertiesFileConfigSource() {
        this.properties = load();
    }

    @Override
    public String getName() {
        return "PropertiesFile";
    }

    @Override
    public int getOrdinal() {
        return 100;
    }

    @Override
    public String getValue(String key) {
        return properties.getProperty(key);
    }

    @Override
    public Set<String> getPropertyNames() {
        Set<String> names = new HashSet<>();
        for (Object k : properties.keySet()) {
            names.add(String.valueOf(k));
        }
        return Collections.unmodifiableSet(names);
    }

    private static Properties load() {
        Properties props = new Properties();
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = PropertiesFileConfigSource.class.getClassLoader();
        for (String file : FILES) {
            try (InputStream is = cl.getResourceAsStream(file)) {
                if (is != null) {
                    props.load(is);
                }
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "Failed to load " + file, e);
            }
        }
        return props;
    }
}
