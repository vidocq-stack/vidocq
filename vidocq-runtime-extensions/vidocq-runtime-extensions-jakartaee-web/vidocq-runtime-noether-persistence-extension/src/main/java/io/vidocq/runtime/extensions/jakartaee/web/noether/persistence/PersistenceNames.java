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
package io.vidocq.runtime.extensions.jakartaee.web.noether.persistence;

import io.vidocq.noether.jpa.cdi.PersistenceReferences;
import io.vidocq.vauban.naming.Binder;
import io.vidocq.vauban.naming.JndiBindings;
import io.vidocq.vauban.naming.JndiTarget;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The persistence references the application's classes declare on themselves, bound under {@code java:comp/env}
 * (Jakarta EE 11 Platform §5.13, §5.14): a servlet written for a server that declares
 * {@code @PersistenceContext(name = "persistence/orders", unitName = "orders")} on its class looks up
 * {@code java:comp/env/persistence/orders}. Noether lists the references and gives each one a {@code @Singleton} bean;
 * a name that starts with {@code java:} is bound as it is.
 *
 * <p>Noether's extension lists the references of each part of the deployment it runs on: a module processed at build,
 * or the classes it runs on at start. Each name is bound once; a name two parts give to different beans fails the
 * start.</p>
 *
 * <p>The names are answered only when the application has the naming extension, which asks this bean for them;
 * otherwise nothing looks them up. {@code @Dependent}: the naming service destroys it once it has bound the names.
 */
@Dependent
public class PersistenceNames implements JndiBindings {

    private static final String ENVIRONMENT = "java:comp/env/";

    private final List<PersistenceReferences.Reference> references;

    /**
     * The references Noether's extension listed, none when it did not run.
     *
     * @param parts the beans that list them, one for each part of the deployment that declares references
     * @throws IllegalStateException when two parts give one name to different beans
     */
    @Inject
    public PersistenceNames(Instance<PersistenceReferences> parts) {
        this.references = merged(parts);
    }

    /** The references of every part, each name once. */
    private static List<PersistenceReferences.Reference> merged(Iterable<PersistenceReferences> parts) {
        Map<String, PersistenceReferences.Reference> byName = new TreeMap<>();
        for (PersistenceReferences part : parts) {
            for (PersistenceReferences.Reference reference : part.references()) {
                PersistenceReferences.Reference known = byName.putIfAbsent(reference.name(), reference);
                if (known != null && !known.equals(reference)) {
                    throw new IllegalStateException("The persistence reference " + reference.name()
                            + " is declared twice, for two beans: " + known + " and " + reference);
                }
            }
        }
        return List.copyOf(byName.values());
    }

    @Override
    public void bind(Binder binder) {
        for (PersistenceReferences.Reference reference : references) {
            String name = reference.name().startsWith("java:") ? reference.name() : ENVIRONMENT + reference.name();
            binder.bind(name, new JndiTarget.Bean(reference.type(), Set.of(reference.qualifier()),
                    reference.type().getName() + " of the persistence reference " + reference.name()));
        }
    }
}
