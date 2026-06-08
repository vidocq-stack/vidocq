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
package io.vidocq.dirac.tck.arquillian;

import io.vidocq.dirac.cdi.internal.CountedInterceptor;
import io.vidocq.dirac.cdi.internal.DiracExtension;
import io.vidocq.dirac.cdi.internal.GaugeRegistrationBean;
import io.vidocq.dirac.cdi.internal.MetricRegistryProducerBean;
import io.vidocq.dirac.cdi.internal.TimedInterceptor;
import io.vidocq.vauban.core.container.VaubanContainer;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.asset.ArchiveAsset;
import org.jboss.shrinkwrap.api.asset.Asset;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Manages the lifecycle of the Vauban CDI container in the Dirac Arquillian TCK runner.
 *
 * <p>For each Arquillian deployment (one ShrinkWrap archive per TCK test class):</p>
 * <ol>
 *   <li>stops any existing container;</li>
 *   <li>collects application classes from the archive;</li>
 *   <li>starts a new {@link VaubanContainer} with Dirac beans + archive classes.</li>
 * </ol>
 */
final class VaubanDiracTckBootstrap {
    private static final Map<String, String> PREVIOUS_CONFIG_VALUES = new HashMap<>();

    private VaubanDiracTckBootstrap() {}

    static void deploy(Archive<?> archive) {
        VaubanContainer existing = VaubanContainer.current();
        if (existing != null && existing.isRunning()) {
            try { existing.close(); } catch (Exception ignored) {}
        }

        applyArchiveConfig(archive);

        List<Class<?>> beanClasses = extractBeanClasses(archive);

        var builder = VaubanContainer.builder()
                .addBeanClass(DiracExtension.class)
                .addBeanClass(CountedInterceptor.class)
                .addBeanClass(TimedInterceptor.class)
                .addBeanClass(MetricRegistryProducerBean.class)
                .addBeanClass(GaugeRegistrationBean.class);
        for (Class<?> c : beanClasses) {
            builder.addBeanClass(c);
        }
        builder.build();

        VaubanContainer container = VaubanContainer.current();
        if (container != null) {
            container.requestContext().activate();
        }

        System.err.println("[DiracTCK] Vauban CDI container started for archive '"
                + archive.getName() + "' — " + beanClasses.size() + " class(es) registered");
    }

    static void undeploy() {
        VaubanContainer existing = VaubanContainer.current();
        if (existing != null && existing.isRunning()) {
            try { existing.close(); } catch (Exception ignored) {}
        }
        restoreArchiveConfig();
        System.err.println("[DiracTCK] Vauban CDI container stopped");
    }

    private static synchronized void applyArchiveConfig(Archive<?> archive) {
        restoreArchiveConfig();
        collectConfigPropertiesFromArchive(archive, false);
    }

    private static void collectConfigPropertiesFromArchive(Archive<?> archive, boolean insideLib) {
        for (var entry : archive.getContent().entrySet()) {
            String path = entry.getKey().get();
            Asset asset = entry.getValue().getAsset();
            if (asset instanceof ArchiveAsset archiveAsset
                    && (path.startsWith("/WEB-INF/lib/") || insideLib)) {
                collectConfigPropertiesFromArchive(archiveAsset.getArchive(), true);
                continue;
            }
            if (!path.endsWith("microprofile-config.properties")) {
                continue;
            }
            var properties = new Properties();
            try (var stream = asset.openStream()) {
                properties.load(stream);
            } catch (Exception ignored) {
                continue;
            }
            for (var key : properties.stringPropertyNames()) {
                PREVIOUS_CONFIG_VALUES.putIfAbsent(key, System.getProperty(key));
                System.setProperty(key, properties.getProperty(key));
            }
        }
    }

    private static synchronized void restoreArchiveConfig() {
        for (var entry : PREVIOUS_CONFIG_VALUES.entrySet()) {
            if (entry.getValue() == null) {
                System.clearProperty(entry.getKey());
            } else {
                System.setProperty(entry.getKey(), entry.getValue());
            }
        }
        PREVIOUS_CONFIG_VALUES.clear();
    }

    private static List<Class<?>> extractBeanClasses(Archive<?> archive) {
        var classes = new ArrayList<Class<?>>();
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        collectClassesFromArchive(archive, cl, classes, false);
        return classes;
    }

    private static void collectClassesFromArchive(Archive<?> archive, ClassLoader cl,
                                                   List<Class<?>> classes, boolean insideLib) {
        for (var entry : archive.getContent().entrySet()) {
            String path = entry.getKey().get();
            Asset asset = entry.getValue().getAsset();
            if (asset instanceof ArchiveAsset archiveAsset
                    && (path.startsWith("/WEB-INF/lib/") || insideLib)) {
                collectClassesFromArchive(archiveAsset.getArchive(), cl, classes, true);
                continue;
            }
            if (!path.endsWith(".class")) continue;
            if (path.contains("module-info")) continue;
            String stripped = path.startsWith("/") ? path.substring(1) : path;
            if (stripped.startsWith("WEB-INF/classes/")) {
                stripped = stripped.substring("WEB-INF/classes/".length());
            }
            String className = stripped.replace('/', '.').replace(".class", "");
            if (className.isBlank()) continue;
            try {
                Class<?> clazz = Class.forName(className, false, cl);
                classes.add(clazz);
            } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
                // skip
            }
        }
    }
}
