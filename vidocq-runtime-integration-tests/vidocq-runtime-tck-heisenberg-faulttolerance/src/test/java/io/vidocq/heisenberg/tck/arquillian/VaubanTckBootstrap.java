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
package io.vidocq.heisenberg.tck.arquillian;

import io.vidocq.dirac.cdi.internal.CountedInterceptor;
import io.vidocq.dirac.cdi.internal.GaugeRegistrationBean;
import io.vidocq.dirac.cdi.internal.MetricRegistryProducerBean;
import io.vidocq.dirac.cdi.internal.TimedInterceptor;
import io.vidocq.humboldt.cdi.HumboldtBuildCompatibleExtension;
import io.vidocq.humboldt.cdi.WithSpanInterceptor;
import io.vidocq.heisenberg.cdi.internal.BulkheadStateRegistryBean;
import io.vidocq.heisenberg.cdi.internal.DiracFtMetricsRecorder;
import io.vidocq.heisenberg.cdi.internal.FaultToleranceInterceptor;
import io.vidocq.heisenberg.cdi.internal.FaultTolerancePriority3850Interceptor;
import io.vidocq.heisenberg.cdi.internal.HeisenbergExtension;
import io.vidocq.heisenberg.cdi.internal.OtelFtMetricsRecorder;
import io.vidocq.heisenberg.cdi.internal.StateRegistryBean;
import io.vidocq.vauban.core.container.VaubanContainer;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import org.eclipse.microprofile.fault.tolerance.tck.telemetryMetrics.util.InMemoryMetricReader;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.Node;
import org.jboss.shrinkwrap.api.asset.ArchiveAsset;
import org.jboss.shrinkwrap.api.asset.Asset;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Manages the lifecycle of the Vauban CDI container in the Heisenberg Arquillian TCK runner.
 *
 * <p>Each Arquillian deployment (one ShrinkWrap archive per TCK test class):
 * <ol>
 *   <li>extracts {@code META-INF/microprofile-config.properties} from the archive and exports it
 *       as system properties so that Ravel/MP-Config can see it;</li>
 *   <li>stops any existing container;</li>
 *   <li>collects the application classes from the archive;</li>
 *   <li>starts a new {@link VaubanContainer} with:
 *     <ul>
 *       <li>the BCE extension {@link HeisenbergExtension};</li>
 *       <li>the interceptors {@link FaultToleranceInterceptor} and
 *           {@link FaultTolerancePriority3850Interceptor};</li>
 *       <li>the state beans {@link StateRegistryBean} and {@link BulkheadStateRegistryBean};</li>
 *       <li>all classes from the archive.</li>
 *     </ul>
 *   </li>
 * </ol>
 */
final class VaubanTckBootstrap {

    private static final List<String> EXPORTED_KEYS = new ArrayList<>();

    private VaubanTckBootstrap() {}

    static void deploy(Archive<?> archive) {
        ensureTelemetryReaderIsRegistered();

        Properties configProps = extractConfig(archive);
        exportToSystemProperties(configProps);

        VaubanContainer existing = VaubanContainer.current();
        if (existing != null && existing.isRunning()) {
            try { existing.close(); } catch (Exception ignored) {}
        }

        List<Class<?>> beanClasses = extractBeanClasses(archive);

        var builder = VaubanContainer.builder()
                .addBeanClass(HeisenbergExtension.class)
                .addBeanClass(FaultToleranceInterceptor.class)
                .addBeanClass(FaultTolerancePriority3850Interceptor.class)
                .addBeanClass(StateRegistryBean.class)
                .addBeanClass(BulkheadStateRegistryBean.class)
                .addBeanClass(MetricRegistryProducerBean.class)
                .addBeanClass(GaugeRegistrationBean.class)
                .addBeanClass(CountedInterceptor.class)
                .addBeanClass(TimedInterceptor.class)
                .addBeanClass(MetricRegistryProxyProducerBean.class)
                .addBeanClass(HumboldtBuildCompatibleExtension.class)
                .addBeanClass(WithSpanInterceptor.class)
                .addBeanClass(DiracFtMetricsRecorder.class)
                .addBeanClass(OtelFtMetricsRecorder.class);
        for (Class<?> c : beanClasses) {
            builder.addBeanClass(c);
        }
        builder.build();

        // Activate the RequestContext for the duration of the TCK deployment: MicroProfile
        // Fault Tolerance tests use @RequestScoped beans and expect the context to be active
        // while the test methods run (otherwise ContextNotActiveException).
        VaubanContainer container = VaubanContainer.current();
        if (container != null) {
            container.requestContext().activate();
        }

        System.err.println("[HeisenbergTCK] Vauban CDI container started for archive '"
                + archive.getName() + "' — " + beanClasses.size() + " class(es) registered");
    }

