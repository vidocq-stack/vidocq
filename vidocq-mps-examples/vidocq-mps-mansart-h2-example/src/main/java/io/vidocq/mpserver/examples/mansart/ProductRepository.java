package io.vidocq.mpserver.examples.mansart;

import jakarta.data.repository.BasicRepository;
import jakarta.data.repository.Repository;
import jakarta.transaction.Transactional;

import java.util.List;

/**
 * Mansart-generated implementation: APT produces {@code ProductRepositoryImpl} at compile-time
 * and the {@code mansart-data-cdi} BCE wires it into Vauban as a singleton bean. The
 * {@link ProductResource} simply {@code @Inject}s this interface.
 */
@Transactional
@Repository
public interface ProductRepository extends BasicRepository<Product, Long> {

    long count();

    List<Product> findByNameLike(String pattern);
}
