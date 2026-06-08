package io.vidocq.runtime.examples.petstore;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@ApplicationScoped
@Path("/categories")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CategoryResource {

    @Inject
    CategoryRepository categories;

    @GET
    public List<Category> list() {
        return categories.findAll().toList();
    }

    @POST
    @Transactional
    public Response create(Category input) {
        Category saved = categories.save(new Category(input.getName()));
        return Response.status(Response.Status.CREATED).entity(saved).build();
    }
}
