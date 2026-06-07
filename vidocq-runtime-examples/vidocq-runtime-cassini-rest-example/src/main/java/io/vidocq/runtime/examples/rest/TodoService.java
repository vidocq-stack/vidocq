package io.vidocq.runtime.examples.rest;

import jakarta.enterprise.context.ApplicationScoped;

import io.vidocq.runtime.examples.rest.model.Todo;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
public class TodoService {

    private final Map<Long, Todo> store = new ConcurrentHashMap<>();
    private final AtomicLong counter = new AtomicLong(0);

    public List<Todo> list() {
        return new ArrayList<>(store.values());
    }

    public Todo create(String title) {
        long id = counter.incrementAndGet();
        Todo todo = new Todo(id, title, false);
        store.put(id, todo);
        return todo;
    }

    public Optional<Todo> get(long id) {
        return Optional.ofNullable(store.get(id));
    }

    public Optional<Todo> update(long id, String title, boolean done) {
        Todo existing = store.get(id);
        if (existing == null) return Optional.empty();
        Todo updated = existing.withUpdate(title, done);
        store.put(id, updated);
        return Optional.of(updated);
    }

    public boolean delete(long id) {
        return store.remove(id) != null;
    }
}
