package io.vidocq.runtime.examples.petstore;

import jakarta.data.repository.BasicRepository;
import jakarta.data.repository.Repository;
import jakarta.transaction.Transactional;

import java.util.List;

@Transactional
@Repository
public interface CategoryRepository extends BasicRepository<Category, Long> {

    /** Returns the categories matching {@code name} (0 or 1 given the unique constraint). */
    List<Category> findByName(String name);
}
