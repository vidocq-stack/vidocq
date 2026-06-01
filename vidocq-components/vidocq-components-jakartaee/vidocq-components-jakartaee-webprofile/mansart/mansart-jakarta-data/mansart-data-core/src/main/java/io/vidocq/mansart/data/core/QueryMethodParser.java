package io.vidocq.mansart.data.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Decodes a Spring-Data-flavoured method name (subset) into a {@link QueryDescriptor}.
 *
 * <p>Supported forms (M3b-1):
 * <ul>
 *   <li>{@code findBy<Attr>}, {@code findBy<Attr>And<Attr2>}, {@code findBy<Attr>Or<Attr2>}</li>
 *   <li>Comparator suffixes: (none = EQ), {@code Like}, {@code Between}, {@code In},
 *       {@code GreaterThan}/{@code GreaterThanEqual}, {@code LessThan}/{@code LessThanEqual},
 *       {@code IsNull}, {@code IsNotNull}, {@code Not}</li>
 *   <li>{@code countBy...}, {@code existsBy...}, {@code deleteBy...}, {@code findFirst...}</li>
 *   <li>Optional {@code OrderBy<Attr>[Asc|Desc]} suffix (multiple attributes allowed).</li>
 * </ul>
 */
public final class QueryMethodParser {

    private QueryMethodParser() {}

    public enum Operation { FIND, FIND_ONE, COUNT, EXISTS, DELETE }
    public enum Combinator { AND, OR }
    public enum Comparator { EQ, NOT_EQ, LIKE, BETWEEN, IN, GT, GTE, LT, LTE, IS_NULL, IS_NOT_NULL,
                              CONTAINS, STARTS_WITH, ENDS_WITH, TRUE, FALSE, EMPTY, NOT_EMPTY }

    public record QueryDescriptor(
            Operation op,
            List<Predicate> predicates,
            Combinator combinator,
            List<Order> orderBy
    ) {}

    public record Predicate(String attribute, Comparator comparator, boolean ignoreCase, boolean negated) {
        public Predicate(String attribute, Comparator comparator) {
            this(attribute, comparator, false, false);
        }
        public int boundParams() {
            return switch (comparator) {
                case BETWEEN                                -> 2;
                case IS_NULL, IS_NOT_NULL,
                     TRUE, FALSE, EMPTY, NOT_EMPTY         -> 0;
                case IN                                     -> -1;
                default                                     -> 1;
            };
        }
    }

    public record Order(String attribute, boolean asc) {}

    public static QueryDescriptor parse(String methodName, Set<String> attributeNames) {
        return parse(methodName, attributeNames, new int[]{0});
    }

    /**
     * M8-3i — extension point: callers can plug a path resolver to recognise relation traversal
     * in method names. Given a CamelCase chunk and the current attribute set, the resolver may
     * return a dotted path (e.g. {@code "author.name"}) when the chunk decomposes through known
     * relations. Returning {@code null} falls back to flat resolution. The default implementation
     * is flat-only — used by the runtime path which has no cross-entity registry.
     */
    @FunctionalInterface
    public interface PathResolver {
        /** @param camelChunk the raw method-name chunk (e.g. "AuthorName"); {@code null}/empty allowed */
        String resolve(String camelChunk, Set<String> rootAttrs);
    }

    public static final PathResolver FLAT_ONLY = (chunk, attrs) -> null;

    public static QueryDescriptor parse(String methodName, Set<String> attributeNames,
                                        PathResolver pathResolver) {
        return parse(methodName, attributeNames, new int[]{0}, pathResolver);
    }

    /**
     * M7-8 push — also accepts {@code findFirst<N>By} where {@code <N>} is a positive integer.
     * The N is written into {@code limitOut[0]} so the caller can apply a LIMIT to the query.
     */
    public static QueryDescriptor parse(String methodName, Set<String> attributeNames, int[] limitOut) {
        return parse(methodName, attributeNames, limitOut, FLAT_ONLY);
    }

