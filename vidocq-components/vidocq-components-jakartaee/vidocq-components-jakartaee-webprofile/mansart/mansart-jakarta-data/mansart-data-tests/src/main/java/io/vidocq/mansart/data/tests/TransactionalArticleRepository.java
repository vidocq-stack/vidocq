package io.vidocq.mansart.data.tests;

import jakarta.data.repository.BasicRepository;
import jakarta.data.repository.Repository;
import jakarta.transaction.Transactional;

import java.util.List;

/**
 * Test fixture for verifying {@code @Transactional} propagation by {@code RepositoryWriter}.
 *
 * <p>The class-level {@code @Transactional} must be reproduced on the generated
 * {@code TransactionalArticleRepositoryImpl}, and the method-level {@code @Transactional(REQUIRES_NEW)}
 * on {@code findByTitleSensitive} must also appear on the generated override.
 */
@Repository
@Transactional
public interface TransactionalArticleRepository extends BasicRepository<Article, Long> {

    long count();

    List<Article> findByTitle(String title);

    /**
     * Method with a more specific transaction annotation — must be emitted verbatim on the
     * generated override even when the class already carries a broader {@code @Transactional}.
     * Per CDI interceptor binding rules the method-level annotation overrides the class level.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    List<Article> findByTitleSensitive(String title);
}
