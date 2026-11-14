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
package io.vidocq.kerckhoffs.cdi;

import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.inject.Singleton;
import jakarta.security.enterprise.identitystore.IdentityStore;
import jakarta.security.enterprise.identitystore.IdentityStoreHandler;
import jakarta.security.enterprise.identitystore.InMemoryIdentityStoreDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * Makes the identity stores of an application reach the {@link IdentityStoreHandler} (Jakarta
 * Security 4.0, sections 3.2.4 and 3.4.3): one {@link IdentityStore} bean per
 * {@link InMemoryIdentityStoreDefinition}, and the default handler over every store bean.
 *
 * <p>It works at build, from the classes of the deployment only; what it found travels in the
 * parameters of the synthetic beans.
 */
public class KerckhoffsExtension implements BuildCompatibleExtension {

    /** A definition and the class that declares it. */
    record Declared(String origin, AnnotationInfo definition) {
    }

    private final List<Declared> inMemoryDefinitions = new ArrayList<>();

    @Enhancement(types = Object.class, withSubtypes = true, withAnnotations = InMemoryIdentityStoreDefinition.class)
    public void collectInMemoryDefinition(ClassInfo declaring) {
        inMemoryDefinitions.add(new Declared(declaring.name(), declaring.annotation(InMemoryIdentityStoreDefinition.class)));
    }

    @Synthesis
    public void synthesizeBeans(SyntheticComponents components) {
        for (Declared declared : inMemoryDefinitions) {
            components.addBean(IdentityStore.class)
                    .type(IdentityStore.class)
                    .scope(Singleton.class)
                    // The origin tells two definitions apart: the container merges beans of equal parameters.
                    .withParam("origin", declared.origin())
                    .withParam("definition", declared.definition())
                    .createWith(InMemoryIdentityStoreCreator.class);
        }
        components.addBean(IdentityStoreHandler.class)
                .type(IdentityStoreHandler.class)
                .scope(Singleton.class)
                .createWith(IdentityStoreHandlerCreator.class);
    }
}
