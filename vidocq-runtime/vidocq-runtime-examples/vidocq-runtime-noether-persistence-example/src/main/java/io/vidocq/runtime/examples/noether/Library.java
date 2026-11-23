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
package io.vidocq.runtime.examples.noether;

import io.vidocq.runtime.examples.noether.model.AuthorView;
import io.vidocq.runtime.examples.noether.model.BookView;
import io.vidocq.runtime.examples.noether.model.NoteView;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * The notebook's work, each method in one transaction. The entity manager is the container's: transaction scoped,
 * it joins the transaction {@code @Transactional} opens, and its changes are written when that transaction commits,
 * or dropped when it rolls back.
 *
 * <p>The methods return views, built inside the transaction: an entity read here is detached once the method
 * returns, and a lazy relation is read while the persistence context is open.
 */
@ApplicationScoped
public class Library {

    @PersistenceContext
    EntityManager books;

    /**
     * Catalogues a book. Its author is found by name, or created: then the ISBN is checked, and a refusal throws
     * after that creation, so the rollback has something to undo. An author created for a refused book is not kept.
     */
    @Transactional
    public BookView catalogue(String title, String isbn, String authorName) {
        Author author = author(authorName);
        if (!books.createNamedQuery(Book.BY_ISBN, Book.class).setParameter("isbn", isbn).getResultList().isEmpty()) {
            throw new AlreadyCatalogued(isbn);
        }
        Book book = new Book(title, isbn, author);
        books.persist(book);
        return view(book);
    }

    @Transactional
    public List<BookView> books() {
        return books.createNamedQuery(Book.ALL, Book.class).getResultStream().map(Library::view).toList();
    }

    @Transactional
    public List<BookView> booksBy(String authorName) {
        return books.createNamedQuery(Book.BY_AUTHOR, Book.class)
                .setParameter("name", authorName)
                .getResultStream()
                .map(Library::view)
                .toList();
    }

    @Transactional
    public List<AuthorView> authors() {
        return books.createNamedQuery(Author.ALL, Author.class)
                .getResultStream()
                .map(author -> new AuthorView(author.getId(), author.getName()))
                .toList();
    }

    /** Adds a note to a book; empty when there is no such book. */
    @Transactional
    public Optional<NoteView> note(long bookId, String text) {
        Book book = books.find(Book.class, bookId);
        if (book == null) {
            return Optional.empty();
        }
        ReadingNote note = new ReadingNote(text, book);
        books.persist(note);
        book.add(note);
        return Optional.of(new NoteView(note.getId(), note.getText()));
    }

    /** The notes of a book, in the order they were taken; empty when there is no such book. */
    @Transactional
    public Optional<List<NoteView>> notes(long bookId) {
        return Optional.ofNullable(books.find(Book.class, bookId))
                .map(book -> book.getNotes().stream().map(note -> new NoteView(note.getId(), note.getText())).toList());
    }

    private Author author(String name) {
        return books.createNamedQuery(Author.BY_NAME, Author.class)
                .setParameter("name", name)
                .getResultStream()
                .findFirst()
                .orElseGet(() -> {
                    Author author = new Author(name);
                    books.persist(author);
                    return author;
                });
    }

    private static BookView view(Book book) {
        return new BookView(book.getId(), book.getTitle(), book.getIsbn(), book.getAuthor().getName(),
                book.getNotes().size());
    }
}
