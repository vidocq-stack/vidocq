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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Fixture for the interceptor-driven path of the JTA bridge tests: a business method whose
 * {@code @Transactional} interceptor opens the transaction, writes through a repository, then
 * fails — the write must be rolled back with it.
 */
@ApplicationScoped
public class TxWritingService {

    @Inject
    AuthorRepository authors;

    @Transactional
    public void saveAndFail(String name) {
        Author a = new Author();
        a.setName(name);
        authors.save(a);
        throw new IllegalStateException("boom — the save above must be rolled back");
    }

    @Transactional
    public void saveTwo(String first, String second) {
        for (String name : new String[]{first, second}) {
            Author a = new Author();
            a.setName(name);
            authors.save(a);
        }
    }
}
