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
package io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.units;

import jakarta.persistence.PersistenceException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * The keys and the modules of a deployment, read from its files, as at build: {@code vidocq:generate} hands the
 * extensions a loader that serves the files of the application and of its dependencies alone, the application's
 * classes first. The start reads the keys of the running application instead, {@code -D} and the environment
 * included.
 */
final class Deployment implements ModuleRoots {

    /** The files the runtime reads its keys from in the application, in its order: the last one wins. */
    private static final String[] FILES = {"vidocq.properties", "application.properties"};
    private static final String MODULE_INFO = "module-info.class";
    /** What follows the root of a module in the URL of its descriptor, versioned in a multi-release jar. */
    private static final Pattern DESCRIPTOR = Pattern.compile("(META-INF/versions/\\d+/)?module-info\\.class$");

    private final Map<String, String> keys;
    private final Map<String, URL> modules;

    private Deployment(Map<String, String> keys, Map<String, URL> modules) {
        this.keys = keys;
        this.modules = modules;
    }

    static Deployment of(ClassLoader loader) {
        return new Deployment(keys(loader), modules(loader));
    }

    Map<String, String> keys() {
        return keys;
    }

    /** The first module of the deployment: the application's classes come ahead of its dependencies. */
    @Override
    public Optional<URL> application() {
        return modules.values().stream().findFirst();
    }

    @Override
    public Optional<URL> of(String name) {
        return Optional.ofNullable(modules.get(name));
    }

    private static Map<String, String> keys(ClassLoader loader) {
        Properties properties = new Properties();
        for (String file : FILES) {
            try (InputStream in = loader.getResourceAsStream(file)) {
                if (in != null) {
                    properties.load(in);
                }
            } catch (IOException e) {
                throw new PersistenceException("Cannot read the " + file + " of the application", e);
            }
        }
        Map<String, String> keys = new LinkedHashMap<>();
        properties.stringPropertyNames().forEach(name -> keys.put(name, properties.getProperty(name)));
        return keys;
    }

    /**
     * Each named module of the deployment, in the order of the loader, by name. The modules of the runtime image are
     * left out: a loader whose parent is the platform's lists them ahead of the deployment's, and none of them is the
     * application's.
     */
    private static Map<String, URL> modules(ClassLoader loader) {
        Map<String, URL> modules = new LinkedHashMap<>();
        try {
            for (URL descriptor : Collections.list(loader.getResources(MODULE_INFO))) {
                if ("jrt".equals(descriptor.getProtocol())) {
                    continue;
                }
                name(descriptor).ifPresent(name -> root(descriptor).ifPresent(root -> modules.putIfAbsent(name, root)));
            }
        } catch (IOException e) {
            throw new PersistenceException("Cannot list the modules of the application", e);
        }
        return modules;
    }

    private static Optional<String> name(URL descriptor) {
        try (InputStream in = descriptor.openStream()) {
            return ClassFile.of().parse(in.readAllBytes()).findAttribute(Attributes.module())
                    .map(attribute -> attribute.moduleName().name().stringValue());
        } catch (IOException | IllegalArgumentException _) {
            // not a descriptor that can be read: no module there
            return Optional.empty();
        }
    }

    private static Optional<URL> root(URL descriptor) {
        try {
            return Optional.of(URI.create(DESCRIPTOR.matcher(descriptor.toExternalForm()).replaceFirst("")).toURL());
        } catch (MalformedURLException | IllegalArgumentException _) {
            return Optional.empty();
        }
    }
}
