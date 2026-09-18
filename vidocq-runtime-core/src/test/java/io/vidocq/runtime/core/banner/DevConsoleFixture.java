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
package io.vidocq.runtime.core.banner;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A stand-in for the dev console's extension class, which the core looks for by name: an empty public class of
 * that name, written with the class-file API into a class output of its own, since the console is no dependency
 * of the core.
 */
public final class DevConsoleFixture {

    /** The binary name the core looks for. */
    public static final String CONSOLE_CLASS = "io.vidocq.runtime.extensions.essentials.devconsole.DevConsoleExtension";

    private DevConsoleFixture() {}

    /**
     * A loader that sees the stand-in console class, written into {@code root}, the test's loader as its parent.
     *
     * @param root a directory of classes
     */
    public static URLClassLoader withConsole(Path root) throws IOException {
        Path file = root.resolve(CONSOLE_CLASS.replace('.', '/') + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, ClassFile.of().build(ClassDesc.of(CONSOLE_CLASS), type -> type
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER)
                .withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC, code -> code
                        .aload(0)
                        .invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
                        .return_())));
        return new URLClassLoader("devconsole", new URL[] {root.toUri().toURL()},
                DevConsoleFixture.class.getClassLoader());
    }
}
