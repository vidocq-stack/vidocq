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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The packages of the application that a runtime module reaches by reflection: the JSON-B binding of a REST
 * resource's return and parameter types, an entity (Jakarta Data). A package that is neither opened nor exported failed at the
 * first request (BUG-20261030-24); {@code vidocq:check-module-info} now names it at build time.
 */
class ReflectedPackagesTest {

    @TempDir
    Path classes;

    private static final ClassDesc BOOK = ClassDesc.of("com.acme.shop.book.Book");
    private static final ClassDesc AUTHOR = ClassDesc.of("com.acme.shop.people.Author");

    // --- referencedPackages ---

    @Test
    void aSuperclassAndAnInterfaceAreReferenced() throws IOException {
        write(ClassDesc.of("io.app", "Child"), cb -> cb
                .withSuperclass(ClassDesc.of("java.util", "AbstractMap"))
                .withInterfaceSymbols(ClassDesc.of("java.io", "Serializable")));

        Set<String> packages = ReflectedPackages.referencedPackages(classes);

        assertTrue(packages.contains("java.util"), "the superclass's package: " + packages);
        assertTrue(packages.contains("java.io"), "the interface's package: " + packages);
    }

    @Test
    void aFieldTypeIsReferencedEvenWhenNothingElseMentionsIt() throws IOException {
        write(ClassDesc.of("io.app", "Holder"), cb -> cb
                .withField("stamp", ClassDesc.of("java.time", "Instant"), ClassFile.ACC_PRIVATE));

        assertTrue(ReflectedPackages.referencedPackages(classes).contains("java.time"));
    }

    @Test
    void methodParameterAndReturnTypesAreReferenced() throws IOException {
        write(ClassDesc.of("io.app", "Service"), cb -> cb
                .withMethod("convert",
                        MethodTypeDesc.of(ClassDesc.of("java.net", "URI"), ClassDesc.of("java.sql", "Date")),
                        ClassFile.ACC_ABSTRACT, mb -> {}));

        Set<String> packages = ReflectedPackages.referencedPackages(classes);

        assertTrue(packages.contains("java.net"), "the return type: " + packages);
        assertTrue(packages.contains("java.sql"), "the parameter type: " + packages);
    }

    @Test
    void anArrayTypeIsReportedAsItsComponentPackage() throws IOException {
        write(ClassDesc.of("io.app", "Arrays"), cb -> cb
                .withField("stamps", ClassDesc.of("java.time", "Instant").arrayType(), ClassFile.ACC_PRIVATE));

        Set<String> packages = ReflectedPackages.referencedPackages(classes);

        assertTrue(packages.contains("java.time"), "array descriptors unwrap: " + packages);
        assertTrue(packages.stream().noneMatch(p -> p.startsWith("[")), "no raw descriptor leaks: " + packages);
    }

    @Test
    void primitivesAndTheDefaultPackageContributeNothing() throws IOException {
        write(ClassDesc.of("io.app", "Primitives"), cb -> cb
                .withField("count", ConstantDescs.CD_int, ClassFile.ACC_PRIVATE)
                .withField("flag", ConstantDescs.CD_boolean, ClassFile.ACC_PRIVATE));

        Set<String> packages = ReflectedPackages.referencedPackages(classes);

        assertTrue(packages.stream().allMatch(p -> p.contains(".")), "no unqualified entries: " + packages);
    }

    @Test
    void anOutputDirectoryWithoutClassFilesReferencesNothing() throws IOException {
        assertEquals(Set.of(), ReflectedPackages.referencedPackages(classes));
    }

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

    /** A generated JSON-B binding, as champollion-codegen-apt writes it, listed in the services file. */
    private void generatedBinding(ClassDesc type) throws IOException {
        ClassDesc binding = ClassDesc.of(type.packageName(), type.displayName() + "$$Binding");
        write(binding, cb -> cb.withInterfaceSymbols(ClassDesc.of("io.vidocq.champollion.jsonb.spi.JsonbBinding"))
                .with(SignatureAttribute.of(java.lang.classfile.ClassSignature.of(
                        java.lang.classfile.Signature.ClassTypeSig.of(ConstantDescs.CD_Object),
                        java.lang.classfile.Signature.ClassTypeSig.of(
                                ClassDesc.of("io.vidocq.champollion.jsonb.spi.JsonbBinding"),
                                java.lang.classfile.Signature.TypeArg.of(java.lang.classfile.Signature.ClassTypeSig.of(type)))))));
        Path services = classes.resolve("META-INF/services/io.vidocq.champollion.jsonb.spi.JsonbBinding");
        Files.createDirectories(services.getParent());
        Files.writeString(services, (Files.exists(services) ? Files.readString(services) : "")
                + type.packageName() + "." + type.displayName() + "$$Binding\n");
    }

