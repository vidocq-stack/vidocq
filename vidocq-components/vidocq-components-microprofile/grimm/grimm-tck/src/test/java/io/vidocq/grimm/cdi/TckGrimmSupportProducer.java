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
package io.vidocq.grimm.cdi;

import io.vidocq.grimm.internal.config.GrimmConfig;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * TCK-only producers used by the Arquillian harness to wire Grimm beans without
 * relying on the full runtime producer chain.
 */
@Dependent
public class TckGrimmSupportProducer {

    @Produces
    public GrimmConfig produceGrimmConfig() {
        if (!TckDeploymentContext.config().isEmpty()) {
            return GrimmConfig.fromMap(TckDeploymentContext.config());
        }
        try {
            Config config = ConfigProvider.getConfig();
            return GrimmConfig.fromMpConfig(config);
        } catch (IllegalStateException | java.util.ServiceConfigurationError e) {
            return fromClasspathProperties();
        }
    }

    private GrimmConfig fromClasspathProperties() {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = TckGrimmSupportProducer.class.getClassLoader();
        }
        String[] candidates = {
                "META-INF/microprofile-config.properties",
                "microprofile-config.properties"
        };
        for (String candidate : candidates) {
            try (InputStream stream = classLoader.getResourceAsStream(candidate)) {
                if (stream == null) {
                    continue;
                }
                Properties properties = new Properties();
                properties.load(stream);
                Map<String, String> source = new LinkedHashMap<>();
                for (String name : properties.stringPropertyNames()) {
                    source.put(name, properties.getProperty(name));
                }
                return GrimmConfig.fromMap(source);
            } catch (Exception ignored) {
                // Try next candidate and eventually fall back to defaults.
            }
        }
        return GrimmConfig.defaults();
    }

    @Produces
    public ScannedTypes produceScannedTypes() {
        return ScannedTypes.of(TckDeploymentContext.discoveredTypes());
    }
}


