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

import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ahead-of-time code generation for {@code @Repository} interfaces that live in pre-compiled
 * dependency jars (where the {@code mansart-data-processor} APT never ran). Drives the very same
 * emitters used by the in-compiler path ({@link MansartMetamodelWriter}, {@link RepositoryWriter}),
 * but in <em>relocated</em> mode: every generated class is placed in an application-owned package
 * and refers to the external repository / entity by fully-qualified name, so the application module
 * never shares a package with the dependency jar (no JPMS split package).
 *
 * <p>The {@code mansart-data-maven-plugin} supplies real {@link Elements}/{@link Types} obtained
 * from a source-less {@code javax.tools.JavaCompiler} task over the application compile classpath,
 * plus a directory-backed {@link SourceSink}. This class contains no {@code javax.lang.model}
 * coupling beyond resolving the named types — all generation reuses the proven APT writers.
 */
public final class ExternalRepositoryCodegen {

    private ExternalRepositoryCodegen() {}

    /**
     * Generates a metamodel (when one is not already on the classpath) and a relocated
     * {@code Impl} for each repository named in {@code repoFqns}.
     *
     * @param elements      element utils from a {@code JavaCompiler} task seeing the dependency jars
     * @param types         type utils from the same task
     * @param sink          destination of generated sources (the plugin writes to generated-sources)
     * @param repoFqns      fully-qualified names of external {@code @Repository} interfaces to process
     * @param targetPackage application-owned package the generated classes are emitted into
     * @return one {@code interface-fqn=impl-fqn} entry per generated repository, ready to append to
     *         {@code META-INF/mansart-repositories.list}
     */
    public static List<String> generate(Elements elements, Types types, SourceSink sink,
                                        List<String> repoFqns, String targetPackage) throws IOException {
        EntityRegistry registry = new EntityRegistry();
        EntityScanner scanner = new EntityScanner(elements, types, null);
        MansartMetamodelWriter metaWriter = new MansartMetamodelWriter(sink);
        RepositoryWriter repoWriter = new RepositoryWriter(sink, elements, types, registry, null);

        // Pass 1 — resolve each repo's entity; reuse an existing (APT-built) metamodel if the
        // dependency jar already ships one, otherwise emit a relocated metamodel once per entity.
        Map<String, String> entityToMetamodel = new LinkedHashMap<>();
        List<TypeElement> repos = new ArrayList<>();
        for (String repoFqn : repoFqns) {
            TypeElement repo = elements.getTypeElement(repoFqn);
            if (repo == null) continue;
            TypeElement entity = repoWriter.entityTypeOf(repo);
            if (entity == null) continue; // not a BasicRepository<E, K>-shaped repository
            repos.add(repo);

            String entityFqn = entity.getQualifiedName().toString();
            if (entityToMetamodel.containsKey(entityFqn)) continue;

            // Reuse the dependency's own _Entity metamodel when present (lib was APT-built): no
            // regeneration, and no reflection into the external entity's private fields.
            String entityPkg = elements.getPackageOf(entity).getQualifiedName().toString();
            String entitySimple = entity.getSimpleName().toString();
            String existingMeta = entityPkg.isEmpty() ? "_" + entitySimple : entityPkg + "._" + entitySimple;
            if (elements.getTypeElement(existingMeta) != null) {
                entityToMetamodel.put(entityFqn, existingMeta);
                continue;
            }

            EntityScanner.EntityDescriptor descriptor = scanner.scan(entity);
            if (descriptor == null) continue; // not a valid @Entity (e.g. missing @Id)
            registry.register(entityFqn, descriptor);
            String metaSimple = "_" + mangle(entityFqn);
            String metaFqn = targetPackage.isEmpty() ? metaSimple : targetPackage + "." + metaSimple;
            metaWriter.write(descriptor, targetPackage, metaSimple, entityFqn);
            entityToMetamodel.put(entityFqn, metaFqn);
        }

        // Pass 2 — emit a relocated Impl per repository, wired to the resolved metamodel.
        List<String> entries = new ArrayList<>();
        for (TypeElement repo : repos) {
            TypeElement entity = repoWriter.entityTypeOf(repo);
            String metaFqn = entityToMetamodel.get(entity.getQualifiedName().toString());
            if (metaFqn == null) continue; // entity was not scannable — skip (warned by caller)
            String implSimple = mangle(repo.getQualifiedName().toString()) + "Impl";
            String implFqn = repoWriter.writeRelocated(repo, targetPackage, implSimple, metaFqn);
            if (implFqn != null) {
                entries.add(repo.getQualifiedName().toString() + "=" + implFqn);
            }
        }
        return entries;
    }

    /** Turns a fully-qualified name into a collision-free identifier segment. */
    private static String mangle(String fqn) {
        return fqn.replace('.', '_').replace('$', '_');
    }
}
