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
package io.vidocq.mansart.it.weld;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/** A normal-scoped bean with Mansart's {@code @Transactional} interceptor, as an application writes it. */
@ApplicationScoped
public class Library {

    @Inject
    Books books;

    @Transactional
    public void addBoth(String first, String second) {
        books.save(book(first));
        books.save(book(second));
    }

    @Transactional
    public void addThenFail(String title) {
        books.save(book(title));
        throw new IllegalStateException("the book saved above must be rolled back");
    }

    private static Book book(String title) {
        Book b = new Book();
        b.setTitle(title);
        return b;
    }
}
