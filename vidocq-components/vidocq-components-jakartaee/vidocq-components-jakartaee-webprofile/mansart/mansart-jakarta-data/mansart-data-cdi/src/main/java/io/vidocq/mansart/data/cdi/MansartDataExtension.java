package io.vidocq.mansart.data.cdi;

import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Discovery;
import jakarta.enterprise.inject.build.compatible.spi.ScannedClasses;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * CDI 4.1 {@link BuildCompatibleExtension} that wires Mansart {@code @Repository} interfaces into
 * the bean container. Reads {@code META-INF/mansart-repositories.list} (produced by
 * {@code mansart-data-processor}) and registers one synthetic bean per entry, scoped
 * {@code @ApplicationScoped}, instantiated via {@link MansartRepoCreator}.
 *
 * <p>Also makes sure {@link MansartRuntimeProducer} is scanned so the shared
 * {@link io.vidocq.mansart.data.core.RepositoryRuntime} is available for injection.
 */
public final class MansartDataExtension implements BuildCompatibleExtension {

    private final List<RepoEntry> entries = new ArrayList<>();

    record RepoEntry(String itfFqn, String implFqn) {}

    @Discovery
    public void readRepositoriesIndex(ScannedClasses scanned) {
        // Default RepositoryRuntime producer — only declare the class to scan when it's not
        // already part of the bean archive (otherwise some containers register it twice and
        // lose the @Produces method on the second pass).
        try {
            Class.forName(MansartRuntimeProducer.class.getName(), false, currentClassLoader());
        } catch (ClassNotFoundException e) {
            scanned.add(MansartRuntimeProducer.class.getName());
        }

        ClassLoader cl = currentClassLoader();
        Enumeration<URL> resources;
        try {
            resources = cl.getResources("META-INF/mansart-repositories.list");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        while (resources.hasMoreElements()) {
            URL url = resources.nextElement();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(url.openStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int eq = line.indexOf('=');
                    if (eq < 0) continue;
                    entries.add(new RepoEntry(line.substring(0, eq).trim(), line.substring(eq + 1).trim()));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Synthesis
    public void registerRepositories(SyntheticComponents components) {
        ClassLoader cl = currentClassLoader();
        for (RepoEntry e : entries) {
            Class<?> itf;
            Class<?> impl;
            try {
                itf  = cl.loadClass(e.itfFqn());
                impl = cl.loadClass(e.implFqn());
            } catch (ClassNotFoundException ex) {
                throw new IllegalStateException(
                        "mansart-repositories.list references unknown class: " + ex.getMessage(), ex);
            }
            registerOne(components, itf, impl);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void registerOne(SyntheticComponents components, Class<?> itf, Class<?> impl) {
        components.<Object>addBean((Class) itf)
                .type(itf)
                .scope(jakarta.inject.Singleton.class)
                .withParam("implClass", impl)
                .createWith(MansartRepoCreator.class);
    }

    private static ClassLoader currentClassLoader() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        return cl != null ? cl : MansartDataExtension.class.getClassLoader();
    }
}
