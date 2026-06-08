package io.vidocq.runtime.examples.petstore;

import jakarta.data.repository.BasicRepository;
import jakarta.data.repository.Repository;
import jakarta.transaction.Transactional;

import java.util.List;

@Transactional
@Repository
public interface TagRepository extends BasicRepository<Tag, Long> {

    /** Returns the tags matching {@code name} (0 or 1 given the unique constraint). */
    List<Tag> findByName(String name);
}