    private static void ensureTelemetryReaderIsRegistered() {
        try {
            GlobalOpenTelemetry.resetForTest();
            InMemoryMetricReader reader = InMemoryMetricReader.current();
            SdkMeterProvider meterProvider = SdkMeterProvider.builder()
                    .registerMetricReader(reader)
                    .build();
            OpenTelemetrySdk.builder()
                    .setMeterProvider(meterProvider)
                    .buildAndRegisterGlobal();
        } catch (Exception e) {
            System.err.println("[HeisenbergTCK] OpenTelemetry reader bootstrap failed: " + e.getMessage());
        }
    }

    static void undeploy() {
        clearSystemProperties();
        VaubanContainer existing = VaubanContainer.current();
        if (existing != null && existing.isRunning()) {
            try { existing.close(); } catch (Exception ignored) {}
        }
        System.err.println("[HeisenbergTCK] Vauban CDI container stopped");
    }

    // -------------------------------------------------------------------
    // Config extraction
    // -------------------------------------------------------------------

    private static Properties extractConfig(Archive<?> archive) {
        Properties props = new Properties();
        Node node = archive.get("/META-INF/microprofile-config.properties");
        if (node == null) {
            node = archive.get("/WEB-INF/classes/META-INF/microprofile-config.properties");
        }
        if (node != null) {
            Asset asset = node.getAsset();
            if (asset != null) {
                try (InputStream is = asset.openStream()) {
                    props.load(is);
                } catch (Exception ignored) {}
            }
            if (!props.isEmpty()) return props;
        }
        return extractConfigFromLibraries(archive);
    }

    private static Properties extractConfigFromLibraries(Archive<?> archive) {
        Properties props = new Properties();
        Node libDir = archive.get("/WEB-INF/lib");
        if (libDir == null) return props;
        for (Node child : libDir.getChildren()) {
            Asset asset = child.getAsset();
            if (!(asset instanceof ArchiveAsset archiveAsset)) continue;
            Archive<?> nested = archiveAsset.getArchive();
            Node configNode = nested.get("/META-INF/microprofile-config.properties");
            if (configNode == null) continue;
            Asset configAsset = configNode.getAsset();
            if (configAsset == null) continue;
            try (InputStream is = configAsset.openStream()) {
                props.load(is);
                if (!props.isEmpty()) return props;
            } catch (Exception ignored) {}
        }
        return props;
    }

    private static void exportToSystemProperties(Properties props) {
        clearSystemProperties();
        for (String key : props.stringPropertyNames()) {
            System.setProperty(key, props.getProperty(key));
            EXPORTED_KEYS.add(key);
        }
    }

    private static void clearSystemProperties() {
        for (String key : EXPORTED_KEYS) {
            System.clearProperty(key);
        }
        EXPORTED_KEYS.clear();
    }

    // -------------------------------------------------------------------
    // Bean class extraction
    // -------------------------------------------------------------------

    /**
     * TCK classes that we replace with our own beans to avoid collisions
     * (typically {@code @Inject @RegistryType(BASE)} in TCK producers that do not
     * work with Vauban's resolution of qualifiers with members). Our
     * {@link MetricRegistryProxyProducerBean} already produces everything needed.
     */
    private static final java.util.Set<String> EXCLUDED_TCK_CLASSES = java.util.Set.of(
            "org.eclipse.microprofile.fault.tolerance.tck.metrics.util.MetricRegistryProvider"
    );

    private static List<Class<?>> extractBeanClasses(Archive<?> archive) {
        var classes = new ArrayList<Class<?>>();
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        collectClassesFromArchive(archive, cl, classes, false);
        classes.removeIf(c -> EXCLUDED_TCK_CLASSES.contains(c.getName()));
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

