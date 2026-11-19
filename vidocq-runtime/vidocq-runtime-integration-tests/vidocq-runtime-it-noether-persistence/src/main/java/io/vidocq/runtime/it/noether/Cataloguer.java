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
package io.vidocq.runtime.it.noether;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

/**
 * The {@code catalog} unit reached with {@code @Inject}: its {@code <qualifier>} names {@link Catalogued}, and its
 * {@code <scope>} makes its {@code EntityManager} application scoped, so it answers outside a transaction too, where
 * a transaction-scoped one has no active context.
 */
@ApplicationScoped
public class Cataloguer {

    @Inject
    @Catalogued
    EntityManager cards;

    /** Persists a card in the transaction of this method, and returns it. */
    @Transactional
    public Card add(String id) {
        Card card = new Card(id);
        cards.persist(card);
        return card;
    }

    /** The card {@code id}, outside any transaction. */
    public Card find(String id) {
        return cards.find(Card.class, id);
    }
}
