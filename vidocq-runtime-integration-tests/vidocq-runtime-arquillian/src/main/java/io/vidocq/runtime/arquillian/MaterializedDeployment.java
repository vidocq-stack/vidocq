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
package io.vidocq.runtime.arquillian;

import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.Node;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * A ShrinkWrap archive exploded on disk, exposed through a dedicated
 * {@link URLClassLoader}.
 * <p>
 * TCK deployments carry more than bean classes: {@code microprofile-config.properties},
 * JWT public keys, static OpenAPI documents, {@code META-INF/services} entries.
 * Those assets live only inside the in-memory archive and are invisible to the
 * runtime unless surfaced on a real classpath. Materializing the archive and
 * installing the returned class loader as the thread context class loader for
 * the duration of the deployment makes the runtime see the archive content the
 * same way it would see a regular application classpath.
 * </p>
 * <p>
 * WebArchive layout is normalized: entries under {@code WEB-INF/classes/} are
 * relocated to the classpath root, and {@code WEB-INF/lib/*.jar} libraries are
 * added to the class loader as separate roots.
 * </p>
 */
final class MaterializedDeployment implements AutoCloseable {

    private final Path root;
    private final List<Path> libraries;
    private final URLClassLoader classLoader;

    private MaterializedDeployment(Path root, List<Path> libraries, URLClassLoader classLoader) {
        this.root = root;
        this.libraries = libraries;
        this.classLoader = classLoader;
    }

    /**
     * Explodes the archive into a temporary directory and wraps it in a class
     * loader whose parent is this class's own loader.
     */
    static MaterializedDeployment of(Archive<?> archive) throws IOException {
        Path root = Files.createTempDirectory("vidocq-deployment-");
        List<Path> libraries = new ArrayList<>();

        for (var entry : archive.getContent().entrySet()) {
            Node node = entry.getValue();
            if (node == null || node.getAsset() == null) {
                continue;
            }
            String path = entry.getKey().get();
            if (path == null) {
                continue;
            }
            String relative = path.startsWith("/") ? path.substring(1) : path;
            if (relative.startsWith("WEB-INF/classes/")) {
                relative = relative.substring("WEB-INF/classes/".length());
            }
            if (relative.isEmpty()) {
                continue;
            }
            Path target = root.resolve(relative).normalize();
            if (!target.startsWith(root)) {
                continue; // defensive: never materialize outside the deployment root
            }
            Files.createDirectories(target.getParent());
            try (InputStream in = node.getAsset().openStream()) {
                Files.copy(in, target);
            }
            if (relative.startsWith("WEB-INF/lib/") && relative.endsWith(".jar")) {
                libraries.add(target);
            }
        }

        List<URL> urls = new ArrayList<>();
        urls.add(toUrl(root));
        for (Path library : libraries) {
            urls.add(toUrl(library));
        }
        URLClassLoader classLoader = new DeploymentClassLoader(
                "vidocq-deployment[" + archive.getName() + "]",
                urls.toArray(URL[]::new),
                MaterializedDeployment.class.getClassLoader());
        return new MaterializedDeployment(root, List.copyOf(libraries), classLoader);
    }

    /**
     * Deployment class loader with child-first <em>resource</em> lookup. Classes are
     * still loaded parent-first (standard delegation, no risk of duplicate class
     * definitions), but {@code getResource(s)} return the deployment's own resources
     * before the parent's. This gives a deployment-local {@code META-INF/services/*}
     * provider precedence over a runtime provider — the Jakarta EE web-app rule that
     * the Core Profile TCK relies on (a WAR that ships its own {@code JsonProvider} /
     * {@code JsonbProvider} must be the one {@code ServiceLoader} resolves).
     */
    private static final class DeploymentClassLoader extends URLClassLoader {
        DeploymentClassLoader(String name, URL[] urls, ClassLoader parent) {
            super(name, urls, parent);
        }

        @Override
        public URL getResource(String name) {
            String lenient = stripLeadingSlash(name);
            URL local = findResource(lenient);
            return local != null ? local : super.getResource(lenient);
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            name = stripLeadingSlash(name);
            // Deployment resources first, then the parent's, deduplicated.
            List<URL> ordered = new ArrayList<>();
            for (Enumeration<URL> local = findResources(name); local.hasMoreElements(); ) {
                URL url = local.nextElement();
                if (!ordered.contains(url)) {
                    ordered.add(url);
                }
            }
            ClassLoader parent = getParent();
            if (parent != null) {
                for (Enumeration<URL> up = parent.getResources(name); up.hasMoreElements(); ) {
                    URL url = up.nextElement();
                    if (!ordered.contains(url)) {
                        ordered.add(url);
                    }
                }
            }
            return Collections.enumeration(ordered);
        }

        /**
         * Jakarta EE deployment class loaders (servlet-container derived) tolerate a
         * leading slash in resource names; a bare {@link URLClassLoader} does not. TCK
         * deployment code loads resources like {@code getResourceAsStream("/key.pub")},
         * so strip a single leading slash to match the app-server behaviour.
         */
        private static String stripLeadingSlash(String name) {
            return name != null && name.startsWith("/") ? name.substring(1) : name;
        }
    }

    private static URL toUrl(Path path) {
        try {
            return path.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new UncheckedIOException(e);
        }
    }

    ClassLoader classLoader() {
        return classLoader;
    }

    Path root() {
        return root;
    }

    /**
     * Names of every class the deployment contributes as CDI bean classes:
     * classes materialized at the classpath root (originally at the archive root
     * or under {@code WEB-INF/classes/}) plus classes packaged in
     * {@code WEB-INF/lib} jars — CDI treats those libraries as bean archives,
     * and several TCKs ship their beans that way (ShrinkWrap
     * {@code addAsLibrary}). {@code module-info} descriptors are skipped.
     */
    List<String> beanClassNames() throws IOException {
        List<String> names = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.toString().endsWith(".class"))
                    .forEach(p -> {
                        String relative = root.relativize(p).toString().replace(File.separatorChar, '/');
                        if (relative.startsWith("WEB-INF/")) {
                            return; // only normalized root classes; libs are read below
                        }
                        addClassName(names, relative);
                    });
        }
        for (Path library : libraries) {
            try (JarFile jar = new JarFile(library.toFile())) {
                for (Enumeration<JarEntry> entries = jar.entries(); entries.hasMoreElements(); ) {
                    JarEntry entry = entries.nextElement();
                    if (entry.getName().endsWith(".class")) {
                        addClassName(names, entry.getName());
                    }
                }
            }
        }
        return names;
    }

    /**
     * Every {@code microprofile-config.properties} entry the deployment carries —
     * at the materialized root (archive root or {@code WEB-INF/classes/}) or
     * inside a {@code WEB-INF/lib} jar. Later files never override earlier keys
     * (stable order: root files first, then libraries).
     */
    java.util.Map<String, String> microProfileConfig() throws IOException {
        var config = new java.util.LinkedHashMap<String, String>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(p -> p.toString().endsWith("microprofile-config.properties")).toList()) {
                try (InputStream in = Files.newInputStream(file)) {
                    loadProperties(config, in);
                }
            }
        }
        for (Path library : libraries) {
            try (JarFile jar = new JarFile(library.toFile())) {
                for (Enumeration<JarEntry> entries = jar.entries(); entries.hasMoreElements(); ) {
                    JarEntry entry = entries.nextElement();
                    if (entry.getName().endsWith("microprofile-config.properties")) {
                        try (InputStream in = jar.getInputStream(entry)) {
                            loadProperties(config, in);
                        }
                    }
                }
            }
        }
        return config;
    }

    private static void loadProperties(java.util.Map<String, String> config, InputStream in) throws IOException {
        var properties = new java.util.Properties();
        properties.load(in);
        for (String key : properties.stringPropertyNames()) {
            config.putIfAbsent(key, properties.getProperty(key));
        }
    }

    private static void addClassName(List<String> names, String resourcePath) {
        if (resourcePath.contains("module-info")) {
            return;
        }
        names.add(resourcePath.substring(0, resourcePath.length() - ".class".length())
                .replace('/', '.'));
    }

    @Override
    public void close() throws IOException {
        classLoader.close();
        if (Files.exists(root)) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
        }
    }
}
