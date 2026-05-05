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
        Operation op;
        String rest;
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
            orders = parseOrders(orderClause, attributeNames);
        }

        // Determine combinator (And vs Or). If both present, And wins for splitting; documented limitation.
        Combinator combinator;
        String[] parts;
        if (rest.isEmpty()) {
            // findAllByOrderBy… or findAllBy with only an ORDER BY clause — no predicates.
            return new QueryDescriptor(op, List.of(), Combinator.AND, orders);
        }
        if (containsToken(rest, "And", attributeNames)) {
            combinator = Combinator.AND;
            parts = splitOnToken(rest, "And", attributeNames);
        } else if (containsToken(rest, "Or", attributeNames)) {
            combinator = Combinator.OR;
            parts = splitOnToken(rest, "Or", attributeNames);
        } else {
            combinator = Combinator.AND;
            parts = new String[] { rest };
        }

        List<Predicate> preds = new ArrayList<>(parts.length);
        for (String part : parts) {
            Predicate p = parsePredicate(part, attributeNames);
            if (p == null) return null; // invalid attribute → caller emits compile error
            preds.add(p);
        }

        return new QueryDescriptor(op, preds, combinator, orders);
    }

    /** {@code parsePredicate("AgeBetween")} → {@code (age, BETWEEN)}. */
    private static Predicate parsePredicate(String token, Set<String> attributeNames) {
        // M7-8 — strip "IgnoreCase" suffix first, then "Not" infix before the comparator.
        boolean ignoreCase = false;
        if (token.endsWith("IgnoreCase")) {
            ignoreCase = true;
            token = token.substring(0, token.length() - "IgnoreCase".length());
        }
        // Try comparator suffixes longest first
        for (Suffix s : Suffix.byLengthDesc()) {
            if (token.endsWith(s.suffix)) {
                String attrPart = token.substring(0, token.length() - s.suffix.length());
                // Allow "<attr>Not<Suffix>" — e.g. NameNotLike, AgeNotBetween, IdNotIn.
                boolean negated = false;
                if (attrPart.endsWith("Not")) {
                    String maybeAttr = attrPart.substring(0, attrPart.length() - 3);
                    String matched = matchAttribute(maybeAttr, attributeNames);
                    if (matched != null) {
                        negated = true;
                        attrPart = maybeAttr;
                    }
                }
                String attr = matchAttribute(attrPart, attributeNames);
                if (attr != null) return new Predicate(attr, s.comparator, ignoreCase, negated);
            }
        }
        // No suffix — implicit EQ
        String attr = matchAttribute(token, attributeNames);
        return attr == null ? null : new Predicate(attr, Comparator.EQ, ignoreCase, false);
    }

    /** Convert {@code "Name"} → {@code "name"} if "name" is in {@code attributeNames}. */
    private static String matchAttribute(String camelChunk, Set<String> attributeNames) {
        if (camelChunk.isEmpty()) return null;
        String lcFirst = Character.toLowerCase(camelChunk.charAt(0)) + camelChunk.substring(1);
        return attributeNames.contains(lcFirst) ? lcFirst : null;
    }

    private static List<Order> parseOrders(String clause, Set<String> attributeNames) {
        // OrderBy clause: NameAsc, AgeDesc, NameAscAgeDesc, … (or just Name with implicit Asc).
        // Eat one attribute at a time from the LEFT, greedy on the largest matching prefix,
        // then consume optional Asc/Desc.
        List<Order> orders = new ArrayList<>();
        String s = clause;
        while (!s.isEmpty()) {
            String matchedAttr = null;
            int matchedEnd = -1;
            for (int end = s.length(); end > 0; end--) {
                String attr = matchAttribute(s.substring(0, end), attributeNames);
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

    private static boolean containsToken(String s, String token, Set<String> attributeNames) {
        // Find a position where `token` separates two valid attribute chunks
        int i = -1;
        while ((i = s.indexOf(token, i + 1)) >= 0) {
            String left  = s.substring(0, i);
            String right = s.substring(i + token.length());
            if (chunkLooksValid(left, attributeNames) && chunkLooksValid(right, attributeNames)) return true;
        }
        return false;
    }

    private static String[] splitOnToken(String s, String token, Set<String> attributeNames) {
        // First valid split position
        int i = -1;
        while ((i = s.indexOf(token, i + 1)) >= 0) {
            String left  = s.substring(0, i);
            String right = s.substring(i + token.length());
            if (chunkLooksValid(left, attributeNames) && chunkLooksValid(right, attributeNames)) {
                return new String[] { left, right };
            }
        }
        return new String[] { s };
    }

    private static boolean chunkLooksValid(String chunk, Set<String> attributeNames) {
        if (chunk.isEmpty()) return false;
        return parsePredicate(chunk, attributeNames) != null;
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
