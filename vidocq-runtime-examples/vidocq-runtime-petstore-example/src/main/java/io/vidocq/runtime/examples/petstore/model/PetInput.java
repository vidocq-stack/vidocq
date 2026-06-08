package io.vidocq.runtime.examples.petstore.model;

import java.util.List;

/**
 * Write model accepted by {@code POST}/{@code PUT} on {@code /pets}. The {@code category} is given
 * by name and the {@code tags} by name — {@code PetService} resolves or creates the corresponding
 * {@code Category}/{@code Tag} rows.
 */
public record PetInput(
        String name,
        String category,
        String status,
        double price,
        List<String> tags) {
}
