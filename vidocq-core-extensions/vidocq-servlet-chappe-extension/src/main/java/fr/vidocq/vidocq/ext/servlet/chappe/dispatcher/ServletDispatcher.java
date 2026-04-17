package fr.vidocq.vidocq.ext.servlet.chappe.dispatcher;

import jakarta.servlet.Servlet;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Associe des {@link UrlPatternMatcher} à des {@link Servlet} et résout le servlet
 * le plus spécifique pour un path donné.
 */
public final class ServletDispatcher {

    /** Association pattern ↔ servlet. */
    public record Mapping(UrlPatternMatcher matcher, Servlet servlet, String servletName) {
        public Mapping {
            Objects.requireNonNull(matcher);
            Objects.requireNonNull(servlet);
            Objects.requireNonNull(servletName);
        }
    }

    private final List<Mapping> mappings;

    public ServletDispatcher(List<Mapping> mappings) {
        List<Mapping> sorted = new ArrayList<>(mappings);
        sorted.sort(Comparator.comparingInt(m -> m.matcher().precedence()));
        this.mappings = List.copyOf(sorted);
    }

    /** Trouve le servlet qui doit répondre pour le path donné. */
    public Optional<Mapping> find(String path) {
        for (Mapping m : mappings) {
            if (m.matcher().matches(path)) {
                return Optional.of(m);
            }
        }
        return Optional.empty();
    }

    public List<Mapping> mappings() {
        return mappings;
    }
}
