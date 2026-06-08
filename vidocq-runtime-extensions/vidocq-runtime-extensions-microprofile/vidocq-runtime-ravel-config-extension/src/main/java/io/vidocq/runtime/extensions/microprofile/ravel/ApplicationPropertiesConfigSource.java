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
package io.vidocq.runtime.extensions.microprofile.ravel;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * MicroProfile {@link org.eclipse.microprofile.config.spi.ConfigSource} which reads
 * {@code application.properties} from the classpath.
 *
 * <p>Historical SE/MP-compatible convention. In Ravel mode, this file is not
 * not read by default — only {@code META-INF/microprofile-config.properties} is,
 * in accordance with the MP Config 3.1 §3 spec. This source restores the
 * compatibility by saving {@code application.properties} as a PM
 * {@code ConfigSource} standard.</p>
 *
 * <p><b>Ordinal 100</b> — same as
 * {@code MicroprofilePropertiesConfigSource} (Ravel), which places
 * {@code application.properties} and {@code microprofile-config.properties} at
 * same priority level.
 * {@link VidocqPropertiesConfigSource} (ordinal 105) wins on both.</p>
 */
public final class ApplicationPropertiesConfigSource
        implements org.eclipse.microprofile.config.spi.ConfigSource {

    private static final System.Logger LOG =
            System.getLogger(ApplicationPropertiesConfigSource.class.getName());

    private static final String FILE = "application.properties";

    private final Properties properties;

    public ApplicationPropertiesConfigSource() {
        this.properties = load();
    }

    @Override
    public String getName() {
        return "ApplicationPropertiesConfigSource";
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

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> map = new java.util.HashMap<>();
        for (Object k : properties.keySet()) {
            String name = String.valueOf(k);
            map.put(name, properties.getProperty(name));
        }
        return Collections.unmodifiableMap(map);
    }

    private static Properties load() {
        Properties props = new Properties();
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = ApplicationPropertiesConfigSource.class.getClassLoader();
        try (InputStream is = cl.getResourceAsStream(FILE)) {
            if (is != null) {
                props.load(is);
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Failed to load " + FILE, e);
        }
        return props;
    }
}
