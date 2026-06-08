/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.mansart.data.tests;

import jakarta.data.repository.BasicRepository;
import jakarta.data.repository.Query;
import jakarta.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/**
 * M8-3 — repository targeting {@link Book}, with @Query JDQL exercising path navigation
 * (book.author.name) over the @ManyToOne Author relation.
 */
@Repository
public interface BookRepository extends BasicRepository<Book, Long> {

    long count();

    /* ---- M8-3 path expressions (book.author.<attr>) via @Query JDQL ---- */

    @Query("FROM Book WHERE author.name = :name")
    List<Book> jdqlByAuthorNameEq(String name);

    @Query("FROM Book WHERE author.name LIKE :pattern")
    List<Book> jdqlByAuthorNameLike(String pattern);

    @Query("FROM Book WHERE author.name = :name AND title LIKE :titlePattern")
    List<Book> jdqlByAuthorNameAndTitleLike(String name, String titlePattern);

    @Query("FROM Book WHERE UPPER(author.name) = :name")
    List<Book> jdqlByUpperAuthorName(String name);

    @Query("FROM Book WHERE author.id = :id")
    List<Book> jdqlByAuthorId(long id);

    @Query("FROM Book WHERE author.name IN (:a, :b)")
    List<Book> jdqlByAuthorNameIn(String a, String b);

    @Query("FROM Book WHERE author.name IS NOT NULL")
    long jdqlCountWhereAuthorNamePresent();

    @Query("FROM Book ORDER BY author.name ASC")
    List<Book> jdqlOrderedByAuthorName();

    @Query("SELECT title FROM Book WHERE author.name = :name ORDER BY title ASC")
    List<String> jdqlTitlesByAuthorName(String name);

    @Query("SELECT title, author.name FROM Book WHERE author.name = :name")
    List<Object[]> jdqlTitleAndAuthorName(String name);

    @Query("FROM Book WHERE author.name = :name")
    Optional<Book> jdqlOneByAuthorName(String name);

    /* ---- M8-3i query-by-method-name path navigation ---- */

    List<Book> findByAuthorName(String name);

    List<Book> findByAuthorNameLike(String pattern);

    List<Book> findByAuthorNameIgnoreCase(String name);

    List<Book> findByTitleAndAuthorName(String title, String authorName);

    List<Book> findByAuthorNameOrderByTitleAsc(String name);

    long countByAuthorName(String name);

    boolean existsByAuthorName(String name);

    Optional<Book> findOneByAuthorNameAndTitle(String authorName, String title);
}
