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
package io.vidocq.runtime.extensions.jakartaee.web.foy;

import io.vidocq.foy.chappe.FoyChappeBoot;
import io.vidocq.runtime.extensions.jakartaee.web.foy.fixture.RecordingInitializer;
import jakarta.servlet.ServletContainerInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The roots the runtime names as the application's own reach Foy: a {@code @HandlesTypes} initializer is handed
 * their classes even when they hold no {@code web.xml} and no annotated component, as a war Vidocq did not build
 * (Servlet 6.1 §8.2.4, BUG-20261118-60).
 */
class ApplicationRootsTest {

    /** The class of the application, written in its root only: neither the test nor the initializer sees it. */
    private static final String HANDLED = "app.Handled";

    @Test
    void anInitializerIsHandedTheClassesOfAnApplicationRootWithoutWebXml(@TempDir Path application,
                                                                         @TempDir Path library) throws Exception {
        writeHandledClass(application);
        Path services = library.resolve("META-INF/services/" + ServletContainerInitializer.class.getName());
        Files.createDirectories(services.getParent());
        Files.writeString(services, RecordingInitializer.class.getName());
        RecordingInitializer.RECEIVED.set(Set.of());
        URL applicationRoot = application.toUri().toURL();
        ClassLoader loader = new URLClassLoader(new URL[]{applicationRoot, library.toUri().toURL()},
                ApplicationRootsTest.class.getClassLoader());

        FoyChappeBoot.Mounted mounted = FoyDeployments.deploy(null, loader, null, 1800, "UTF-8",
                FoyExtensionTest.config(Map.of()), new FoyDeployments.Sources(List.of(), List.of(applicationRoot)))
                .orElseThrow();

        try {
            List<String> received = RecordingInitializer.RECEIVED.get().stream().map(Class::getName).toList();
            assertTrue(received.contains(HANDLED), "the initializer got " + received);
        } finally {
            mounted.close();
        }
    }

    /** {@code public class app.Handled implements Runnable}, with a no-argument constructor and an empty run. */
    private static void writeHandledClass(Path root) throws Exception {
        byte[] bytes = ClassFile.of().build(ClassDesc.of(HANDLED), type -> type
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER)
                .withInterfaceSymbols(ClassDesc.of(Runnable.class.getName()))
                .withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC, code -> code
                        .aload(0)
                        .invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
                        .return_())
                .withMethodBody("run", ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC, CodeBuilder::return_));
        Path file = root.resolve("app/Handled.class");
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }
}
