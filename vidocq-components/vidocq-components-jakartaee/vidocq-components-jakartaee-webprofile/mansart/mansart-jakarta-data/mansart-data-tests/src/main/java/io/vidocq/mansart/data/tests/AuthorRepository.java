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

    /* ---- M3c-3 multi-attribute cursor + mixed In ---- */

    jakarta.data.page.CursoredPage<Author> findByNameLikeOrderByNameAscIdAsc(
            String pattern, jakarta.data.page.PageRequest pageRequest);

    java.util.List<Author> findByNameLikeAndIdIn(String pattern, java.util.List<Long> ids);

    /* ---- M5 @Query JDQL ---- */

    @jakarta.data.repository.Query("FROM Author WHERE name = :name")
    java.util.List<Author> jdqlByName(String name);

    @jakarta.data.repository.Query("FROM Author WHERE name LIKE :pattern ORDER BY name DESC")
    java.util.List<Author> jdqlNameLikeDesc(String pattern);

    @jakarta.data.repository.Query("FROM Author WHERE id = ?1")
    java.util.Optional<Author> jdqlById(Long id);

    @jakarta.data.repository.Query("FROM Author WHERE name LIKE :pattern AND id > :minId")
    java.util.List<Author> jdqlNameLikeAndIdGt(String pattern, Long minId);

    @jakarta.data.repository.Query("FROM Author WHERE name IS NOT NULL")
    long jdqlCountNonNull();
}
