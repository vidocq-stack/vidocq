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
package io.vidocq.runtime.it.foyservlet;

import io.vidocq.vauban.core.container.VaubanContainer;
import io.vidocq.vauban.core.container.VaubanContainerBuilder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.classfile.Annotation;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The control of {@link RuntimeGenerationWatch}: a test finds no fallback there only if the watch sees one when it
 * happens. The Vauban processor runs on the test classes of this module too, so the bean here has no source: the test
 * writes its class file, and no build could have generated its client proxy.
 */
class RuntimeGenerationWatchTest {

    private static final String BEAN = "io.vidocq.runtime.it.foyservlet.unprocessed.Unprocessed";
    private static final String ANSWER = "unprocessed";

    @Test
    void aClientProxyTheContainerGeneratesAtRunTimeIsSeen() throws Exception {
        RunTimeOnly loader = new RunTimeOnly(applicationScopedSupplier());
        Class<?> bean = loader.loadClass(BEAN);
        try (RuntimeGenerationWatch watch = RuntimeGenerationWatch.watch();
             VaubanContainer container = new VaubanContainerBuilder().addBeanClass(bean).classLoader(loader)
                     .classDefiner(loader::define).build()) {

            assertEquals(ANSWER, ((Supplier<?>) container.select(bean)).get());

            List<String> warnings = watch.warnings();
            assertEquals(1, warnings.size(), warnings::toString);
            assertTrue(warnings.getFirst().contains(RuntimeGenerationWatch.FALLBACK), warnings::toString);
            assertTrue(warnings.getFirst().contains(BEAN), warnings::toString);
        }
    }

    /** {@code @ApplicationScoped public class Unprocessed implements Supplier<String>}, answering {@link #ANSWER}. */
    private static byte[] applicationScopedSupplier() {
        return ClassFile.of().build(ClassDesc.of(BEAN), type -> type
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER)
                .withInterfaceSymbols(ClassDesc.of(Supplier.class.getName()))
                .with(RuntimeVisibleAnnotationsAttribute.of(
                        Annotation.of(ClassDesc.of("jakarta.enterprise.context.ApplicationScoped"))))
                .withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC, code -> code
                        .aload(0)
                        .invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
                        .return_())
                .withMethodBody("get", MethodTypeDesc.of(ConstantDescs.CD_Object), ClassFile.ACC_PUBLIC, code -> code
                        .ldc(ANSWER)
                        .areturn()));
    }

    /** The loader of the bean, which also serves its class file, as Vauban reads it to index the bean. */
    private static final class RunTimeOnly extends ClassLoader {

        private final byte[] bean;

        RunTimeOnly(byte[] bean) {
            super("run-time-only", RuntimeGenerationWatchTest.class.getClassLoader());
            this.bean = bean;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (!BEAN.equals(name)) {
                throw new ClassNotFoundException(name);
            }
            return defineClass(name, bean, 0, bean.length);
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            return name.equals(BEAN.replace('.', '/') + ".class")
                    ? new ByteArrayInputStream(bean) : super.getResourceAsStream(name);
        }

        /** Defines the client proxy Vauban generates, next to the bean. */
        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
