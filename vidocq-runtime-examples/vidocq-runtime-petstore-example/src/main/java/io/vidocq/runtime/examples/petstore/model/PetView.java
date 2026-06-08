package io.vidocq.runtime.examples.petstore.model;

import java.util.List;

/**
 * Read model returned by the REST layer: the flat {@code Pet} entity enriched with its resolved
 * category name and tag names. Assembled by {@code PetService} — never serialised from the entity
 * directly, so the foreign keys stay an implementation detail.
 */
public record PetView(
        Long id,
        String name,
        String category,
        String status,
        double price,
        List<String> tags) {
}