    public static QueryDescriptor parse(String methodName, Set<String> attributeNames, int[] limitOut,
                                        PathResolver pathResolver) {
        Operation op;
        String rest;
        // findFirst<N>By — N is an inline numeric limit (Jakarta Data 1.0).
        if (methodName.startsWith("findFirst")) {
            int i = "findFirst".length();
            int j = i;
            while (j < methodName.length() && Character.isDigit(methodName.charAt(j))) j++;
            if (j > i && methodName.startsWith("By", j)) {
                limitOut[0] = Integer.parseInt(methodName.substring(i, j));
                methodName = "findBy" + methodName.substring(j + 2);
                // fall through with rewritten method name
            }
        }
        if      (startsWith(methodName, "findFirstBy")) { op = Operation.FIND_ONE; rest = methodName.substring("findFirstBy".length()); }
        else if (startsWith(methodName, "findOneBy"))   { op = Operation.FIND_ONE; rest = methodName.substring("findOneBy".length()); }
        else if (startsWith(methodName, "findAllBy"))   { op = Operation.FIND;     rest = methodName.substring("findAllBy".length()); }
        else if (startsWith(methodName, "findBy"))      { op = Operation.FIND;     rest = methodName.substring("findBy".length()); }
        else if (startsWith(methodName, "countBy"))     { op = Operation.COUNT;    rest = methodName.substring("countBy".length()); }
        else if (startsWith(methodName, "existsBy"))    { op = Operation.EXISTS;   rest = methodName.substring("existsBy".length()); }
        else if (startsWith(methodName, "deleteBy"))    { op = Operation.DELETE;   rest = methodName.substring("deleteBy".length()); }
        else if (startsWith(methodName, "removeBy"))    { op = Operation.DELETE;   rest = methodName.substring("removeBy".length()); }
        // Jakarta Data 1.0 — methods with no By clause: countAll, deleteAll, findAll
        // (find/delete/count/exists with empty predicate set, optional OrderBy / FirstN suffix)
        else if (methodName.equals("countAll"))         { op = Operation.COUNT;  rest = ""; }
        else if (methodName.equals("deleteAll"))        { op = Operation.DELETE; rest = ""; }
        else if (methodName.equals("removeAll"))        { op = Operation.DELETE; rest = ""; }
        else                                            return null;

        // Split off OrderBy clause
        List<Order> orders = List.of();
        int orderIdx = rest.indexOf("OrderBy");
        if (orderIdx >= 0) {
            String orderClause = rest.substring(orderIdx + "OrderBy".length());
            rest = rest.substring(0, orderIdx);
            orders = parseOrders(orderClause, attributeNames, pathResolver);
        }

        // Determine combinator (And vs Or). If both present, And wins for splitting; documented limitation.
        Combinator combinator;
        String[] parts;
        if (rest.isEmpty()) {
            // findAllByOrderBy… or findAllBy with only an ORDER BY clause — no predicates.
            return new QueryDescriptor(op, List.of(), Combinator.AND, orders);
        }
        if (containsToken(rest, "And", attributeNames, pathResolver)) {
            combinator = Combinator.AND;
            parts = splitOnToken(rest, "And", attributeNames, pathResolver);
        } else if (containsToken(rest, "Or", attributeNames, pathResolver)) {
            combinator = Combinator.OR;
            parts = splitOnToken(rest, "Or", attributeNames, pathResolver);
        } else {
            combinator = Combinator.AND;
            parts = new String[] { rest };
        }

        List<Predicate> preds = new ArrayList<>(parts.length);
        for (String part : parts) {
            Predicate p = parsePredicate(part, attributeNames, pathResolver);
            if (p == null) return null; // invalid attribute → caller emits compile error
            preds.add(p);
        }

        return new QueryDescriptor(op, preds, combinator, orders);
    }

