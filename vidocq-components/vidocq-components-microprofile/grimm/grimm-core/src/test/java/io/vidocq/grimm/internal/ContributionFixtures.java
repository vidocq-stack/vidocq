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
package io.vidocq.grimm.internal;

import io.vidocq.grimm.spi.gen.OpenApiContribution;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Test harness: emits, with the Class-File API (JEP 484), a resource class and its
 * {@code $$GrimmModel} companion the way grimm-processor names them, in a dedicated loader.
 */
final class ContributionFixtures extends ClassLoader {

    private static final String SUFFIX = "$$GrimmModel";
    private static final ClassDesc CONTRIBUTION = ClassDesc.of(OpenApiContribution.class.getName());
    private static final String SERVICES = "META-INF/services/" + OpenApiContribution.class.getName();

    private URL servicesFile;

    ContributionFixtures() {
        super(ContributionFixtures.class.getClassLoader());
    }

    /**
     * Publishes {@code providerNames} as {@code META-INF/services} providers of
     * {@link OpenApiContribution} visible through this loader only.
     */
    void registerProviders(Path directory, String... providerNames) {
        try {
            Path file = Files.writeString(directory.resolve("providers"), String.join("\n", providerNames));
            servicesFile = file.toUri().toURL();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    protected Enumeration<URL> findResources(String name) throws IOException {
        if (servicesFile != null && SERVICES.equals(name)) {
            return Collections.enumeration(List.of(servicesFile));
        }
        return super.findResources(name);
    }

    /**
     * Defines {@code resourceName} and a companion returning {@code target} (the resource itself
     * when {@code null}) and {@code json}.
     */
    Class<?> resourceWithCompanion(String resourceName, Class<?> target, String json) {
        Class<?> resource = define(resourceName, emptyClass(ClassDesc.of(resourceName)));
        ClassDesc targetDesc = target == null ? ClassDesc.of(resourceName) : ClassDesc.of(target.getName());
        define(resourceName + SUFFIX, ClassFile.of().build(ClassDesc.of(resourceName + SUFFIX), cb -> {
            publicClassWithConstructor(cb);
            cb.withInterfaceSymbols(CONTRIBUTION);
            cb.withMethodBody("resourceClass", MethodTypeDesc.of(ConstantDescs.CD_Class), ClassFile.ACC_PUBLIC,
                    code -> code.ldc(targetDesc).areturn());
            cb.withMethodBody("openApiJson", MethodTypeDesc.of(ConstantDescs.CD_String), ClassFile.ACC_PUBLIC,
                    code -> code.ldc(json).areturn());
        }));
        return resource;
    }

    /** Defines {@code resourceName} and a companion that does not implement the contribution SPI. */
    Class<?> resourceWithBrokenCompanion(String resourceName) {
        Class<?> resource = define(resourceName, emptyClass(ClassDesc.of(resourceName)));
        define(resourceName + SUFFIX, emptyClass(ClassDesc.of(resourceName + SUFFIX)));
        return resource;
    }

    private Class<?> define(String name, byte[] bytes) {
        return defineClass(name, bytes, 0, bytes.length);
    }

    private static byte[] emptyClass(ClassDesc desc) {
        return ClassFile.of().build(desc, ContributionFixtures::publicClassWithConstructor);
    }

    private static void publicClassWithConstructor(ClassBuilder cb) {
        cb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
        cb.withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC,
                code -> code.aload(0)
                        .invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
                        .return_());
    }
}
