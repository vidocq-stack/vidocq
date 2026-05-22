package io.vidocq.runtime.examples.rest;

import jakarta.json.bind.annotation.JsonbCreator;
import jakarta.json.bind.annotation.JsonbProperty;

public record Todo(long id, String title, boolean done) {

    /**
     * Factory annotée {@link JsonbCreator} pour la désérialisation JSON-B.
     * <p>Sans cela, Yasson 3.0.4 en mode module-path strict (jlink/jpackage)
     * n'arrive pas à invoquer le canonical constructor du record et tombe en
     * defaults silencieux ({@code title=null}). Le marquage explicite force
     * Yasson à passer par cette factory, qui est résolue par les noms de
     * paramètres ({@code @JsonbProperty}).</p>
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
