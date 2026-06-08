package io.vidocq.runtime.examples.petstore;

import jakarta.data.repository.BasicRepository;
import jakarta.data.repository.Repository;
import jakarta.transaction.Transactional;

import java.util.List;

/**
 * Mansart-generated repository: APT produces {@code PetRepositoryImpl} at compile time and the
 * {@code mansart-data-cdi} BCE wires it into Vauban as a singleton bean.
 */
@Transactional
@Repository
public interface PetRepository extends BasicRepository<Pet, Long> {

    long count();

    List<Pet> findByStatus(String status);

    List<Pet> findByCategoryId(Long categoryId);
}
