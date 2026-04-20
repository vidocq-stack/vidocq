package fr.vidocq.vidocq.ext.rest.cassini.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Template URI JAX-RS 4.0 (§3.1.1 / §3.7).
 *
 * <p>Supporte :</p>
 * <ul>
 *   <li>segments littéraux : {@code /foo/bar}</li>
 *   <li>paramètres par défaut : {@code /{id}} (regex {@code [^/]+})</li>
 *   <li>paramètres contraints : {@code /{id:[0-9]+}} ou {@code /{path:.*}}</li>
 * </ul>
 *
 * <p>Expose les trois métriques de spécificité utilisées pour la sélection
 * best-match (§3.7.2) :</p>
 * <ol>
 *   <li>{@link #literalChars()} — caractères non capturés (littéraux)</li>
 *   <li>{@link #totalCaptures()} — nombre total de groupes capturants</li>
 *   <li>{@link #defaultCaptures()} — groupes capturants utilisant le regex par défaut</li>
 * </ol>
 */
public final class UriTemplate {

    private static final String DEFAULT_REGEX = "[^/]+";

    private final String template;
    private final Pattern pattern;
    private final List<String> paramNames;
    private final int literalChars;
    private final int totalCaptures;
    private final int defaultCaptures;

    private UriTemplate(String template, Pattern pattern, List<String> paramNames,
                        int literalChars, int totalCaptures, int defaultCaptures) {
        this.template = template;
        this.pattern = pattern;
        this.paramNames = paramNames;
        this.literalChars = literalChars;
        this.totalCaptures = totalCaptures;
        this.defaultCaptures = defaultCaptures;
    }

    public static UriTemplate compile(String template) {
        String t = normalize(template);
        StringBuilder regex = new StringBuilder("^");
        List<String> names = new ArrayList<>();
        int literals = 0;
        int total = 0;
        int defaults = 0;

        int i = 0;
        while (i < t.length()) {
            char c = t.charAt(i);
            if (c == '{') {
                int end = findClosingBrace(t, i);
                if (end < 0) throw new IllegalArgumentException("Unclosed '{' in template: " + template);
                String inside = t.substring(i + 1, end).trim();
                int colon = inside.indexOf(':');
                String name;
                String paramRegex;
                if (colon < 0) {
                    name = inside;
                    paramRegex = DEFAULT_REGEX;
                    defaults++;
                } else {
                    name = inside.substring(0, colon).trim();
                    paramRegex = inside.substring(colon + 1).trim();
                }
                if (name.isEmpty()) throw new IllegalArgumentException("Empty param name in template: " + template);
                names.add(name);
                total++;
                regex.append("(?<").append(name).append(">").append(paramRegex).append(")");
                i = end + 1;
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
                literals++;
                i++;
            }
        }
        regex.append("$");
        return new UriTemplate(t, Pattern.compile(regex.toString()), List.copyOf(names),
                literals, total, defaults);
    }

    public Optional<Map<String, String>> match(String path) {
        Matcher m = pattern.matcher(path);
        if (!m.matches()) return Optional.empty();
        if (paramNames.isEmpty()) return Optional.of(Map.of());
        Map<String, String> params = new LinkedHashMap<>();
        for (String n : paramNames) params.put(n, m.group(n));
        return Optional.of(params);
    }

    public String template() { return template; }
    public List<String> paramNames() { return paramNames; }
    public int literalChars() { return literalChars; }
    public int totalCaptures() { return totalCaptures; }
    public int defaultCaptures() { return defaultCaptures; }

    private static int findClosingBrace(String s, int from) {
        int depth = 0;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static String normalize(String raw) {
        if (raw == null || raw.isEmpty() || "/".equals(raw)) return "/";
        String s = raw.startsWith("/") ? raw : "/" + raw;
        if (s.length() > 1 && s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
