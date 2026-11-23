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
package io.vidocq.runtime.examples.noether;

import io.vidocq.runtime.examples.noether.model.AuthorView;
import io.vidocq.runtime.examples.noether.model.BookView;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/** The authors of the notebook, and the books of each, through a named query. */
@ApplicationScoped
@Path("/authors")
@Produces(MediaType.APPLICATION_JSON)
public class AuthorResource {

    private final Library library;

    @Inject
    public AuthorResource(Library library) {
        this.library = library;
    }

    @GET
    public List<AuthorView> list() {
        return library.authors();
    }

    @GET
    @Path("/{name}/books")
    public List<BookView> books(@PathParam("name") String name) {
        return library.booksBy(name);
    }
}
