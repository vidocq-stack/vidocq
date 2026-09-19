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
package io.vidocq.runtime.extensions.essentials.migration.liquibase;

import liquibase.resource.AbstractResource;
import liquibase.resource.AbstractResourceAccessor;
import liquibase.resource.Resource;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Serves Liquibase the application's changelogs when Vidocq booted the application in a module layer of its own
 * (vidocq#96). There, the context class loader serves a file by name, but under a {@code vauban:} URL that
 * Liquibase's {@code ClassLoaderResourceAccessor} cannot open ({@code unknown protocol: vauban}), and it lists no
 * directory. A file is read here by name through the loader; a directory, for {@code includeAll}, is listed from
 * the layer's modules.
 */
final class ApplicationResourceAccessor extends AbstractResourceAccessor {

    private final Function<String, List<String>> lister;
    private final ClassLoader loader;

    /**
     * @param lister the files under a resource directory, at any depth, such as
     *               {@code ApplicationLayer.list(layer, directory)}
     * @param loader the loader that serves the application's files by name
     */
    ApplicationResourceAccessor(Function<String, List<String>> lister, ClassLoader loader) {
        this.lister = lister;
        this.loader = loader;
    }

    @Override
    public List<Resource> search(String path, boolean recursive) {
        String directory = name(path).replaceAll("/+$", "");
        int depth = directory.isEmpty() ? 0 : directory.length() + 1;
        List<Resource> found = new ArrayList<>();
        for (String name : lister.apply(directory)) {
            if (recursive || name.indexOf('/', depth) < 0) {
                found.add(new LoaderResource(name, loader));
            }
        }
        return found;
    }

    @Override
    public List<Resource> getAll(String path) {
        String name = name(path);
        return loader.getResource(name) == null ? null : List.of(new LoaderResource(name, loader));
    }

    @Override
    public List<String> describeLocations() {
        return List.of("the application layer, read through " + loader);
    }

    @Override
    public void close() {
        // the loader belongs to the layer, which outlives the migration
    }

    /** {@code path} as a resource name: no {@code classpath:} prefix, no leading slash. */
    private static String name(String path) {
        return path.replace('\\', '/').replaceFirst("^classpath\\*?:", "").replaceFirst("^/+", "");
    }

    /** One file of the application, read by name through the loader. */
    private static final class LoaderResource extends AbstractResource {

        private final ClassLoader loader;

        LoaderResource(String name, ClassLoader loader) {
            super(name, URI.create("vidocq-app:/" + name.replace(" ", "%20")));
            this.loader = loader;
        }

        @Override
        public boolean exists() {
            return loader.getResource(getPath()) != null;
        }

        @Override
        public Resource resolve(String other) {
            return new LoaderResource(resolvePath(other), loader);
        }

        @Override
        public Resource resolveSibling(String other) {
            return new LoaderResource(resolveSiblingPath(other).replaceFirst("^/+", ""), loader);
        }

        @Override
        public InputStream openInputStream() throws IOException {
            InputStream in = loader.getResourceAsStream(getPath());
            if (in == null) {
                throw new FileNotFoundException(getPath());
            }
            return in;
        }
    }
}
