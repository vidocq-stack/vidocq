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
package io.vidocq.mansart.data.tests;

import jakarta.data.Order;
import jakarta.data.page.Page;
import jakarta.data.page.PageRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * mansart#40 fixture: the implementation another provider's processor generated into a library, under its
 * {@code <Repository>_} convention (Hibernate Processor's), and that needs that provider at run time.
 */
@ApplicationScoped
public class ShelfNotes_ implements ShelfNotes {

    private final ForeignSession session;

    @Inject
    public ShelfNotes_(ForeignSession session) {
        this.session = session;
    }

    private UnsupportedOperationException foreign() {
        return new UnsupportedOperationException("another provider's implementation (" + session + ")");
    }

    @Override public long countByText(String text) { throw foreign(); }
    @Override public <S extends ShelfNote> S save(S entity) { throw foreign(); }
    @Override public <S extends ShelfNote> List<S> saveAll(List<S> entities) { throw foreign(); }
    @Override public Optional<ShelfNote> findById(Long id) { throw foreign(); }
    @Override public Stream<ShelfNote> findAll() { throw foreign(); }
    @Override public Page<ShelfNote> findAll(PageRequest pageRequest, Order<ShelfNote> sortBy) { throw foreign(); }
    @Override public void deleteById(Long id) { throw foreign(); }
    @Override public void delete(ShelfNote entity) { throw foreign(); }
    @Override public void deleteAll(List<? extends ShelfNote> entities) { throw foreign(); }
}
