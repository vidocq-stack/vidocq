package io.vidocq.mpserver.examples.mansart;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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

    @Inject
    ProductRepository products;

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
    public Response create(Product input) {
        Product saved = products.save(new Product(input.getName(), input.getPrice()));
        return Response.status(Response.Status.CREATED).entity(saved).build();
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") long id) {
        if (products.findById(id).isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        products.deleteById(id);
        return Response.noContent().build();
    }

    @GET
    @Path("/count")
    @Produces(MediaType.TEXT_PLAIN)
    public long count() {
        return products.count();
    }
}