    @Test
    void aTypeWithAGeneratedJsonbBindingNeedsNoDirective() throws IOException {
        bookAndAuthor();
        generatedBinding(BOOK);
        write(ClassDesc.of("com.acme.shop.rest.BookResource"), annotated("jakarta.ws.rs.Path", cb ->
                cb.withMethod("all", MethodTypeDesc.of(ConstantDescs.CD_List), ClassFile.ACC_PUBLIC, mb -> {
                    mb.with(SignatureAttribute.of(MethodSignature.parseFrom("()Ljava/util/List<Lcom/acme/shop/book/Book;>;")));
                    mb.withCode(code -> code.aconst_null().areturn());
                })));

        // Book has its binding, and so does everything it holds (the processor generates one only then): neither
        // its package nor its author's is reached by reflection.
        assertEquals(Map.of(), ReflectedPackages.of(classes).reasons());
    }

    /**
     * JSON-B maps a value by its declared type: a subclass of a bound type, even without a binding of its own, is
     * written by the declared type's binding, so its package is not asked for.
     */
    @Test
    void aSubclassOfABoundTypeNeedsNoDirective() throws IOException {
        bookAndAuthor();
        generatedBinding(BOOK);
        write(ClassDesc.of("com.acme.shop.special.SpecialBook"), cb -> cb.withSuperclass(BOOK)
                .withField("badge", ConstantDescs.CD_String, ClassFile.ACC_PRIVATE));
        write(ClassDesc.of("com.acme.shop.rest.BookResource"), annotated("jakarta.ws.rs.Path", cb ->
                cb.withMethodBody("one", MethodTypeDesc.of(BOOK), ClassFile.ACC_PUBLIC,
                        code -> code.aconst_null().areturn())));

        assertEquals(Map.of(), ReflectedPackages.of(classes).reasons());
    }

    @Test
    void anEntityWithABindingStillNeedsItsPackageOpenedForJakartaData() throws IOException {
        write(BOOK, annotated("jakarta.persistence.Entity", cb -> { }));
        generatedBinding(BOOK);

        assertEquals(Set.of("com.acme.shop.book"), ReflectedPackages.of(classes).reasons().keySet());
    }

    @Test
    void anEntityMansartHasNoGeneratedModelForNeedsItsPackageOpened() throws IOException {
        write(ClassDesc.of("com.acme.shop.data.Loan"), annotated("jakarta.persistence.Entity",
                cb -> cb.withField("id", ConstantDescs.CD_long, ClassFile.ACC_PRIVATE)));

        Map<String, String> reached = ReflectedPackages.of(classes).reasons();
        assertEquals(Set.of("com.acme.shop.data"), reached.keySet());
        assertEquals("com.acme.shop.data.Loan, an entity (Jakarta Data) without a model generated at build time",
                reached.get("com.acme.shop.data"));
    }

