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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
    private final URLClassLoader classLoader;

    private MaterializedDeployment(Path root, URLClassLoader classLoader) {
        this.root = root;
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
        URLClassLoader classLoader = new URLClassLoader(
                "vidocq-deployment[" + archive.getName() + "]",
                urls.toArray(URL[]::new),
                MaterializedDeployment.class.getClassLoader());
        return new MaterializedDeployment(root, classLoader);
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
