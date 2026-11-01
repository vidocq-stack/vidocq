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
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

@SupportedSourceVersion(SourceVersion.RELEASE_25)
@SupportedOptions({MansartProcessor.UNRESOLVED_METHODS, MansartProcessor.MIGRATION_CHECK, MansartProcessor.MIGRATIONS})
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

    private EntityScanner             scanner;
    private MansartMetamodelWriter    mansartWriter;
    private JpaMetamodelWriter        jpaWriter;
    private RepositoryWriter          repositoryWriter;
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
        this.mansartWriter    = new MansartMetamodelWriter(sink);
        // M7-29 — JPA static metamodel emission only when the SingularAttribute SPI is present
        // on the user's classpath (jakarta.persistence-api 3.2 ships it). Always-on otherwise
        // would force the dep on every consumer.
        this.jpaWriter        = env.getElementUtils()
                .getTypeElement("jakarta.persistence.metamodel.SingularAttribute") != null
                ? new JpaMetamodelWriter(sink) : null;
        this.entityRegistry   = new EntityRegistry();
        this.repositoryWriter = new RepositoryWriter(sink,
                env.getElementUtils(), env.getTypeUtils(), entityRegistry, env.getMessager(),
                "warn".equalsIgnoreCase(env.getOptions().get(UNRESOLVED_METHODS))
                        ? Diagnostic.Kind.WARNING : Diagnostic.Kind.ERROR);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        if (round.processingOver()) {
            writeRepositoriesIndex();
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

        for (TypeElement type : entities) writeMetamodels(type);
        for (TypeElement repo : repositories) writeRepository(repo);
        return true;
    }

    private void writeMetamodels(TypeElement type) {
        EntityScanner.EntityDescriptor descriptor = scanner.scan(type);
        if (descriptor == null) return;
        // M8-3i — register the entity facet so RepositoryWriter can resolve
        // method-name path expressions (findByAuthorName) in the same round.
        entityRegistry.register(type.getQualifiedName().toString(), descriptor);
        checkTable(descriptor);
        try {
            mansartWriter.write(descriptor);
            if (jpaWriter != null) jpaWriter.write(descriptor);
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
        if (!implementableFromItsPackage(repo)) {
            // The generated class is a top-level class of the repository's package: it cannot implement a
            // private or local interface, nor one nested in a private type. MansartData.runtimeRepository and the
            // CDI extension serve it on the run-time path.
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "[mansart-data] " + repo.getQualifiedName() + " is not visible from its package: no "
                            + "implementation is generated, it is served on the run-time path.", repo);
            return;
        }
        writeMissingMetamodel(repo);
        try {
            String implFqn = repositoryWriter.writeIfRepository(repo);
            if (implFqn != null) {
                String itfFqn = repo.getQualifiedName().toString();
                repoEntries.add(itfFqn + "=" + implFqn);
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
        String metamodel = (pkg.isEmpty() ? "" : pkg + ".") + "_" + entity.getSimpleName();
        if (processingEnv.getElementUtils().getTypeElement(metamodel) == null) writeMetamodels(entity);
    }

    /** Whether a top-level class of the repository's package can implement it. */
    private static boolean implementableFromItsPackage(TypeElement repo) {
        for (Element e = repo; e instanceof TypeElement type; e = e.getEnclosingElement()) {
            NestingKind kind = type.getNestingKind();
            if (kind == NestingKind.LOCAL || kind == NestingKind.ANONYMOUS
                    || type.getModifiers().contains(Modifier.PRIVATE)) {
                return false;
            }
        }
        return true;
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