    /**
     * The {@code _MansartComponents} Mansart's processor generates in a package of entities, listed in the services
     * file: it hands Mansart the entity models by plain calls (MANSART-038).
     */
    private void mansartComponents(ClassDesc... entities) throws IOException {
        String pkg = entities[0].packageName();
        ClassDesc components = ClassDesc.of(pkg, "_MansartComponents");
        write(components, cb -> {
            cb.withInterfaceSymbols(ClassDesc.of("io.vidocq.mansart.data.core.GeneratedComponents"));
            cb.withMethodBody("model", MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_Class),
                    ClassFile.ACC_PUBLIC, code -> {
                        for (ClassDesc entity : entities) {
                            code.ldc(entity).pop();
                        }
                        code.aconst_null().areturn();
                    });
        });
        Path services = classes.resolve("META-INF/services/io.vidocq.mansart.data.core.GeneratedComponents");
        Files.createDirectories(services.getParent());
        Files.writeString(services, pkg + "._MansartComponents\n");
    }

    @Test
    void anEntityWithAModelMansartGeneratedNeedsNoDirective() throws IOException {
        ClassDesc loan = ClassDesc.of("com.acme.shop.data.Loan");
        write(loan, annotated("jakarta.persistence.Entity",
                cb -> cb.withField("id", ConstantDescs.CD_long, ClassFile.ACC_PRIVATE)));
        mansartComponents(loan);

        assertEquals(Map.of(), ReflectedPackages.of(classes).reasons());
    }

    /** Covered for Jakarta Data, the entity is still bound by JSON-B by reflection when a REST resource returns it. */
    @Test
    void anEntityWithAGeneratedModelAResourceReturnsStillNeedsItsPackageForJsonb() throws IOException {
        write(BOOK, annotated("jakarta.persistence.Entity",
                cb -> cb.withField("title", ConstantDescs.CD_String, ClassFile.ACC_PRIVATE)));
        mansartComponents(BOOK);
        write(ClassDesc.of("com.acme.shop.rest.BookResource"), annotated("jakarta.ws.rs.Path", cb ->
                cb.withMethodBody("one", MethodTypeDesc.of(BOOK), ClassFile.ACC_PUBLIC,
                        code -> code.aconst_null().areturn())));

        assertEquals(Map.of("com.acme.shop.book", "com.acme.shop.book.Book, reached through com.acme.shop.rest.BookResource"),
                ReflectedPackages.of(classes).reasons());
    }

    /** An entity of the package that the generated components do not name is still reached. */
    @Test
    void anEntityTheGeneratedComponentsDoNotNameNeedsItsPackageOpened() throws IOException {
        ClassDesc loan = ClassDesc.of("com.acme.shop.data.Loan");
        ClassDesc fee = ClassDesc.of("com.acme.shop.data.Fee");
        write(loan, annotated("jakarta.persistence.Entity", cb -> { }));
        write(fee, annotated("jakarta.persistence.Entity", cb -> { }));
        mansartComponents(loan);

        assertEquals(Map.of("com.acme.shop.data",
                        "com.acme.shop.data.Fee, an entity (Jakarta Data) without a model generated at build time"),
                ReflectedPackages.of(classes).reasons());
    }

    /**
     * The access Noether's processor generates in a package of entities, as it writes it: the
     * {@code _NoetherJpaAccess} provider listed in the services file names one {@code ManagedAccess} subclass per
     * entity, which reaches the entity's members by method handles the processor planned (no reflection).
     */
    private void noetherAccess(ClassDesc... entities) throws IOException {
        String pkg = entities[0].packageName();
        ClassDesc provider = ClassDesc.of(pkg, "_NoetherJpaAccess");
        for (ClassDesc entity : entities) {
            write(ClassDesc.of(pkg, entity.displayName() + "$$NoetherFieldAccess"), cb -> {
                cb.withSuperclass(ClassDesc.of("io.vidocq.noether.jpa.core.spi.ManagedAccess"));
                cb.withMethodBody("type", MethodTypeDesc.of(ConstantDescs.CD_Class), ClassFile.ACC_PUBLIC,
                        code -> code.ldc(entity).areturn());
            });
        }
        write(provider, cb -> {
            cb.withInterfaceSymbols(ClassDesc.of("io.vidocq.noether.jpa.core.spi.ManagedAccessProvider"));
            cb.withMethodBody("accesses", MethodTypeDesc.of(ConstantDescs.CD_Object), ClassFile.ACC_PUBLIC, code -> {
                for (ClassDesc entity : entities) {
                    code.ldc(ClassDesc.of(pkg, entity.displayName() + "$$NoetherFieldAccess")).pop();
                }
                code.aconst_null().areturn();
            });
        });
        Path services = classes.resolve("META-INF/services/io.vidocq.noether.jpa.core.spi.ManagedAccessProvider");
        Files.createDirectories(services.getParent());
        Files.writeString(services, pkg + "._NoetherJpaAccess\n");
    }

    /** Noether reaches an entity its processor generated an access for through that access, without reflection. */
    @Test
    void anEntityWithAnAccessNoetherGeneratedNeedsNoDirective() throws IOException {
        ClassDesc loan = ClassDesc.of("com.acme.shop.data.Loan");
        write(loan, annotated("jakarta.persistence.Entity",
                cb -> cb.withField("id", ConstantDescs.CD_long, ClassFile.ACC_PRIVATE)));
        noetherAccess(loan);

        assertEquals(Map.of(), ReflectedPackages.of(classes).reasons());
    }

    @Test
    void anEntityTheGeneratedNoetherAccessDoesNotNameNeedsItsPackageOpened() throws IOException {
        ClassDesc loan = ClassDesc.of("com.acme.shop.data.Loan");
        ClassDesc fee = ClassDesc.of("com.acme.shop.data.Fee");
        write(loan, annotated("jakarta.persistence.Entity", cb -> { }));
        write(fee, annotated("jakarta.persistence.Entity", cb -> { }));
        noetherAccess(loan);

        assertEquals(Map.of("com.acme.shop.data",
                        "com.acme.shop.data.Fee, an entity (Jakarta Data) without a model generated at build time"),
                ReflectedPackages.of(classes).reasons());
    }

    /** JSON-B and Mansart's reflective fallback read the inherited fields too: the superclass, and what its fields hold, need their packages. */
    @Test
    void theSuperclassOfAReachedTypeAndWhatItHoldsAreReachedToo() throws IOException {
        ClassDesc audited = ClassDesc.of("com.acme.shop.base.Audited");
        write(audited, cb -> {
            cb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_ABSTRACT);
            cb.withField("author", AUTHOR, ClassFile.ACC_PRIVATE);
        });
        write(AUTHOR, cb -> cb.withField("name", ConstantDescs.CD_String, ClassFile.ACC_PRIVATE));
        write(ClassDesc.of("com.acme.shop.data.Loan"), annotated("jakarta.persistence.Entity", cb -> {
            cb.withSuperclass(audited);
            cb.withField("id", ConstantDescs.CD_long, ClassFile.ACC_PRIVATE);
        }));

        Map<String, String> reached = ReflectedPackages.of(classes).reasons();

        assertEquals(Set.of("com.acme.shop.data", "com.acme.shop.base", "com.acme.shop.people"), reached.keySet());
        assertEquals("com.acme.shop.base.Audited, superclass of com.acme.shop.data.Loan",
                reached.get("com.acme.shop.base"));
    }

    /**
     * A field typed by an interface of the application: JSON-B binds the object it holds, whose class is one of the
     * application's implementations, so their packages are reached too.
     */
    @Test
    void theApplicationImplementationsOfAReachedInterfaceAreReachedToo() throws IOException {
        ClassDesc priced = ClassDesc.of("com.acme.shop.api.Priced");
        write(priced, cb -> cb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT));
        write(ClassDesc.of("com.acme.shop.pricing.FixedPrice"), cb -> {
            cb.withInterfaceSymbols(priced);
            cb.withField("amount", ConstantDescs.CD_long, ClassFile.ACC_PRIVATE);
        });
        write(BOOK, cb -> cb.withField("price", priced, ClassFile.ACC_PRIVATE));
        write(ClassDesc.of("com.acme.shop.rest.BookResource"), annotated("jakarta.ws.rs.Path", cb ->
                cb.withMethodBody("one", MethodTypeDesc.of(BOOK), ClassFile.ACC_PUBLIC,
                        code -> code.aconst_null().areturn())));

        Map<String, String> reached = ReflectedPackages.of(classes).reasons();

        assertEquals(Set.of("com.acme.shop.book", "com.acme.shop.api", "com.acme.shop.pricing"), reached.keySet());
        assertEquals("com.acme.shop.pricing.FixedPrice, implements com.acme.shop.api.Priced",
                reached.get("com.acme.shop.pricing"));
    }

    /** The entity of a {@code Response} shows in no signature: Champollion lists it, and its package is reached. */
    @Test
    void anEntityGivenAResponseIsReachedFromChampollionsList() throws IOException {
        bookAndAuthor();
        Path listed = classes.resolve(ReflectedPackages.RESPONSE_ENTITIES);
        Files.createDirectories(listed.getParent());
        Files.writeString(listed, "com.acme.shop.book.Book\n");

        Map<String, String> reached = ReflectedPackages.of(classes).reasons();
        assertEquals(Set.of("com.acme.shop.book", "com.acme.shop.people"), reached.keySet());
        assertTrue(reached.get("com.acme.shop.book").contains("the entity of a JAX-RS Response"),
                reached.toString());
    }

    /** With its binding generated, a listed entity needs no directive. */
    @Test
    void aBoundEntityGivenAResponseNeedsNoDirective() throws IOException {
        bookAndAuthor();
        generatedBinding(BOOK);
        Path listed = classes.resolve(ReflectedPackages.RESPONSE_ENTITIES);
        Files.createDirectories(listed.getParent());
        Files.writeString(listed, "com.acme.shop.book.Book\n");

        assertEquals(Map.of(), ReflectedPackages.of(classes).reasons());
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
