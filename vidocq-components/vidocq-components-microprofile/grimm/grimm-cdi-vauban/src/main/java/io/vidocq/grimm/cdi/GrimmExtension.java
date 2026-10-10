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
package io.vidocq.grimm.cdi;

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.ws.rs.Path;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * MicroProfile OpenAPI 4.2 integration as a CDI 4.1
 * {@link BuildCompatibleExtension Build-Compatible Extension}.
 *
 * <p>During the {@code @Enhancement} phase, every class annotated with
 * {@link Path @Path} (typically JAX-RS resource beans) is recorded by its fully-qualified
 * name. The collected set reaches the runtime side as the synthetic {@link ScannedTypes} bean,
 * which the {@link GrimmModelCache} feeds to the annotation scanner at startup.</p>
 *
 * <p>The BCE itself performs no scanning — it merely tells CDI which classes are
 * candidates. The actual model build pipeline (spec "Processing rules" — reader → static
 * file → annotation scan → merge → config-apply → filter) runs inside
 * {@link io.vidocq.grimm.internal.ModelBuilder} when the model cache is instantiated.</p>
 *
 * <p>The recorded names belong to this extension instance, so to one container: the
 * {@code @Synthesis} phase hands them to a synthetic {@link ScannedTypes} bean, which
 * {@link ScannedTypesCreator} resolves in the container. They used to live in a static set,
 * shared by every container of the class loader (grimm#22).</p>
 *
 * <p>Registered as a Java Modules service in {@code module-info.java}:
 * {@code provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
 * with io.vidocq.grimm.cdi.GrimmExtension;}</p>
 */
public final class GrimmExtension implements BuildCompatibleExtension {

    /** Grimm's own classes stay out of the document: /openapi must not list itself (spec §2.2). */
    static final String OWN_PACKAGE_PREFIX = "io.vidocq.grimm.";

    // One extension instance per container start: the classes of this deployment only.
    private final Set<String> discoveredNames = Collections.synchronizedSet(new LinkedHashSet<>());

    /**
     * Records every application class found by the CDI scanner — spec §4.4: the
     * annotation scan covers <em>all application classes</em>, so {@code @Schema}
     * POJOs and the {@code @OpenAPIDefinition} {@code Application} subclass
     * contribute to the model, not only {@link Path @Path} resources. Grimm's
     * own classes are skipped: the /openapi endpoint itself must not be listed
     * in the document it serves (spec §2.2).
     */
    @Enhancement(types = Object.class, withSubtypes = true)
    public void registerApplicationClass(ClassConfig classConfig) {
        String name = classConfig.info().name();
        if (!name.startsWith(OWN_PACKAGE_PREFIX)) {
            discoveredNames.add(name);
        }
    }

    /**
     * Hands this container's classes to the runtime, as a synthetic {@link ScannedTypes} bean
     * whose parameter lists their names.
     */
    @Synthesis
    public void registerScannedTypes(SyntheticComponents components) {
        String[] names;
        synchronized (discoveredNames) {
            names = discoveredNames.toArray(String[]::new);
        }
        components.addBean(ScannedTypes.class)
                .type(ScannedTypes.class)
                .scope(Dependent.class)
                .withParam(ScannedTypesCreator.CLASSES, names)
                .createWith(ScannedTypesCreator.class);
    }
}
