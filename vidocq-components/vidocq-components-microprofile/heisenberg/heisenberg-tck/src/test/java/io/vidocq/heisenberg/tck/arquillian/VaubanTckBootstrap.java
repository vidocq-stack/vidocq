/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.vidocq.heisenberg.tck.arquillian;

import io.vidocq.dirac.cdi.internal.CountedInterceptor;
import io.vidocq.dirac.cdi.internal.GaugeRegistrationBean;
import io.vidocq.dirac.cdi.internal.MetricRegistryProducerBean;
import io.vidocq.dirac.cdi.internal.TimedInterceptor;
import io.vidocq.heisenberg.cdi.internal.BulkheadStateRegistryBean;
import io.vidocq.heisenberg.cdi.internal.DiracFtMetricsRecorder;
import io.vidocq.heisenberg.cdi.internal.FaultToleranceInterceptor;
import io.vidocq.heisenberg.cdi.internal.FaultTolerancePriority3850Interceptor;
import io.vidocq.heisenberg.cdi.internal.HeisenbergExtension;
import io.vidocq.heisenberg.cdi.internal.StateRegistryBean;
import io.vidocq.vauban.core.container.VaubanContainer;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.Node;
import org.jboss.shrinkwrap.api.asset.ArchiveAsset;
import org.jboss.shrinkwrap.api.asset.Asset;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Gère le cycle de vie du container Vauban CDI dans le runner TCK Arquillian Heisenberg.
 *
 * <p>Chaque déploiement Arquillian (une ShrinkWrap archive par classe de test TCK) :
 * <ol>
 *   <li>extrait {@code META-INF/microprofile-config.properties} de l'archive et l'exporte
 *       en system properties pour que Ravel/MP-Config les voie ;</li>
 *   <li>arrête tout container existant ;</li>
 *   <li>collecte les classes applicatives de l'archive ;</li>
 *   <li>démarre un nouveau {@link VaubanContainer} avec :
 *     <ul>
 *       <li>l'extension BCE {@link HeisenbergExtension} ;</li>
 *       <li>les intercepteurs {@link FaultToleranceInterceptor} et
 *           {@link FaultTolerancePriority3850Interceptor} ;</li>
 *       <li>les beans d'état {@link StateRegistryBean} et {@link BulkheadStateRegistryBean} ;</li>
 *       <li>toutes les classes de l'archive.</li>
 *     </ul>
 *   </li>
 * </ol>
 */
final class VaubanTckBootstrap {

    private static final List<String> EXPORTED_KEYS = new ArrayList<>();

    private VaubanTckBootstrap() {}

    static void deploy(Archive<?> archive) {
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
                .addBeanClass(DiracFtMetricsRecorder.class);
        for (Class<?> c : beanClasses) {
            builder.addBeanClass(c);
        }
        builder.build();

        // Active le RequestContext pour la durée du déploiement TCK : les tests
        // MicroProfile Fault Tolerance utilisent des beans @RequestScoped et
        // s'attendent à ce que le contexte soit actif pendant l'exécution
        // des méthodes de test (sinon ContextNotActiveException).
        VaubanContainer container = VaubanContainer.current();
        if (container != null) {
            container.requestContext().activate();
        }

        System.err.println("[HeisenbergTCK] Vauban CDI container started for archive '"
                + archive.getName() + "' — " + beanClasses.size() + " class(es) registered");
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

