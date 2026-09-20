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
package io.vidocq.runtime.examples.mansart;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@ApplicationScoped
@Path("/products")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ProductResource {

    private static final System.Logger LOG = System.getLogger(ProductResource.class.getName());

    @Inject
    ProductRepository products;

    @Inject
    OperationAudit audit;

    @GET
    public List<Product> list(@QueryParam("name") String namePattern) {
        if (namePattern != null && !namePattern.isBlank()) {
            return products.findByNameLike(namePattern);
        }
        return products.findAll().toList();
    }

    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") long id) {
        return products.findById(id)
                .map(p -> Response.ok(p).build())
                .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }

    @POST
    @Transactional
    public Response create(Product input) {
        Product saved = products.save(new Product(input.getName(), input.getPrice()));
        audit.record("created product id=" + saved.getId());
        LOG.log(System.Logger.Level.INFO, () -> "TX audit: " + audit.entries());
        return Response.status(Response.Status.CREATED).entity(saved).build();
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response delete(@PathParam("id") long id) {
        if (products.findById(id).isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        products.deleteById(id);
        audit.record("deleted product id=" + id);
        LOG.log(System.Logger.Level.INFO, () -> "TX audit: " + audit.entries());
        return Response.noContent().build();
    }

    @GET
    @Path("/count")
    @Produces(MediaType.TEXT_PLAIN)
    public long count() {
        return products.count();
    }

    /**
     * Test-only unit of work for {@code TransactionalRollbackTest} (Vidocq/vidocq#97): writes a
     * product and then always fails, so an in-process test can prove the write does not survive
     * the transaction rollback. Not a JAX-RS resource method (no {@code @GET}/{@code @POST}), so
     * Cassini never exposes it as an endpoint.
     */
    @Transactional
    void saveThenFailForTests() {
        products.save(new Product("Rollback probe", 1.0));
        throw new IllegalStateException("simulated failure after a write, for TransactionalRollbackTest (Vidocq/vidocq#97)");
    }
}
