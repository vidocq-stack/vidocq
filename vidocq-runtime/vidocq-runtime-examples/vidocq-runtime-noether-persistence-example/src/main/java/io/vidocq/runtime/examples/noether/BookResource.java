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

import io.vidocq.runtime.examples.noether.model.BookInput;
import io.vidocq.runtime.examples.noether.model.BookView;
import io.vidocq.runtime.examples.noether.model.NoteInput;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/** The books of the notebook and their notes. */
@ApplicationScoped
@Path("/books")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BookResource {

    private final Library library;

    @Inject
    public BookResource(Library library) {
        this.library = library;
    }

    @GET
    public List<BookView> list() {
        return library.books();
    }

    /** 201 with the book; 409 when its ISBN is already catalogued, and nothing is written. */
    @POST
    public Response catalogue(BookInput input) {
        try {
            BookView book = library.catalogue(input.title(), input.isbn(), input.author());
            return Response.status(Response.Status.CREATED).entity(book).build();
        } catch (AlreadyCatalogued refused) {
            return Response.status(Response.Status.CONFLICT).entity(refused.getMessage()).type(MediaType.TEXT_PLAIN)
                    .build();
        }
    }

    @GET
    @Path("/{id}/notes")
    public Response notes(@PathParam("id") long id) {
        return library.notes(id)
                .map(notes -> Response.ok(notes).build())
                .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }

    @POST
    @Path("/{id}/notes")
    public Response note(@PathParam("id") long id, NoteInput input) {
        return library.note(id, input.text())
                .map(note -> Response.status(Response.Status.CREATED).entity(note).build())
                .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }
}
