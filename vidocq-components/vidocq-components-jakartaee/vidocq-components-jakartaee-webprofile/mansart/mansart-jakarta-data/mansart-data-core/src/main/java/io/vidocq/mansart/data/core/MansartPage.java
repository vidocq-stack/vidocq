package io.vidocq.mansart.data.core;

import jakarta.data.page.Page;
import jakarta.data.page.PageRequest;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Immutable {@link Page} implementation backed by an in-memory {@link List}.
 * Built once per {@code queryPage} call.
 */
public record MansartPage<T>(
        List<T> content,
        PageRequest pageRequest,
        long totalElements
) implements Page<T> {

    public MansartPage {
        content = List.copyOf(content);
    }

    @Override
    public long totalPages() {
        if (totalElements < 0) return -1;
        int size = pageRequest.size();
        return (totalElements + size - 1) / size;
    }

    @Override
    public int numberOfElements() {
        return content.size();
    }

    @Override
    public boolean hasContent() {
        return !content.isEmpty();
    }

    @Override
    public boolean hasNext() {
        if (totalElements >= 0) return pageRequest.page() < totalPages();
        return content.size() == pageRequest.size();
    }

    @Override
    public boolean hasPrevious() {
        return pageRequest.page() > 1;
    }

    @Override
    public PageRequest nextPageRequest() {
        if (!hasNext()) {
            throw new NoSuchElementException("No next page");
        }
        return PageRequest.ofPage(pageRequest.page() + 1, pageRequest.size(), pageRequest.requestTotal());
    }

    @Override
    public PageRequest previousPageRequest() {
        if (!hasPrevious()) {
            throw new NoSuchElementException("No previous page");
        }
        return PageRequest.ofPage(pageRequest.page() - 1, pageRequest.size(), pageRequest.requestTotal());
    }

    @Override
    public boolean hasTotals() {
        return totalElements >= 0;
    }

    @Override
    public Iterator<T> iterator() {
        return content.iterator();
    }
}
