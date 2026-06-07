package io.vidocq.runtime.examples.rest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.vidocq.runtime.examples.rest.model.Todo;
import java.util.List;

@ApplicationScoped
@Path("/todos")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TodoResource {

    @Inject
    TodoService todoService;

    @GET
    public List<Todo> list() {
        return todoService.list();
    }

    @POST
    public Response create(Todo input) {
        Todo todo = todoService.create(input.title());
        return Response.status(Response.Status.CREATED).entity(todo).build();
    }

    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") long id) {
        return todoService.get(id)
                .map(todo -> Response.ok(todo).build())
                .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }

    @PUT
    @Path("/{id}")
    public Response update(@PathParam("id") long id, Todo input) {
        return todoService.update(id, input.title(), input.done())
                .map(todo -> Response.ok(todo).build())
                .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") long id) {
        if (!todoService.delete(id)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.noContent().build();
    }
}
