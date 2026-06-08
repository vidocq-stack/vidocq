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
package io.vidocq.mansart.data.core;

import jakarta.data.page.CursoredPage;
import jakarta.data.page.PageRequest;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Immutable {@link CursoredPage} backed by an in-memory list. The cursor for each row is the list
 * of OrderBy attribute values, captured at row materialization.
 */
public record MansartCursoredPage<T>(
        List<T> content,
        PageRequest pageRequest,
        long totalElements,
        List<List<Object>> rowCursors
) implements CursoredPage<T> {

    public MansartCursoredPage {
        content = List.copyOf(content);
        rowCursors = List.copyOf(rowCursors);
    }

    @Override public int numberOfElements()                    { return content.size(); }
    @Override public boolean hasContent()                      { return !content.isEmpty(); }
    @Override public boolean hasTotals()                       { return totalElements >= 0; }
    @Override public Iterator<T> iterator()                    { return content.iterator(); }

    @Override
    public long totalPages() {
        if (totalElements < 0) return -1;
        int size = pageRequest.size();
        return (totalElements + size - 1) / size;
    }

    @Override public boolean hasNext()     { return content.size() == pageRequest.size(); }
    @Override public boolean hasPrevious() { return pageRequest.cursor().isPresent() && !content.isEmpty(); }

    @Override
    public PageRequest.Cursor cursor(int index) {
        return new ArrayCursor(rowCursors.get(index));
    }

    @Override
    public PageRequest nextPageRequest() {
        if (!hasNext()) throw new NoSuchElementException("No next page");
        return PageRequest.ofSize(pageRequest.size())
                .afterCursor(cursor(content.size() - 1));
    }

    @Override
    public PageRequest previousPageRequest() {
        if (!hasPrevious()) throw new NoSuchElementException("No previous page");
        return PageRequest.ofSize(pageRequest.size())
                .beforeCursor(cursor(0));
    }

    private static final class ArrayCursor implements PageRequest.Cursor {
        private final List<Object> values;
        ArrayCursor(List<Object> values)             { this.values = values; }
        @Override public int size()                  { return values.size(); }
        @Override public Object get(int index)       { return values.get(index); }
        @Override public List<?> elements()          { return values; }
    }
}
