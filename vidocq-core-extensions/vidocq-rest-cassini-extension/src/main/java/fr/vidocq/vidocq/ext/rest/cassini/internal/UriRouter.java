package fr.vidocq.vidocq.ext.rest.cassini.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Routeur simple pour M1 : parcours linéaire, match exact sur le chemin
 * canonique. Les URI templates (path params, regex) arrivent en M2a.
 */
public final class UriRouter {

    private final List<ResourceMethod> routes;

    public UriRouter(List<ResourceMethod> routes) {
        this.routes = List.copyOf(routes);
    }

    public Optional<ResourceMethod> match(String httpMethod, String path) {
        String p = normalize(path);
        for (ResourceMethod r : routes) {
            if (r.httpMethod().equalsIgnoreCase(httpMethod) && r.path().equals(p)) {
                return Optional.of(r);
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
            if (r.path().equals(p) && !verbs.contains(r.httpMethod())) {
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
