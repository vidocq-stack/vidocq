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

package io.vidocq.runtime.core;

import io.vidocq.vauban.classloader.VaubanLayerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.constant.ClassDesc;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deregisters the JDBC drivers a closing application layer defined, so that {@code DriverManager} does not keep the
 * old layer after a dev reload (spec 2026-10-13-application-layer-dependencies §5).
 */
final class LayerJdbcDrivers {

    private static final String HELPER = "JdbcLeakPrevention.class";
    private static final AtomicLong HELPER_COUNT = new AtomicLong();

    private LayerJdbcDrivers() {}

    /** The number of drivers deregistered; 0 when the boot layer has no java.sql, so no driver can be loaded. */
    static int deregister(VaubanLayerFactory.AppLayer layer) throws ReflectiveOperationException, IOException {
        var javaSql = ModuleLayer.boot().findModule("java.sql");
        if (javaSql.isEmpty()) {
            return 0;
        }
        // Only a module that reads java.sql can define a Driver: nothing to do (and nothing defined) otherwise.
        var readers = layer.layer().modules().stream().filter(m -> m.canRead(javaSql.get())).toList();
        if (readers.isEmpty()) {
            return 0;
        }
        Class<?> host = anyClassOf(layer, readers);
        if (host == null) {
            return 0;
        }
        Module module = host.getModule();
        Module core = LayerJdbcDrivers.class.getModule();
        layer.controller().addReads(module, javaSql.get());
        layer.controller().addOpens(module, host.getPackageName(), core);
        core.addReads(module);
        var lookup = MethodHandles.privateLookupIn(host, MethodHandles.lookup());
        Class<?> helper = lookup.defineClass(renamed(host.getPackageName()));
        var deregister = lookup.findStatic(helper, "deregister", MethodType.methodType(int.class, ClassLoader.class));
        try {
            return (int) deregister.invoke(layer.loader());
        } catch (Throwable t) {
            throw new IllegalStateException("Deregistering the JDBC drivers of the old layer failed", t);
        }
    }

    private static byte[] renamed(String pkg) throws IOException {
        try (InputStream in = LayerJdbcDrivers.class.getResourceAsStream(HELPER)) {
            if (in == null) {
                throw new IOException(HELPER + " is missing from the core");
            }
            var model = ClassFile.of().parse(in.readAllBytes());
            // A fresh name per call: a second definition in the same package would fail with a duplicate class.
            return ClassFile.of().transformClass(model,
                    ClassDesc.of(pkg + ".VidocqJdbcLeakPrevention" + HELPER_COUNT.incrementAndGet()), ClassTransform.ACCEPT_ALL);
        }
    }

    /** A class of a module that reads java.sql, loaded without initialization, to host the helper's package; null when none loads. */
    private static Class<?> anyClassOf(VaubanLayerFactory.AppLayer layer, List<Module> readers) {
        for (String name : layer.loader().managedClassNames()) {
            int dot = name.lastIndexOf('.');
            if (dot < 0 || name.endsWith("module-info")) {
                continue;
            }
            String pkg = name.substring(0, dot);
            for (Module m : readers) {
                if (m.getPackages().contains(pkg)) {
                    try {
                        Class<?> c = Class.forName(m, name);
                        if (c != null) {
                            return c;
                        }
                    } catch (LinkageError _) {
                        // an optional dependency of this class is absent: try another class of the layer
                    }
                }
            }
        }
        return null;
    }
}
