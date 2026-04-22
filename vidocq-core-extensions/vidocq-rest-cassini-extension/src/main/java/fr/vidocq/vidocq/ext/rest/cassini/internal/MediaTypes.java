package fr.vidocq.vidocq.ext.rest.cassini.internal;

import jakarta.ws.rs.core.MediaType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Helpers pour {@link MediaType} : parsing, matching par wildcard, tri par
 * qualité et sélection best-match (§3.8 Content Negotiation).
 */
public final class MediaTypes {

    public static final MediaType WILDCARD = MediaType.WILDCARD_TYPE;

    private MediaTypes() {}

    /** Parse "type/subtype;p1=v1;q=0.8" en {@link MediaType}. Défaut wildcard. */
    public static MediaType parse(String raw) {
        if (raw == null || raw.isBlank()) return WILDCARD;
        String[] parts = raw.split(";");
        String[] ts = parts[0].trim().split("/", 2);
        String type = ts[0].trim().isEmpty() ? "*" : ts[0].trim();
        String subtype = ts.length > 1 && !ts[1].trim().isEmpty() ? ts[1].trim() : "*";
        Map<String, String> params = new HashMap<>();
        for (int i = 1; i < parts.length; i++) {
            String seg = parts[i].trim();
            if (seg.isEmpty()) continue;
            int eq = seg.indexOf('=');
            if (eq < 0) params.put(seg, "");
            else params.put(seg.substring(0, eq).trim(), unquote(seg.substring(eq + 1).trim()));
        }
        return new MediaType(type, subtype, params);
    }

    public static List<MediaType> parseList(String raw) {
        if (raw == null || raw.isBlank()) return List.of(WILDCARD);
        List<MediaType> out = new ArrayList<>();
        for (String tok : raw.split(",")) {
            if (!tok.isBlank()) out.add(parse(tok));
        }
        return out;
    }

    /** Vrai si {@code a} et {@code b} sont compatibles (wildcard inclus). */
    public static boolean matches(MediaType a, MediaType b) {
        if (a == null || b == null) return false;
        if (!a.isWildcardType() && !b.isWildcardType() && !a.getType().equalsIgnoreCase(b.getType())) return false;
        return a.isWildcardSubtype() || b.isWildcardSubtype()
                || a.getSubtype().equalsIgnoreCase(b.getSubtype());
    }

    /** Prend Accept client + @Produces method, renvoie le best-match (le plus spécifique). */
    public static Optional<MediaType> pickProduced(List<MediaType> accepts,
                                                   List<MediaType> produces) {
        if (produces.isEmpty()) produces = List.of(WILDCARD);
        List<MediaType> sortedAccepts = new ArrayList<>(accepts);
        sortedAccepts.sort(Comparator.comparingDouble(MediaTypes::quality).reversed());
        for (MediaType a : sortedAccepts) {
            MediaType best = null;
            for (MediaType p : produces) {
                if (matches(a, p)) {
                    MediaType candidate = p.isWildcardSubtype() || p.isWildcardType() ? a : p;
                    if (best == null || specificity(candidate) > specificity(best)) best = candidate;
                }
            }
            // §3.8 : un media-type encore wildcard sur SUBTYPE à ce stade
            // (Accept=text/* et @Produces=text/*) est ambigu — non-match.
            // Wildcard-type (*/*) est OK (ressource universelle).
            if (best != null && !best.isWildcardSubtype()) {
                return Optional.of(best);
            }
        }
        return Optional.empty();
    }

    /** Content-Type requête vs @Consumes method. */
    public static boolean consumesMatches(MediaType contentType, List<MediaType> consumes) {
        if (consumes.isEmpty()) return true;
        for (MediaType c : consumes) if (matches(contentType, c)) return true;
        return false;
    }

    public static double quality(MediaType mt) {
        String q = mt.getParameters().get("q");
        if (q == null) return 1.0;
        try { return Double.parseDouble(q); } catch (NumberFormatException e) { return 1.0; }
    }

    private static int specificity(MediaType mt) {
        int s = 0;
        if (!mt.isWildcardType()) s += 2;
        if (!mt.isWildcardSubtype()) s += 1;
        return s;
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    public static List<MediaType> fromSet(java.util.Set<String> raws) {
        if (raws == null || raws.isEmpty()) return List.of();
        List<MediaType> out = new ArrayList<>(raws.size());
        for (String r : raws) out.add(parse(r));
        return Collections.unmodifiableList(out);
    }

    /**
     * Sérialise un {@link MediaType} sans passer par
     * {@link jakarta.ws.rs.ext.RuntimeDelegate} (que Cassini ne fournit
     * pas avant M2e).
     */
    public static String format(MediaType mt) {
        if (mt == null) return "*/*";
        StringBuilder sb = new StringBuilder();
        sb.append(mt.getType()).append('/').append(mt.getSubtype());
        for (Map.Entry<String, String> e : mt.getParameters().entrySet()) {
            sb.append(';').append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }
}