    /** {@code parsePredicate("AgeBetween")} → {@code (age, BETWEEN)}. */
    private static Predicate parsePredicate(String token, Set<String> attributeNames, PathResolver pathResolver) {
        // M7-8 — recognise "<Attr>NotNull" as IS_NOT_NULL directly: otherwise the IS_NULL suffix
        // matcher would steal it and the leftover "<Attr>Not" would fail attribute lookup.
        if (token.endsWith("NotNull")) {
            String attr = matchAttributeOrPath(token.substring(0, token.length() - "NotNull".length()),
                    attributeNames, pathResolver);
            if (attr != null) return new Predicate(attr, Comparator.IS_NOT_NULL, false, false);
        }
        // M7-8 — strip "IgnoreCase" suffix or mid-name infix; then "Not" infix before the comparator.
        boolean ignoreCase = false;
        int icIdx = token.indexOf("IgnoreCase");
        if (icIdx >= 0) {
            ignoreCase = true;
            token = token.substring(0, icIdx) + token.substring(icIdx + "IgnoreCase".length());
        }
        // Try comparator suffixes longest first
        for (Suffix s : Suffix.byLengthDesc()) {
            if (token.endsWith(s.suffix)) {
                String attrPart = token.substring(0, token.length() - s.suffix.length());
                // Allow "<attr>Not<Suffix>" — e.g. NameNotLike, AgeNotBetween, IdNotIn.
                boolean negated = false;
                if (attrPart.endsWith("Not")) {
                    String maybeAttr = attrPart.substring(0, attrPart.length() - 3);
                    String matched = matchAttributeOrPath(maybeAttr, attributeNames, pathResolver);
                    if (matched != null) {
                        negated = true;
                        attrPart = maybeAttr;
                    }
                }
                String attr = matchAttributeOrPath(attrPart, attributeNames, pathResolver);
                if (attr != null) return new Predicate(attr, s.comparator, ignoreCase, negated);
            }
        }
        // No suffix — implicit EQ
        String attr = matchAttributeOrPath(token, attributeNames, pathResolver);
        return attr == null ? null : new Predicate(attr, Comparator.EQ, ignoreCase, false);
    }

    /** M8-3i — flat match first, then defer to the {@link PathResolver} for cross-relation paths. */
    private static String matchAttributeOrPath(String camelChunk, Set<String> attributeNames,
                                               PathResolver pathResolver) {
        if (camelChunk.isEmpty()) return null;
        String lcFirst = Character.toLowerCase(camelChunk.charAt(0)) + camelChunk.substring(1);
        if (attributeNames.contains(lcFirst)) return lcFirst;
        return pathResolver.resolve(camelChunk, attributeNames);
    }

    /** Convert {@code "Name"} → {@code "name"} if "name" is in {@code attributeNames}. Flat-only. */
    private static String matchAttribute(String camelChunk, Set<String> attributeNames) {
        return matchAttributeOrPath(camelChunk, attributeNames, FLAT_ONLY);
    }

    private static List<Order> parseOrders(String clause, Set<String> attributeNames, PathResolver pathResolver) {
        // OrderBy clause: NameAsc, AgeDesc, NameAscAgeDesc, … (or just Name with implicit Asc).
        // Eat one attribute at a time from the LEFT, greedy on the largest matching prefix,
        // then consume optional Asc/Desc.
        List<Order> orders = new ArrayList<>();
        String s = clause;
        while (!s.isEmpty()) {
            String matchedAttr = null;
            int matchedEnd = -1;
            for (int end = s.length(); end > 0; end--) {
                String attr = matchAttributeOrPath(s.substring(0, end), attributeNames, pathResolver);
                if (attr != null) { matchedAttr = attr; matchedEnd = end; break; }
            }
            if (matchedAttr == null) break;
            String tail = s.substring(matchedEnd);
            boolean asc = true;
            if      (tail.startsWith("Asc"))  { asc = true;  tail = tail.substring(3); }
            else if (tail.startsWith("Desc")) { asc = false; tail = tail.substring(4); }
            orders.add(new Order(matchedAttr, asc));
            s = tail;
        }
        return orders;
    }

