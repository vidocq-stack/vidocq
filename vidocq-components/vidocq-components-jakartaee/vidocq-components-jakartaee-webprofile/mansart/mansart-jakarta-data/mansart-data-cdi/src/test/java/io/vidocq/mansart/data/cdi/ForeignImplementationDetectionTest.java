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
package io.vidocq.mansart.data.cdi;

import io.vidocq.mansart.data.core.RepositoryRuntime;
import org.junit.jupiter.api.Test;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which class {@link MansartDataExtension} takes for another provider's implementation of a repository (mansart#40):
 * the {@code <Repository>_} of Hibernate Processor's convention, unless it has Mansart's {@code (RepositoryRuntime)}
 * constructor. The classes are built with the Class-File API, so one can name a type that does not exist.
 */
class ForeignImplementationDetectionTest {

    private static final ClassDesc REPO = ClassDesc.of("gen.Repo");

    private static byte[] repository() {
        return ClassFile.of().build(REPO, cb -> cb.withFlags(AccessFlag.PUBLIC, AccessFlag.INTERFACE, AccessFlag.ABSTRACT));
    }

    /** {@code name implements gen.Repo}, with one constructor taking {@code parameter}. */
    private static byte[] implementation(String name, ClassDesc parameter) {
        return ClassFile.of().build(ClassDesc.of(name), cb -> cb.withFlags(AccessFlag.PUBLIC)
                .withInterfaceSymbols(REPO)
                .withMethodBody(ConstantDescs.INIT_NAME, MethodTypeDesc.of(ConstantDescs.CD_void, parameter),
                        ClassFile.ACC_PUBLIC, code -> code.aload(0)
                                .invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
                                .return_()));
    }

    private static final class Loader extends ClassLoader {
        private final Map<String, byte[]> classes;

        Loader(Map<String, byte[]> classes) {
            super(ForeignImplementationDetectionTest.class.getClassLoader());
            this.classes = classes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classes.get(name);
            if (bytes == null) throw new ClassNotFoundException(name);
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    @Test
    void anImplementationWhoseConstructorNeedsAnAbsentProviderIsForeign() throws Exception {
        var loader = new Loader(Map.of("gen.Repo", repository(),
                "gen.Repo_", implementation("gen.Repo_", ClassDesc.of("gen.MissingSession"))));

        assertTrue(MansartDataExtension.isForeignImplementation(loader.loadClass("gen.Repo_"),
                loader.loadClass("gen.Repo")));
    }

    @Test
    void mansartsOwnConstructorIsNotForeign() throws Exception {
        var loader = new Loader(Map.of("gen.Repo", repository(),
                "gen.Repo_", implementation("gen.Repo_", ClassDesc.of(RepositoryRuntime.class.getName()))));

        assertFalse(MansartDataExtension.isForeignImplementation(loader.loadClass("gen.Repo_"),
                loader.loadClass("gen.Repo")));
    }

    @Test
    void aClassOfAnotherNameIsNeverForeign() throws Exception {
        var loader = new Loader(Map.of("gen.Repo", repository(),
                "gen.RepoAdapter", implementation("gen.RepoAdapter", ClassDesc.of("gen.MissingSession"))));

        assertFalse(MansartDataExtension.isForeignImplementation(loader.loadClass("gen.RepoAdapter"),
                loader.loadClass("gen.Repo")));
    }
}
