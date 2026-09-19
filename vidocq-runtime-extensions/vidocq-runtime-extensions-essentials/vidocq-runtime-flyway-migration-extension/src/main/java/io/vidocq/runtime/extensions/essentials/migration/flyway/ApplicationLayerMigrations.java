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
package io.vidocq.runtime.extensions.essentials.migration.flyway;

import org.flywaydb.core.api.ClassProvider;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.Location;
import org.flywaydb.core.api.ResourceProvider;
import org.flywaydb.core.api.migration.JavaMigration;
import org.flywaydb.core.api.resource.LoadableResource;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * The migrations of {@code classpath:} locations, found in the application's own modules rather than by
 * Flyway's class-path scanner, for a launch where the application sits in a module layer of its own
 * (vidocq#96). There, the context class loader serves a script by name but lists no directory, and
 * Flyway's scanner, which asks {@code getResources("db/migration")} first, finds nothing.
 *
 * <p>Each location is listed by {@code lister}, from the layer's modules; each file is read, and each class
 * loaded, by name through {@code loader}. A resource keeps the name Flyway's own scanner gives it, relative
 * to its location, so a schema history written by either stays valid for the other.
 */
final class ApplicationLayerMigrations implements ResourceProvider, ClassProvider<JavaMigration> {

    private static final System.Logger LOG = System.getLogger(ApplicationLayerMigrations.class.getName());

    private final List<LoadableResource> resources = new ArrayList<>();
    private final Map<String, LoadableResource> byRelativePath = new HashMap<>();
    private final List<Class<? extends JavaMigration>> classes = new ArrayList<>();

    /**
     * @param locations              Flyway's locations, every one {@code classpath:}
     * @param lister                 the files under a resource directory, at any depth, such as
     *                               {@code ApplicationLayer.list(layer, directory)}
     * @param loader                 the loader that serves the application's files and classes by name
     * @param encoding               the encoding of the scripts
     * @param failOnMissingLocations whether a location with no file fails, as Flyway's own option does
     */
    ApplicationLayerMigrations(Location[] locations, Function<String, List<String>> lister, ClassLoader loader,
                               Charset encoding, boolean failOnMissingLocations) {
        for (Location location : locations) {
            List<String> names = lister.apply(location.getRootPath()).stream()
                    .filter(location::matchesPath)
                    .toList();
            if (names.isEmpty() && failOnMissingLocations) {
                throw new FlywayException("Unable to resolve location " + location + ".");
            }
            for (String name : names) {
                LayerResource resource = new LayerResource(name, location.getPathRelativeToThis(name), loader, encoding);
                resources.add(resource);
                byRelativePath.put(resource.getRelativePath().toLowerCase(Locale.ROOT), resource);
                if (name.endsWith(".class")) {
                    javaMigration(name, loader);
                }
            }
        }
    }

    @Override
    public LoadableResource getResource(String name) {
        return byRelativePath.get(name.toLowerCase(Locale.ROOT));
    }

    /** The resources whose file name starts with {@code prefix} and ends with one of {@code suffixes}. */
    @Override
    public Collection<LoadableResource> getResources(String prefix, String[] suffixes) {
        List<LoadableResource> found = new ArrayList<>();
        for (LoadableResource resource : resources) {
            if (matches(resource.getFilename(), prefix, suffixes)) {
                found.add(resource);
            }
        }
        return found;
    }

    @Override
    public Collection<Class<? extends JavaMigration>> getClasses() {
        return List.copyOf(classes);
    }

    /** Flyway's own rule: a non-empty prefix, then one of the suffixes, ignoring case, with something between. */
    static boolean matches(String fileName, String prefix, String[] suffixes) {
        boolean prefixed = prefix != null && !prefix.isEmpty();
        if (prefixed && !fileName.startsWith(prefix)) {
            return false;
        }
        for (String suffix : suffixes) {
            if (fileName.toUpperCase(Locale.ROOT).endsWith(suffix.toUpperCase(Locale.ROOT))
                    && fileName.length() > (prefixed ? prefix.length() : 0) + suffix.length()) {
                return true;
            }
        }
        return false;
    }

    /** Keeps the class of {@code name} when it is a concrete {@link JavaMigration}, as Flyway's scanner does. */
    private void javaMigration(String name, ClassLoader loader) {
        String className = name.substring(0, name.length() - ".class".length()).replace('/', '.');
        try {
            Class<?> type = Class.forName(className, false, loader);
            if (JavaMigration.class.isAssignableFrom(type) && !Modifier.isAbstract(type.getModifiers())
                    && !type.isEnum() && !type.isAnonymousClass()) {
                classes.add(type.asSubclass(JavaMigration.class));
            }
        } catch (ClassNotFoundException | LinkageError e) {
            LOG.log(System.Logger.Level.WARNING, "Skipping " + className + ": " + e);
        }
    }

    /** One file of the application, read by name through the loader. */
    private static final class LayerResource extends LoadableResource {

        private final String name;
        private final String relativePath;
        private final ClassLoader loader;
        private final Charset encoding;

        LayerResource(String name, String relativePath, ClassLoader loader, Charset encoding) {
            this.name = name;
            this.relativePath = relativePath;
            this.loader = loader;
            this.encoding = encoding;
        }

        @Override
        public Reader read() {
            InputStream in = loader.getResourceAsStream(name);
            if (in == null) {
                throw new FlywayException("Unable to obtain inputstream for resource: " + name);
            }
            return new InputStreamReader(in, encoding.newDecoder());
        }

        @Override
        public String getAbsolutePath() {
            return name;
        }

        @Override
        public String getAbsolutePathOnDisk() {
            return name;
        }

        @Override
        public String getFilename() {
            return name.substring(name.lastIndexOf('/') + 1);
        }

        @Override
        public String getRelativePath() {
            return relativePath;
        }
    }
}
