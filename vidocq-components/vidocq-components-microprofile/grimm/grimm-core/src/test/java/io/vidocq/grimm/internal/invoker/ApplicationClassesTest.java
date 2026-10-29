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
package io.vidocq.grimm.internal.invoker;

import io.vidocq.grimm.internal.config.FilterConfig;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.OASFilter;
import org.eclipse.microprofile.openapi.OASModelReader;
import org.eclipse.microprofile.openapi.models.Constructible;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.junit.jupiter.api.Test;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The model reader and the filter named by {@code mp.openapi.model.reader} and {@code mp.openapi.filter} are classes
 * of the application (MicroProfile OpenAPI §5.1, §5.2). They are loaded the way the application sees them, through the
 * thread context class loader, and not only through Grimm's own loader, which does not see the application's classes
 * when Grimm is provided by the server or sits in a parent module layer.
 */
class ApplicationClassesTest {

    private static final String READER = "app.HiddenReader";
    private static final String FILTER = "app.HiddenFilter";

    @Test
    void theModelReaderIsLoadedThroughTheApplicationLoader() throws Exception {
        OpenAPI model = inApplication(() -> new ModelReaderInvoker().invokeModelReader(new FilterConfig(null, READER)));

        assertNotNull(model);
    }

    @Test
    void theFilterIsLoadedThroughTheApplicationLoader() throws Exception {
        OpenAPI model = OASFactory.createObject(OpenAPI.class);

        assertEquals(model, inApplication(() -> new FilterInvoker().applyFilter(model, new FilterConfig(FILTER, null))));
    }

    @Test
    void aClassNoLoaderSeesIsStillReported() {
        assertThrows(IllegalArgumentException.class,
                () -> new ModelReaderInvoker().invokeModelReader(new FilterConfig(null, READER)));
    }

    private static <T> T inApplication(Callable<T> action) throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(new ApplicationLoader(ApplicationClassesTest.class.getClassLoader()));
        try {
            return action.call();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /** The application's loader: it defines a reader and a filter Grimm's own loader cannot see. */
    private static final class ApplicationLoader extends ClassLoader {

        private final Map<String, byte[]> classes = Map.of(
                READER, implementation(READER, OASModelReader.class, true),
                FILTER, implementation(FILTER, OASFilter.class, false));

        ApplicationLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classes.get(name);
            if (bytes == null) {
                throw new ClassNotFoundException(name);
            }
            return defineClass(name, bytes, 0, bytes.length);
        }

        /** A public class implementing {@code contract}; a reader's {@code buildModel()} returns an empty model. */
        private static byte[] implementation(String name, Class<?> contract, boolean reader) {
            ClassDesc self = ClassDesc.of(name);
            ClassDesc openApi = ClassDesc.of(OpenAPI.class.getName());
            return ClassFile.of().build(self, cb -> {
                cb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER)
                        .withInterfaceSymbols(ClassDesc.of(contract.getName()))
                        .withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC,
                                code -> code.aload(0)
                                        .invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME,
                                                ConstantDescs.MTD_void)
                                        .return_());
                if (reader) {
                    cb.withMethodBody("buildModel", MethodTypeDesc.of(openApi), ClassFile.ACC_PUBLIC,
                            code -> code.ldc(openApi)
                                    .invokestatic(ClassDesc.of(OASFactory.class.getName()), "createObject",
                                            MethodTypeDesc.of(ClassDesc.of(Constructible.class.getName()), ConstantDescs.CD_Class))
                                    .checkcast(openApi)
                                    .areturn());
                }
            });
        }
    }
}
