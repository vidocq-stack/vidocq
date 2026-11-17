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
package io.vidocq.mansart.data.vaubanit;

import jakarta.data.repository.BasicRepository;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Injects the two repositories by their parameterized supertype, not by their own interface: only the type
 * arguments tell them apart.
 */
@Singleton
public class Shelf {

    private final BasicRepository<Book, Long> books;
    private final BasicRepository<Author, Long> authors;

    @Inject
    public Shelf(BasicRepository<Book, Long> books, BasicRepository<Author, Long> authors) {
        this.books = books;
        this.authors = authors;
    }

    public BasicRepository<Book, Long> books() {
        return books;
    }

    public BasicRepository<Author, Long> authors() {
        return authors;
    }
}
