package fr.vidocq.vidocq.ext.rest.cassini.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Routeur Cassini : sélection best-match JAX-RS 4.0 §3.7.2.
 *
 * <p>Au constructeur, les routes sont triées par spécificité décroissante :</p>
 * <ol>
 *   <li>nombre de caractères littéraux (descendant)</li>
 *   <li>nombre total de groupes capturants (descendant)</li>
 *   <li>nombre de groupes à regex par défaut (descendant)</li>
 * </ol>
 *
 * <p>Lors du routing, on parcourt cette liste triée et on retient le
 * premier template qui matche (et dont le verbe HTTP correspond).</p>
 */
public final class UriRouter {

    private static final Comparator<ResourceMethod> BY_SPECIFICITY =
            Comparator.comparingInt((ResourceMethod r) -> r.template().literalChars()).reversed()
                    .thenComparing(Comparator.comparingInt((ResourceMethod r) -> r.template().totalCaptures()).reversed())
                    .thenComparing(Comparator.comparingInt(r -> r.template().defaultCaptures()));

    private final List<ResourceMethod> routes;

    public UriRouter(List<ResourceMethod> routes) {
        List<ResourceMethod> sorted = new ArrayList<>(routes);
        sorted.sort(BY_SPECIFICITY);
        this.routes = List.copyOf(sorted);
    }

    public Optional<MatchResult> match(String httpMethod, String path) {
        String p = normalize(path);
        for (ResourceMethod r : routes) {
            Optional<Map<String, String>> params = r.template().match(p);
            if (params.isPresent() && r.httpMethod().equalsIgnoreCase(httpMethod)) {
                return Optional.of(new MatchResult(r, params.get()));
            }
        }
        return Optional.empty();
    }

    public List<ResourceMethod> routes() {
        return routes;
    }

    public List<String> methodsAllowedFor(String path) {
        String p = normalize(path);
        List<String> verbs = new ArrayList<>();
        for (ResourceMethod r : routes) {
            if (r.template().match(p).isPresent() && !verbs.contains(r.httpMethod())) {
                verbs.add(r.httpMethod());
            }
        }
        return verbs;
    }

    private static String normalize(String raw) {
        if (raw == null || raw.isEmpty()) return "/";
        String s = raw.startsWith("/") ? raw : "/" + raw;
        if (s.length() > 1 && s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
