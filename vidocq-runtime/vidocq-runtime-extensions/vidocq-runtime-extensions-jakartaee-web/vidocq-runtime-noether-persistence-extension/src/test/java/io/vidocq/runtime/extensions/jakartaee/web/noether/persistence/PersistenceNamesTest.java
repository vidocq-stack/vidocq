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

import io.vidocq.noether.jpa.cdi.PersistenceContextCreator;
import io.vidocq.noether.jpa.cdi.PersistenceReferences;
import io.vidocq.noether.jpa.cdi.PersistenceReferences.Reference;
import io.vidocq.vauban.naming.Binder;
import io.vidocq.vauban.naming.JndiTarget;
import jakarta.enterprise.inject.AmbiguousResolutionException;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The persistence references Noether lists, bound under java:comp/env. */
class PersistenceNamesTest {

    /** Records what is bound. */
    private static final class Recorder implements Binder {
        final Map<String, JndiTarget> bound = new LinkedHashMap<>();
        @Override public Binder bind(String name, JndiTarget target) { bound.put(name, target); return this; }
    }

    /**
     * The beans of a type, as the container hands them: one per part of the deployment Noether's extension ran on,
     * which a single selection finds ambiguous.
     */
    private record Beans(List<PersistenceReferences> beans) implements Instance<PersistenceReferences> {
        @Override public Iterator<PersistenceReferences> iterator() { return beans.iterator(); }
        @Override public PersistenceReferences get() {
            if (beans.size() != 1) throw new AmbiguousResolutionException(beans.size() + " beans");
            return beans.getFirst();
        }
        @Override public boolean isUnsatisfied() { return beans.isEmpty(); }
        @Override public boolean isAmbiguous() { return beans.size() > 1; }
        @Override public Instance<PersistenceReferences> select(Annotation... qualifiers) { return this; }
        @Override public <U extends PersistenceReferences> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }
        @Override public <U extends PersistenceReferences> Instance<U> select(TypeLiteral<U> subtype,
                                                                           Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }
        @Override public void destroy(PersistenceReferences instance) { /* @Singleton beans: nothing to destroy */ }
        @Override public Handle<PersistenceReferences> getHandle() { throw new UnsupportedOperationException(); }
        @Override public Iterable<? extends Handle<PersistenceReferences>> handles() {
            throw new UnsupportedOperationException();
        }
    }

    private static Map<String, JndiTarget> bound(Reference... references) {
        return bound(new PersistenceReferences(List.of(references)));
    }

    private static Map<String, JndiTarget> bound(PersistenceReferences... parts) {
        var recorder = new Recorder();
        new PersistenceNames(new Beans(List.of(parts))).bind(recorder);
        return recorder.bound;
    }

    private static final Reference ORDERS = new Reference("persistence/orders", EntityManager.class,
            new PersistenceContextCreator.Binding("shared:context:6:orders"));
    private static final Reference LEDGER = new Reference("persistence/ledger", EntityManagerFactory.class,
            new PersistenceContextCreator.Binding("unit:ledger"));

    @Test
    void aReferenceIsBoundUnderTheEnvironmentToTheBeanNoetherGaveIt() {
        var qualifier = new PersistenceContextCreator.Binding("shared:context:6:orders");

        var bean = (JndiTarget.Bean) bound(new Reference("persistence/orders", EntityManager.class, qualifier))
                .get("java:comp/env/persistence/orders");

        assertEquals(EntityManager.class, bean.type());
        assertEquals(Set.of(qualifier), bean.qualifiers());
    }

    @Test
    void aNameThatStartsWithJavaIsBoundAsItIs() {
        var qualifier = new PersistenceContextCreator.Binding("unit:orders");

        assertEquals(Set.of("java:app/env/orders"),
                bound(new Reference("java:app/env/orders", EntityManagerFactory.class, qualifier)).keySet());
    }

    @Test
    void withoutReferencesNothingIsBound() {
        assertEquals(Map.of(), bound(new PersistenceReferences[0]));
    }

    @Test
    void theReferencesOfEveryPartOfTheDeploymentAreBound() {
        // BUG-20261118-65: the extension ran on two parts (a frozen module and a war's classes at start), the two
        // beans made the selection ambiguous, and no name was bound.
        assertEquals(Set.of("java:comp/env/persistence/orders", "java:comp/env/persistence/ledger"),
                bound(new PersistenceReferences(List.of(ORDERS)), new PersistenceReferences(List.of(LEDGER))).keySet());
    }

    @Test
    void aReferenceTwoPartsDeclareIsBoundOnce() {
        assertEquals(Set.of("java:comp/env/persistence/orders"),
                bound(new PersistenceReferences(List.of(ORDERS)), new PersistenceReferences(List.of(ORDERS))).keySet());
    }

    @Test
    void aNameTwoPartsGiveDifferentBeansIsRefused() {
        var other = new Reference("persistence/orders", EntityManagerFactory.class,
                new PersistenceContextCreator.Binding("unit:orders"));
        var first = new PersistenceReferences(List.of(ORDERS));
        var second = new PersistenceReferences(List.of(other));

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> bound(first, second));

        assertTrue(refused.getMessage().startsWith("The persistence reference persistence/orders is declared twice"),
                refused.getMessage());
    }
}
