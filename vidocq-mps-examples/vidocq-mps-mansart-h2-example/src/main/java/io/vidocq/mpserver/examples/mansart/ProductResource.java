package io.vidocq.mpserver.examples.mansart;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
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

    // Indirection via Instance<> : MansartDataExtension declares ProductRepository as a synthetic
    // bean inside its @Synthesis phase via Class.forName(itfFqn) — but at APT-time the user's
    // module classes are not yet loadable, so the synthetic bean cannot be materialized at compile
    // time and Vauban's APT validator therefore does not see it. Instance<> bypasses the static
    // check ; the lookup resolves at first call, by which time the runtime BCE has registered the
    // bean for real. (Future: refactor MansartRepoCreator to take a String FQN instead of Class<?>
    // — would let the synthetic bean be declared at compile time and unlock direct @Inject.)
    @Inject
    Instance<ProductRepository> productsLookup;

    private ProductRepository products() {
        return productsLookup.get();
    }

    @GET
    public List<Product> list(@QueryParam("name") String namePattern) {
        if (namePattern != null && !namePattern.isBlank()) {
            return products().findByNameLike(namePattern);
        }
        return products().findAll().toList();
    }

    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") long id) {
        return products().findById(id)
                .map(p -> Response.ok(p).build())
                .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }

    @POST
    public Response create(Product input) {
        Product saved = products().save(new Product(input.getName(), input.getPrice()));
        return Response.status(Response.Status.CREATED).entity(saved).build();
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") long id) {
        if (products().findById(id).isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        products().deleteById(id);
        return Response.noContent().build();
    }

    @GET
    @Path("/count")
    @Produces(MediaType.TEXT_PLAIN)
    public long count() {
        return products().count();
    }
}
