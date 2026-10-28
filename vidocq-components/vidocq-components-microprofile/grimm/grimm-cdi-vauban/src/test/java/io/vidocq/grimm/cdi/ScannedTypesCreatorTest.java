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
package io.vidocq.grimm.cdi;

import io.vidocq.grimm.spi.gen.OpenApiContribution;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ScannedTypesCreator} (grimm#22): the scanned types are the classes the extension
 * recorded plus the resources described by the {@code $$GrimmModel} companions that the
 * application's class loader lists as {@link OpenApiContribution} services — Grimm's own
 * classes excepted.
 */
class ScannedTypesCreatorTest {

    private static final String SERVICES = "META-INF/services/" + OpenApiContribution.class.getName();

    @TempDir
    Path tempDir;

    /** Parameters with no recorded class names. */
    private static final Parameters NO_CLASSES = new Parameters() {
        @Override
        public <T> T get(String key, Class<T> type) {
            return null;
        }

        @Override
        public <T> T get(String key, Class<T> type, T defaultValue) {
            return defaultValue;
        }
    };

    private static ScannedTypes createWith(ClassLoader contextLoader) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(contextLoader);
        try {
            return new ScannedTypesCreator().create(null, NO_CLASSES);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /**
     * Writes one contribution class for the unnamed module, as an application jar would ship it:
     * {@code resourceClass()} returns {@code resourceClass}.
     */
    private void writeContribution(String simpleName, Class<?> resourceClass) throws Exception {
        byte[] bytes = ClassFile.of().build(ClassDesc.of("app." + simpleName), cb -> cb
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL)
                .withSuperclass(ConstantDescs.CD_Object)
                .withInterfaceSymbols(ClassDesc.of(OpenApiContribution.class.getName()))
                .withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC,
                        code -> code.aload(0)
                                .invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
                                .return_())
                .withMethodBody("resourceClass", MethodTypeDesc.of(ConstantDescs.CD_Class), ClassFile.ACC_PUBLIC,
                        code -> code.ldc(ClassDesc.of(resourceClass.getName())).areturn())
                .withMethodBody("openApiJson", MethodTypeDesc.of(ConstantDescs.CD_String), ClassFile.ACC_PUBLIC,
                        code -> code.ldc("{}").areturn()));
        Path file = tempDir.resolve("classes/app/" + simpleName + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    private URLClassLoader loaderListing(String... providers) throws Exception {
        Path services = tempDir.resolve("classes").resolve(SERVICES);
        Files.createDirectories(services.getParent());
        Files.writeString(services, String.join("\n", providers) + "\n");
        return new URLClassLoader(new java.net.URL[] {tempDir.resolve("classes").toUri().toURL()},
                getClass().getClassLoader());
    }

    @Test
    void contributionsOfTheApplicationLoaderAddTheirResources() throws Exception {
        writeContribution("UuidContribution", UUID.class);
        writeContribution("OwnContribution", OpenApiResource.class);

        try (URLClassLoader loader = loaderListing("app.UuidContribution", "app.OwnContribution")) {
            ScannedTypes types = createWith(loader);

            assertTrue(types.classes().contains(UUID.class));
            assertFalse(types.classes().contains(OpenApiResource.class), "grimm's own classes stay out");
        }
    }

    @Test
    void unreadableServicesAreIgnored() throws Exception {
        try (URLClassLoader loader = loaderListing("app.DoesNotExist")) {
            ScannedTypes types = createWith(loader);

            assertTrue(types.classes().isEmpty());
        }
    }

    @Test
    void withoutContextLoaderTheCreatorsOwnLoaderIsUsed() {
        ScannedTypes types = createWith(null);

        assertFalse(types.classes().contains(UUID.class));
    }
}
