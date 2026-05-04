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

    /* ---- M5-2 JDQL extensions ---- */

    @jakarta.data.repository.Query("FROM Author WHERE id IN :ids")
    java.util.List<Author> jdqlByIdIn(java.util.List<Long> ids);

    @jakarta.data.repository.Query("SELECT COUNT(*) FROM Author WHERE name LIKE :pattern")
    long jdqlSelectCountByNameLike(String pattern);

    @jakarta.data.repository.Query("UPDATE Author SET name = :newName WHERE name = :oldName")
    long jdqlRename(String oldName, String newName);

    @jakarta.data.repository.Query("DELETE FROM Author WHERE name LIKE :pattern")
    long jdqlDeleteByNameLike(String pattern);

    /* ---- M5-3 OR/NOT with IN, multi-element IN literal ---- */

    @jakarta.data.repository.Query("FROM Author WHERE name = :name OR id IN :ids")
    java.util.List<Author> jdqlNameOrIdIn(String name, java.util.List<Long> ids);

    @jakarta.data.repository.Query("FROM Author WHERE NOT (id IN :ids)")
    java.util.List<Author> jdqlNotIdIn(java.util.List<Long> ids);

    @jakarta.data.repository.Query("FROM Author WHERE name IN (:a, :b, :c)")
    java.util.List<Author> jdqlNameInLiteral(String a, String b, String c);

    /* ---- M5-4 aggregates + projections ---- */

    @jakarta.data.repository.Query("SELECT MAX(id) FROM Author")
    Long jdqlMaxId();

    @jakarta.data.repository.Query("SELECT MIN(id) FROM Author WHERE name LIKE :pattern")
    long jdqlMinIdByPattern(String pattern);

    @jakarta.data.repository.Query("SELECT SUM(id) FROM Author WHERE name LIKE :pattern")
    Long jdqlSumIdByPattern(String pattern);

    @jakarta.data.repository.Query("SELECT name FROM Author ORDER BY name ASC")
    java.util.List<String> jdqlAllNames();

    @jakarta.data.repository.Query("SELECT name FROM Author WHERE id = ?1")
    java.util.Optional<String> jdqlNameById(Long id);
}
