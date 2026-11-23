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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A book of the notebook: its author, and the notes taken while reading it. */
@Entity
@NamedQuery(name = Book.BY_ISBN, query = "SELECT b FROM Book b WHERE b.isbn = :isbn")
@NamedQuery(name = Book.BY_AUTHOR, query = "SELECT b FROM Book b WHERE b.author.name = :name ORDER BY b.title")
@NamedQuery(name = Book.ALL, query = "SELECT b FROM Book b ORDER BY b.title")
public class Book {

    static final String BY_ISBN = "Book.byIsbn";
    static final String BY_AUTHOR = "Book.byAuthor";
    static final String ALL = "Book.all";

    @Id
    // AUTO: Noether draws the identifier from a generator table that schema generation creates, at persist().
    // An IDENTITY key would be known only once the row is written, at the flush.
    @GeneratedValue
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, unique = true)
    private String isbn;

    @ManyToOne(optional = false)
    private Author author;

    @OneToMany(mappedBy = "book")
    @OrderBy("id")
    private List<ReadingNote> notes = new ArrayList<>();

    protected Book() {
        // for Noether
    }

    Book(String title, String isbn, Author author) {
        this.title = title;
        this.isbn = isbn;
        this.author = author;
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getIsbn() {
        return isbn;
    }

    public Author getAuthor() {
        return author;
    }

    /** The notes, read while the persistence context is open: the collection is lazy. */
    public List<ReadingNote> getNotes() {
        return Collections.unmodifiableList(notes);
    }

    /** Keeps the inverse side in step with a note persisted in the same transaction. */
    void add(ReadingNote note) {
        notes.add(note);
    }
}
