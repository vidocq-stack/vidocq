package io.vidocq.mansart.data.tests;

import jakarta.data.repository.BasicRepository;
import jakarta.data.repository.Repository;

@Repository
public interface AuthorRepository extends BasicRepository<Author, Long> {

    long count();

    boolean existsById(Long id);

    /* ---- M3b derived queries ---- */

    java.util.List<Author> findByName(String name);

    java.util.Optional<Author> findOneByName(String name);

    java.util.List<Author> findByNameLike(String pattern);

    long countByName(String name);

    boolean existsByName(String name);

    long deleteByName(String name);

    java.util.List<Author> findAllByOrderByNameAsc();

    /* ---- M3c pagination ---- */

    jakarta.data.page.Page<Author> findByNameLikeOrderByNameAsc(String pattern, jakarta.data.page.PageRequest pageRequest);

    jakarta.data.page.CursoredPage<Author> findByNameLikeOrderByIdAsc(String pattern, jakarta.data.page.PageRequest pageRequest);

    /* ---- M3c-2 In(List) ---- */

    java.util.List<Author> findByNameIn(java.util.List<String> names);
}
