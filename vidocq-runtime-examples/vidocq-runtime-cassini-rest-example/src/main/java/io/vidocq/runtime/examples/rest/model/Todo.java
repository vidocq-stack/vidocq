package io.vidocq.runtime.examples.rest.model;

import jakarta.json.bind.annotation.JsonbCreator;
import jakarta.json.bind.annotation.JsonbProperty;

public record Todo(long id, String title, boolean done) {

    /**
     * Annotated factory {@link JsonbCreator} for JSON-B deserialization.
     * <p>Without this, Yasson 3.0.4 in strict module-path mode (jlink/jpackage)
     * fails to invoke the canonical constructor of the record and falls into
     * silent defaults ({@code title=null}). Explicit marking forces
     * Yasson to go through this factory, which is resolved by the names of
     * parameters ({@code @JsonbProperty}).</p>
     */
    @JsonbCreator
    public static Todo create(@JsonbProperty("id") long id,
                              @JsonbProperty("title") String title,
                              @JsonbProperty("done") boolean done) {
        return new Todo(id, title, done);
    }

    public Todo withUpdate(String newTitle, boolean newDone) {
        return new Todo(this.id, newTitle, newDone);
    }
}
