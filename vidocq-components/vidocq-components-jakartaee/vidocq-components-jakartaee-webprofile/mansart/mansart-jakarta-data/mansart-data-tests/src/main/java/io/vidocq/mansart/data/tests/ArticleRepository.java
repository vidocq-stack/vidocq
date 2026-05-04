package io.vidocq.mansart.data.tests;

import jakarta.data.repository.BasicRepository;
import jakarta.data.repository.Delete;
import jakarta.data.repository.Insert;
import jakarta.data.repository.Repository;
import jakarta.data.repository.Update;

import java.util.List;
import java.util.Optional;

@Repository
public interface ArticleRepository extends BasicRepository<Article, Long> {

    @Insert Article create(Article article);

    @Update Article modify(Article article);

    @Delete void remove(Article article);

    long count();

    Optional<Article> findOneByTitle(String title);

    List<Article> findByIdIn(List<Long> ids);
}