    private static boolean containsToken(String s, String token, Set<String> attributeNames,
                                         PathResolver pathResolver) {
        List<String> parts = splitAll(s, token, attributeNames, pathResolver);
        return parts != null && parts.size() >= 2;
    }

    private static String[] splitOnToken(String s, String token, Set<String> attributeNames,
                                         PathResolver pathResolver) {
        List<String> parts = splitAll(s, token, attributeNames, pathResolver);
        return parts == null ? new String[] { s } : parts.toArray(new String[0]);
    }

    /**
     * Decomposes {@code s} into the run of single-predicate chunks separated by {@code token}
     * ({@code "RoomIdAndSeatRowAndReleased"} on {@code "And"} → {@code [RoomId, SeatRow, Released]}).
     * Earlier versions split on only the first valid {@code And}/{@code Or} and returned two chunks,
     * so any 3+ condition chain mis-parsed and fell through to an unsupported-method stub (MANSART-002).
     *
     * <p>Strategy: a chunk that is itself one valid predicate is returned as-is; otherwise take the
     * shortest valid left predicate and recurse on the right, backtracking to a longer left chunk
     * when the remainder cannot be decomposed (so attribute names that embed the token still parse).
     * Returns {@code null} when {@code s} cannot be fully decomposed into valid predicates.</p>
     */
    private static List<String> splitAll(String s, String token, Set<String> attributeNames,
                                         PathResolver pathResolver) {
        if (chunkLooksValid(s, attributeNames, pathResolver)) {
            List<String> single = new ArrayList<>(1);
            single.add(s);
            return single;
        }
        int i = -1;
        while ((i = s.indexOf(token, i + 1)) >= 0) {
            String left = s.substring(0, i);
            if (!chunkLooksValid(left, attributeNames, pathResolver)) {
                continue;
            }
            List<String> rest = splitAll(s.substring(i + token.length()), token, attributeNames, pathResolver);
            if (rest != null) {
                List<String> parts = new ArrayList<>(rest.size() + 1);
                parts.add(left);
                parts.addAll(rest);
                return parts;
            }
        }
        return null;
    }

    private static boolean chunkLooksValid(String chunk, Set<String> attributeNames, PathResolver pathResolver) {
        if (chunk.isEmpty()) return false;
        return parsePredicate(chunk, attributeNames, pathResolver) != null;
    }

    private static boolean startsWith(String s, String prefix) {
        return s.startsWith(prefix) && (s.length() == prefix.length() || Character.isUpperCase(s.charAt(prefix.length())));
    }

    private enum Suffix {
        IS_NOT_NULL ("IsNotNull",        Comparator.IS_NOT_NULL),
        IS_NULL     ("IsNull",           Comparator.IS_NULL),
        GTE         ("GreaterThanEqual", Comparator.GTE),
        LTE         ("LessThanEqual",    Comparator.LTE),
        GT          ("GreaterThan",      Comparator.GT),
        LT          ("LessThan",         Comparator.LT),
        BETWEEN     ("Between",          Comparator.BETWEEN),
        LIKE        ("Like",             Comparator.LIKE),
        STARTS_WITH ("StartsWith",       Comparator.STARTS_WITH),
        ENDS_WITH   ("EndsWith",         Comparator.ENDS_WITH),
        CONTAINS    ("Contains",         Comparator.CONTAINS),
        NOT_EMPTY   ("NotEmpty",         Comparator.NOT_EMPTY),
        EMPTY       ("Empty",            Comparator.EMPTY),
        TRUE        ("True",             Comparator.TRUE),
        FALSE       ("False",            Comparator.FALSE),
        IN          ("In",               Comparator.IN),
        NOT_EQ      ("Not",              Comparator.NOT_EQ);

        final String suffix;
        final Comparator comparator;
        Suffix(String s, Comparator c) { suffix = s; comparator = c; }

        static Suffix[] byLengthDesc() {
            Suffix[] all = values();
            java.util.Arrays.sort(all, java.util.Comparator.comparingInt((Suffix x) -> -x.suffix.length()));
            return all;
        }
    }
}
