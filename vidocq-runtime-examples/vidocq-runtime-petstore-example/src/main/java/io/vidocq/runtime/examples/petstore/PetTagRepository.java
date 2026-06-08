package io.vidocq.runtime.examples.petstore;

import jakarta.data.repository.BasicRepository;
import jakarta.data.repository.Repository;
import jakarta.transaction.Transactional;

import java.util.List;

@Transactional
@Repository
public interface PetTagRepository extends BasicRepository<PetTag, Long> {

    List<PetTag> findByPetId(Long petId);
}
