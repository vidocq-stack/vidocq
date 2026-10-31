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
package io.vidocq.runtime.tooling;

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
        return cb -> cb.withMethodBody(name, MethodTypeDesc.of(type), ClassFile.ACC_PUBLIC,
                code -> code.aconst_null().areturn());
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

        Map<String, String> reached = ReflectedPackages.of(classes).reasons();

        assertEquals(Set.of("com.acme.shop.book", "com.acme.shop.people"), reached.keySet());
        assertEquals("com.acme.shop.book.Book, reached through com.acme.shop.rest.BookResource",
                reached.get("com.acme.shop.book"));
    }

    @Test
    void aJpaEntityNeedsItsPackageOpened() throws IOException {
        write(ClassDesc.of("com.acme.shop.data.Loan"), annotated("jakarta.persistence.Entity",
                cb -> cb.withField("id", ConstantDescs.CD_long, ClassFile.ACC_PRIVATE)));

        assertEquals(Set.of("com.acme.shop.data"), ReflectedPackages.of(classes).reasons().keySet());
    }

    @Test
    void aClassNoResourceOrEntityReachesIsLeftAlone() throws IOException {
        bookAndAuthor();

        assertEquals(Map.of(), ReflectedPackages.of(classes).reasons());
    }

    @Test
    void aPackageOpenedOrExportedOrAnOpenModuleIsSatisfied() {
        var reached = new ReflectedPackages.Reached(
                Map.of("a.open", "x", "a.exported", "y", "a.qualified", "z", "a.closed", "w"), Set.of());
        ModuleDescriptor descriptor = ModuleDescriptor.newModule("app")
                .opens("a.open").exports("a.exported").opens("a.qualified", Set.of("io.vidocq.champollion.jsonb"))
                .packages(Set.of("a.closed"))
                .build();

        assertEquals(List.of("a.closed"), ReflectedPackages.missing(descriptor, reached));
        assertEquals(List.of(), ReflectedPackages.missing(
                ModuleDescriptor.newOpenModule("app").packages(Set.of("a.closed")).build(), reached));
    }

    /** An interceptor with one {@code @AroundInvoke} method of the given access flags. */
    private void interceptor(String name, int methodFlags) throws IOException {
        write(ClassDesc.of(name), cb -> {
            cb.withFlags(ClassFile.ACC_PUBLIC);
            cb.with(RuntimeVisibleAnnotationsAttribute.of(Annotation.of(ClassDesc.of("jakarta.interceptor.Interceptor"))));
            cb.withMethod("audit", MethodTypeDesc.of(ConstantDescs.CD_Object,
                    ClassDesc.of("jakarta.interceptor.InvocationContext")), methodFlags, mb -> {
                mb.with(RuntimeVisibleAnnotationsAttribute.of(
                        Annotation.of(ClassDesc.of("jakarta.interceptor.AroundInvoke"))));
                mb.withCode(code -> code.aconst_null().areturn());
            });
        });
    }

    @Test
    void aPrivateInterceptorMethodNeedsItsPackageOpenedNotOnlyExported() throws IOException {
        interceptor("com.acme.shop.support.AuditedInterceptor", ClassFile.ACC_PRIVATE);

        var reached = ReflectedPackages.of(classes);

        assertEquals(Map.of("com.acme.shop.support",
                        "com.acme.shop.support.AuditedInterceptor, a CDI interceptor with a private method its container "
                                + "calls by reflection"),
                reached.reasons());
        assertEquals(Set.of("com.acme.shop.support"), reached.needsOpens());
        assertEquals(List.of("com.acme.shop.support"), ReflectedPackages.missing(
                ModuleDescriptor.newModule("app").exports("com.acme.shop.support").build(), reached));
    }

    /** Vauban calls a package-private interceptor method through the generated provider of its package (VAU-INT-011). */
    @Test
    void aPackagePrivateInterceptorMethodNeedsNoDirective() throws IOException {
        interceptor("com.acme.shop.support.AuditedInterceptor", 0);

        var reached = ReflectedPackages.of(classes);

        assertEquals(Map.of(), reached.reasons());
        assertEquals(List.of(), ReflectedPackages.missing(
                ModuleDescriptor.newModule("app").packages(Set.of("com.acme.shop.support")).build(), reached));
    }

    @Test
    void aPublicInterceptorMethodNeedsNoDirective() throws IOException {
        interceptor("com.acme.shop.support.AuditedInterceptor", ClassFile.ACC_PUBLIC);

        var reached = ReflectedPackages.of(classes);

        assertEquals(Map.of(), reached.reasons());
        assertEquals(Set.of(), reached.needsOpens());
    }
}
