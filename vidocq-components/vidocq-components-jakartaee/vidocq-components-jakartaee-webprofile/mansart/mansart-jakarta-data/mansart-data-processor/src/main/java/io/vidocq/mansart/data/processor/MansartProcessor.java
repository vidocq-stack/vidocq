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
package io.vidocq.mansart.data.processor;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

@SupportedSourceVersion(SourceVersion.RELEASE_25)
@SupportedOptions({MansartProcessor.UNRESOLVED_METHODS, MansartProcessor.MIGRATION_CHECK, MansartProcessor.MIGRATIONS,
        MansartProcessor.PRIVATE_REPOSITORIES})
@SupportedAnnotationTypes({
        "jakarta.persistence.Entity",
        "jakarta.data.repository.Repository"
})
public final class MansartProcessor extends AbstractProcessor {

    /**
     * How a repository method no query derives from is reported: {@code error} (the default) fails the build,
     * {@code warn} keeps a generated method that throws {@code UnsupportedOperationException} (MANSART-030).
     */
    static final String UNRESOLVED_METHODS = "mansart.unresolvedMethods";

    /**
     * {@code off} turns off the warning about an entity whose table no Flyway SQL migration creates (MANSART-037); any
     * other value, or none, keeps it.
     */
    static final String MIGRATION_CHECK = "mansart.migrationCheck";

    /** Comma-separated directories of SQL migrations to read instead of {@code <class output>/db/migration}. */
    static final String MIGRATIONS = "mansart.migrations";

    /**
     * How a repository no class of its package can implement (a private interface, or one nested in a private type) is
     * reported (mansart#85): {@code error} (the default) fails the build, {@code warn} leaves it to the run-time path,
     * which serves it on the class path only.
     */
    static final String PRIVATE_REPOSITORIES = "mansart.privateRepositories";

