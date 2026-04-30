package io.vidocq.mpserver.examples.rest;

public record Todo(long id, String title, boolean done) {
    public Todo withUpdate(String newTitle, boolean newDone) {
        return new Todo(this.id, newTitle, newDone);
    }
}
