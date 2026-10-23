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

package io.vidocq.bodoni.core.testing;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.concurrent.Callable;

/**
 * Class-loader helpers for the split-loader tests: a server lib loader that holds Bodoni and
 * a web application loader below it that holds the JDBC driver.
 */
public final class Loaders {

    private Loaders() {
    }

    /**
     * Runs {@code action} with {@code loader} as the context class loader of the current
     * thread, then restores the previous one.
     */
    public static <T> T withContextLoader(ClassLoader loader, Callable<T> action) throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            return action.call();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /** The code source of {@code type}: its class directory or its jar. */
    public static URL location(Class<?> type) {
        return type.getProtectionDomain().getCodeSource().getLocation();
    }

    /**
     * A class loader named {@code name} that defines every class found in {@code urls} itself
     * and asks {@code parent} for the others (child first). Parent first would not isolate:
     * the platform class loader hands a class of a boot-layer module package (such as
     * {@code io.vidocq.bodoni.core.*}, patched into the test run) to the application class
     * loader, so the copy in {@code urls} would never be defined.
     */
    public static URLClassLoader isolated(String name, ClassLoader parent, URL... urls) {
        return new ChildFirstLoader(name, urls, parent);
    }

    private static final class ChildFirstLoader extends URLClassLoader {

        ChildFirstLoader(String name, URL[] urls, ClassLoader parent) {
            super(name, urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            Class<?> type = findLoadedClass(name);
            if (type == null) {
                try {
                    type = findClass(name);
                } catch (ClassNotFoundException _) {
                    return super.loadClass(name, resolve);
                }
            }
            if (resolve) {
                resolveClass(type);
            }
            return type;
        }
    }
}