    private EntityScanner             scanner;
    private MansartMetamodelWriter    mansartWriter;
    private RepositoryWriter          repositoryWriter;
    private ComponentsWriter          componentsWriter;
    private EntityRegistry            entityRegistry;
    private final java.util.List<String> repoEntries = new java.util.ArrayList<>();
    /**
     * Written when this run generated something without an error (mansart#40): {@code vidocq:generate} skips a
     * dependency jar that carries it, so a jar is never processed twice.
     */
    static final String MARKER = DependencyHints.MARKER;
    private boolean generated;
    private boolean failed;
    /** The application's migrations, read on the first entity; {@code null} when the check is off or there are none. */
    private MigrationTables migrationTables;
    private boolean migrationTablesRead;

    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);
        FilerSourceSink sink  = new FilerSourceSink(env.getFiler());
        this.scanner          = new EntityScanner(env);
        // The Jakarta Data metamodel _Book only: the JPA canonical metamodel Book_ belongs to the persistence
        // provider's processor, which knows the kind of every attribute (Vidocq/noether#45, F15).
        this.mansartWriter    = new MansartMetamodelWriter(sink);
        this.entityRegistry   = new EntityRegistry();
        this.componentsWriter = new ComponentsWriter(sink);
        this.repositoryWriter = new RepositoryWriter(sink,
                env.getElementUtils(), env.getTypeUtils(), entityRegistry, env.getMessager(),
                "warn".equalsIgnoreCase(env.getOptions().get(UNRESOLVED_METHODS))
                        ? Diagnostic.Kind.WARNING : Diagnostic.Kind.ERROR);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        if (round.processingOver()) {
            writeRepositoriesIndex();
            try {
                componentsWriter.writeServices(processingEnv.getFiler());
            } catch (IOException ex) {
                failed = true;
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "[mansart-data] Failed to write META-INF/services/" + ComponentsWriter.SERVICE + ": "
                                + ex.getMessage());
            }
            if (generated && !failed) writeMarker();
            return false;
        }

        Set<TypeElement> entities     = new LinkedHashSet<>();
        Set<TypeElement> repositories = new LinkedHashSet<>();

        for (TypeElement annotation : annotations) {
            switch (annotation.getQualifiedName().toString()) {
                case "jakarta.data.repository.Repository" ->
                        repositories.addAll(ElementFilter.typesIn(round.getElementsAnnotatedWith(annotation)));
                case "jakarta.persistence.Entity" ->
                        entities.addAll(ElementFilter.typesIn(round.getElementsAnnotatedWith(annotation)));
                default -> {
                    // not one of the two annotations @SupportedAnnotationTypes declares: nothing to collect
                }
            }
        }

        for (TypeElement type : entities) writeMetamodel(type);
        for (TypeElement repo : repositories) writeRepository(repo);
        try {
            componentsWriter.endOfRound();
        } catch (IOException ex) {
            failed = true;
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "[mansart-data] Failed to write the _MansartComponents classes: " + ex.getMessage());
        }
        return true;
    }

    /** Writes the Jakarta Data metamodel {@code _Entity}; the JPA metamodel {@code Entity_} is the provider's. */
    private void writeMetamodel(TypeElement type) {
        EntityScanner.EntityDescriptor descriptor = scanner.scan(type);
        if (descriptor == null) return;
        // M8-3i — register the entity facet so RepositoryWriter can resolve
        // method-name path expressions (findByAuthorName) in the same round.
        entityRegistry.register(type.getQualifiedName().toString(), descriptor);
        checkTable(descriptor);
        try {
            mansartWriter.write(descriptor);
            String qualified = type.getQualifiedName().toString();
            int dot = qualified.lastIndexOf('.');
            String metamodelPkg = dot < 0 ? "" : qualified.substring(0, dot);
            componentsWriter.entity(packageOf(type), qualified,
                    (metamodelPkg.isEmpty() ? "" : metamodelPkg + ".") + "_" + type.getSimpleName());
            generated = true;
        } catch (IOException ex) {
            failed = true;
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "[mansart-data] Failed to write metamodel for "
                            + type.getQualifiedName() + ": " + ex.getMessage(), type);
        }
    }

    /**
     * Warns when the application ships SQL migrations and none creates the entity's table (MANSART-037): a warning,
     * since the database may be managed elsewhere.
     */
    private void checkTable(EntityScanner.EntityDescriptor descriptor) {
        if (!migrationTablesRead) {
            migrationTablesRead = true;
            if (!"off".equalsIgnoreCase(processingEnv.getOptions().get(MIGRATION_CHECK))) {
                migrationTables = MigrationTables.read(processingEnv.getFiler(),
                        processingEnv.getOptions().get(MIGRATIONS)).orElse(null);
            }
        }
        MigrationTables tables = migrationTables;
        if (tables == null || tables.creates(descriptor.tableName())) {
            return;
        }
        TypeElement type = descriptor.type();
        String simple = type.getSimpleName().toString();
        String table = descriptor.tableName();
        StringBuilder message = new StringBuilder("[mansart-data] No migration in ").append(tables.where())
                .append(" creates the table \"").append(table).append('"');
        if (table.equals(io.vidocq.mansart.data.dialect.SqlNames.tableName(simple))) {
            message.append(" of entity ").append(type.getQualifiedName())
                    .append(": Mansart names a table after its class, in snake_case and plural (")
                    .append(simple).append(" → ").append(table).append(").");
            tables.madeAfterTheClassName(simple).ifPresentOrElse(
                    made -> message.append(" A migration creates \"").append(made).append("\": add @Table(name = \"")
                            .append(made).append("\") to ").append(simple).append(", or create \"").append(table)
                            .append("\"."),
                    () -> message.append(" Create it in a migration, or name the existing table with")
                            .append(" @Table(name = \"…\")."));
        } else {
            message.append(" that @Table names on ").append(type.getQualifiedName())
                    .append(". Correct @Table(name = \"…\"), or create it in a migration.");
        }
        message.append(" If the database is managed elsewhere, ignore this, or turn the check off with -A")
                .append(MIGRATION_CHECK).append("=off.");
        processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, message, type);
    }

    private void writeRepository(TypeElement repo) {
        String provider = foreignProvider(repo);
        if (provider != null) {
            // Jakarta Data §4: @Repository(provider) names the provider that implements the repository.
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "[mansart-data] " + repo.getQualifiedName()
                    + " is declared for the Jakarta Data provider \"" + provider + "\" (@Repository(provider)):"
                    + " Mansart leaves it to that provider.", repo);
            return;
        }
        TypeElement hidden = notVisibleFromItsPackage(repo);
        if (hidden != null) {
            // The generated class is a top-level class of the repository's package: it cannot implement a private
            // interface, nor one nested in a private type. An error by default; with the option, a warning, and
            // MansartData.runtimeRepository or the CDI extension serve it on the run-time path (class path only).
            reportPrivateRepository(repo, hidden);
            return;
        }
        writeMissingMetamodel(repo);
        try {
            String implFqn = repositoryWriter.writeIfRepository(repo);
            if (implFqn != null) {
                String itfFqn = repo.getQualifiedName().toString();
                repoEntries.add(itfFqn + "=" + implFqn);
                componentsWriter.repository(packageOf(repo), itfFqn, implFqn);
                generated = true;
            }
        } catch (IOException | RuntimeException ex) {
            failed = true;
            // An invalid @Query, or a shape the processor cannot implement: a compile error on the repository,
            // not an exception out of javac.
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "[mansart-data] Failed to write repository impl for "
                            + repo.getQualifiedName() + ": " + ex.getMessage(), repo);
        }
    }

    /**
     * Writes the metamodel of the repository's entity when nothing provides it yet (mansart#40): over a dependency
     * jar, {@code vidocq:generate} hands the processor the repository only, while the implementation refers to the
     * entity's {@code _Entity}.
     */
    private void writeMissingMetamodel(TypeElement repo) {
        TypeElement entity = repositoryWriter.entityTypeOf(repo);
        if (entity == null || entityRegistry.get(entity.getQualifiedName().toString()) != null) return;
        String pkg = processingEnv.getElementUtils().getPackageOf(entity).getQualifiedName().toString();
        String prefix = pkg.isEmpty() ? "" : pkg + ".";
        if (processingEnv.getElementUtils().getTypeElement(prefix + "_" + entity.getSimpleName()) != null) return;
        writeMetamodel(entity);
    }

    /**
     * The provider {@code @Repository(provider)} names when it is not Mansart (Jakarta Data §4), else {@code null}:
     * a repository declared for another provider is left to it, at build time and at run time.
     */
    private static String foreignProvider(TypeElement repo) {
        for (var mirror : repo.getAnnotationMirrors()) {
            if (!((TypeElement) mirror.getAnnotationType().asElement()).getQualifiedName()
                    .contentEquals("jakarta.data.repository.Repository")) continue;
            for (var entry : mirror.getElementValues().entrySet()) {
                if (entry.getKey().getSimpleName().contentEquals("provider")) {
                    String provider = String.valueOf(entry.getValue().getValue());
                    return provider.isEmpty() || provider.equalsIgnoreCase("mansart") ? null : provider;
                }
            }
        }
        return null;
    }

    private String packageOf(TypeElement type) {
        return processingEnv.getElementUtils().getPackageOf(type).getQualifiedName().toString();
    }

    /**
     * The type that keeps a top-level class of the repository's package from implementing it: the repository itself
     * when it is private, else the private type it is nested in; {@code null} when every type is visible.
     */
    private static TypeElement notVisibleFromItsPackage(TypeElement repo) {
        for (Element e = repo; e instanceof TypeElement type; e = e.getEnclosingElement()) {
            if (type.getModifiers().contains(Modifier.PRIVATE)) {
                return type;
            }
        }
        return null;
    }

    /** The error, or the warning with {@code -Amansart.privateRepositories=warn}, for a private repository. */
    private void reportPrivateRepository(TypeElement repo, TypeElement hidden) {
        String what = hidden == repo ? " is private" : " is nested in the private type " + hidden.getQualifiedName();
        String message = "[mansart-data] " + repo.getQualifiedName() + what + ", so no class of its package can"
                + " implement it: make it, and the types it is nested in, at least package-private.";
        if ("warn".equalsIgnoreCase(processingEnv.getOptions().get(PRIVATE_REPOSITORIES))) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, message + " It is left to the run-time"
                    + " path, which serves it on the class path only, never from a named module (-A"
                    + PRIVATE_REPOSITORIES + "=warn).", repo);
            return;
        }
        failed = true;
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message + " -A" + PRIVATE_REPOSITORIES
                + "=warn turns this error into a warning and leaves the repository to the run-time path, which"
                + " serves it on the class path only, never from a named module.", repo);
    }

    /** Writes {@link #MARKER}: which processor generated the jar's Mansart classes. */
    private void writeMarker() {
        try {
            javax.tools.FileObject f = processingEnv.getFiler().createResource(
                    javax.tools.StandardLocation.CLASS_OUTPUT, "", MARKER);
            try (java.io.Writer w = f.openWriter()) {
                w.write("processor=mansart-data-processor\n");
            }
        } catch (IOException ex) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "[mansart-data] Failed to write " + MARKER + ": " + ex.getMessage());
        }
    }

    /** Writes {@code META-INF/mansart-repositories.list} consumed by the CDI BCE. */
    private void writeRepositoriesIndex() {
        if (repoEntries.isEmpty()) return;
        try {
            javax.tools.FileObject f = processingEnv.getFiler().createResource(
                    javax.tools.StandardLocation.CLASS_OUTPUT, "",
                    DependencyHints.INDEX);
            try (java.io.Writer w = f.openWriter()) {
                w.write("# Generated by mansart-data-processor — one line per @Repository\n");
                w.write("# format: <interface-fqn>=<impl-fqn>\n");
                for (String e : repoEntries) { w.write(e); w.write('\n'); }
            }
        } catch (IOException ex) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "[mansart-data] Failed to write META-INF/mansart-repositories.list: " + ex.getMessage());
        }
    }
}
