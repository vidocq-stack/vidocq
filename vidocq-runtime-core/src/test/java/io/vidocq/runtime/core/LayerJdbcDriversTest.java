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
import io.vidocq.vauban.classloader.spi.PluginContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.classfile.attribute.ModuleRequireInfo;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.constant.ModuleDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_void;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** A JDBC driver of the application layer no longer pins the layer after a reload (spec §5, mansart#42 review). */
@DisplayName("LayerJdbcDrivers — the drivers of a closing layer are deregistered")
class LayerJdbcDriversTest {

    private static final ClassDesc DRIVER = ClassDesc.of("java.sql.Driver");
    private static final ClassDesc FAKE = ClassDesc.of("acme.driver.FakeDriver");

    @Test
    @DisplayName("a driver that registered itself from the layer is deregistered once")
    void deregistersTheLayersDrivers(@TempDir Path dir) throws Exception {
        Path module = driverModule(dir);
        var layer = VaubanLayerFactory.createAppLayer(List.of(module), ModuleLayer.boot(),
                ClassLoader.getSystemClassLoader(), PluginContext.empty());
        try {
            Class.forName("acme.driver.FakeDriver", true, layer.loader()); // its <clinit> registers it

            assertEquals(1, LayerJdbcDrivers.deregister(layer));
            assertEquals(0, LayerJdbcDrivers.deregister(layer), "nothing is left to deregister");
        } finally {
            layer.loader().close();
        }
    }

    /** acme.driver: requires java.sql; FakeDriver implements Driver and registers itself in its static initializer. */
    private static Path driverModule(Path dir) throws Exception {
        Path root = dir.resolve("acme.driver");
        Files.createDirectories(root.resolve("acme/driver"));
        Files.write(root.resolve("module-info.class"), ClassFile.of().buildModule(
                ModuleAttribute.of(ModuleDesc.of("acme.driver"), mb -> {
                    mb.requires(ModuleRequireInfo.of(ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null));
                    mb.requires(ModuleRequireInfo.of(ModuleDesc.of("java.sql"), 0, null));
                })));
        Files.write(root.resolve("acme/driver/FakeDriver.class"), ClassFile.of().build(FAKE, cb -> cb
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER)
                .withInterfaceSymbols(DRIVER)
                .withMethodBody("<init>", MethodTypeDesc.of(CD_void), ClassFile.ACC_PUBLIC, b -> b
                        .aload(0).invokespecial(CD_Object, "<init>", MethodTypeDesc.of(CD_void)).return_())
                .withMethodBody("<clinit>", MethodTypeDesc.of(CD_void), ClassFile.ACC_STATIC, b -> b
                        .new_(FAKE).dup().invokespecial(FAKE, "<init>", MethodTypeDesc.of(CD_void))
                        .invokestatic(ClassDesc.of("java.sql.DriverManager"), "registerDriver",
                                MethodTypeDesc.of(CD_void, DRIVER))
                        .return_())));
        return root;
    }
}
