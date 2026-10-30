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
package io.vidocq.runtime.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.MethodSignature;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.attribute.SignatureAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.module.ModuleDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The packages of the application that a runtime module reaches by reflection: the JSON-B binding of a REST
 * resource's return and parameter types, a JPA entity. A package that is neither opened nor exported failed at the
 * first request (BUG-20261030-24); {@code vidocq:check-module-info} now names it at build time.
 */
class ReflectedPackagesTest {

    @TempDir
    Path classes;

    private static final ClassDesc BOOK = ClassDesc.of("com.acme.shop.book.Book");
    private static final ClassDesc AUTHOR = ClassDesc.of("com.acme.shop.people.Author");

    private void write(ClassDesc type, Consumer<ClassBuilder> body) throws IOException {
        byte[] bytes = ClassFile.of().build(type, body);
        Path file = classes.resolve(type.packageName().replace('.', '/'))
                .resolve(type.displayName() + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    private static Consumer<ClassBuilder> annotated(String annotation, Consumer<ClassBuilder> rest) {
        return cb -> {
            cb.with(RuntimeVisibleAnnotationsAttribute.of(Annotation.of(ClassDesc.of(annotation),
                    AnnotationElement.ofString("value", "/x"))));
            rest.accept(cb);
        };
    }

    private static Consumer<ClassBuilder> getter(String name, ClassDesc type) {
        return cb -> cb.withMethod(name, MethodTypeDesc.of(type), ClassFile.ACC_PUBLIC,
                mb -> mb.withCode(code -> code.aconst_null().areturn()));
    }

    /** A Book DTO whose author is an Author in another package, behind a private field and a getter. */
    private void bookAndAuthor() throws IOException {
        write(BOOK, cb -> {
            cb.withField("author", AUTHOR, ClassFile.ACC_PRIVATE);
            getter("getAuthor", AUTHOR).accept(cb);
        });
        write(AUTHOR, cb -> cb.withField("name", ConstantDescs.CD_String, ClassFile.ACC_PRIVATE));
    }

    @Test
    void theTypesARestResourceReturnsAndTheirFieldsNeedTheirPackageOpened() throws IOException {
        bookAndAuthor();
        write(ClassDesc.of("com.acme.shop.rest.BookResource"), annotated("jakarta.ws.rs.Path", cb ->
                cb.withMethod("all", MethodTypeDesc.of(ConstantDescs.CD_List), ClassFile.ACC_PUBLIC, mb -> {
                    mb.with(SignatureAttribute.of(MethodSignature.parseFrom("()Ljava/util/List<Lcom/acme/shop/book/Book;>;")));
                    mb.withCode(code -> code.aconst_null().areturn());
                })));

        Map<String, String> reached = ReflectedPackages.of(classes);

        assertEquals(Set.of("com.acme.shop.book", "com.acme.shop.people"), reached.keySet());
        assertEquals("com.acme.shop.book.Book, reached through com.acme.shop.rest.BookResource",
                reached.get("com.acme.shop.book"));
    }

    @Test
    void aJpaEntityNeedsItsPackageOpened() throws IOException {
        write(ClassDesc.of("com.acme.shop.data.Loan"), annotated("jakarta.persistence.Entity",
                cb -> cb.withField("id", ConstantDescs.CD_long, ClassFile.ACC_PRIVATE)));

        assertEquals(Set.of("com.acme.shop.data"), ReflectedPackages.of(classes).keySet());
    }

    @Test
    void aClassNoResourceOrEntityReachesIsLeftAlone() throws IOException {
        bookAndAuthor();

        assertEquals(Map.of(), ReflectedPackages.of(classes));
    }

    @Test
    void aPackageOpenedOrExportedOrAnOpenModuleIsSatisfied() {
        Map<String, String> reached = Map.of("a.open", "x", "a.exported", "y", "a.qualified", "z", "a.closed", "w");
        ModuleDescriptor descriptor = ModuleDescriptor.newModule("app")
                .opens("a.open").exports("a.exported").opens("a.qualified", Set.of("io.vidocq.champollion.jsonb"))
                .packages(Set.of("a.closed"))
                .build();

        assertEquals(List.of("a.closed"), ReflectedPackages.missing(descriptor, reached));
        assertEquals(List.of(), ReflectedPackages.missing(
                ModuleDescriptor.newOpenModule("app").packages(Set.of("a.closed")).build(), reached));
    }
}
