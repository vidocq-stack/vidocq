package io.vidocq.mansart.data.processor;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.TypeMirror;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hand-rolled recursive-descent parser for the subset of Jakarta Data Query Language (JDQL)
 * supported in M5-1:
 *
 * <pre>
 *   FROM &lt;EntitySimpleName&gt; [WHERE &lt;expr&gt;] [ORDER BY &lt;orderItem&gt; (, &lt;orderItem&gt;)*]
 *
 *   expr    := orExpr
 *   orExpr  := andExpr ('OR' andExpr)*
 *   andExpr := unary  ('AND' unary)*
 *   unary   := 'NOT' unary | primary
 *   primary := '(' expr ')'
 *            | attr 'IS' 'NOT'? 'NULL'
 *            | attr 'BETWEEN' arg 'AND' arg
 *            | attr ('=' | '&lt;&gt;' | '!=' | '&lt;' | '&lt;=' | '&gt;' | '&gt;=' | 'LIKE') arg
 *
 *   arg     := ':'IDENT | '?'NUMBER
 * </pre>
 *
 * <p>The parser is invoked at APT time, validates attribute names against the entity, and emits
 * a Java statement string that {@link RepositoryWriter} drops into the generated method body.
 */
final class JdqlParser {

    private JdqlParser() {}

    /** Emits the body for a {@code @Query} method. */
    static String generateBody(String jdql, ExecutableElement method, String metamodel,
                               String entitySimpleName, Set<String> attributeNames,
                               TypeMirror returnType, boolean trailingPageRequest,
                               boolean isCursoredPageReturn) {
        Map<String, Integer> nameToIdx = new HashMap<>();
        for (int i = 0; i < method.getParameters().size(); i++) {
            nameToIdx.put(method.getParameters().get(i).getSimpleName().toString(), i);
        }

        Lexer lex = new Lexer(jdql);
        Stmt stmt = new Parser(lex, attributeNames, entitySimpleName).parseStmt();

        return switch (stmt.kind) {
            case SELECT, COUNT -> emitSelect(stmt, method, metamodel, nameToIdx, returnType,
                    trailingPageRequest, isCursoredPageReturn);
            case UPDATE        -> emitUpdate(stmt, method, metamodel, nameToIdx, returnType);
            case DELETE        -> emitDelete(stmt, method, metamodel, nameToIdx, returnType);
        };
    }

    private static String emitSelect(Stmt stmt, ExecutableElement method, String metamodel,
                                     Map<String, Integer> nameToIdx, TypeMirror returnType,
                                     boolean trailingPageRequest, boolean isCursoredPageReturn) {
        StringBuilder sb = new StringBuilder();
        // For IN with a collection arg, the Where AST and args must be built at runtime.
        boolean hasInList = stmt.where != null && containsInList(stmt.where);

        if (hasInList) {
            return emitDynamicInBody(stmt, method, metamodel, nameToIdx, returnType,
                    trailingPageRequest, isCursoredPageReturn);
        }

        sb.append("io.vidocq.mansart.data.dialect.Where _w = ");
        emitPredicate(sb, stmt.where, metamodel);
        sb.append("; ");

        sb.append("io.vidocq.mansart.data.dialect.OrderBy _ob = ");
        emitOrderBy(sb, stmt.orderBy, metamodel);
        sb.append("; ");

        List<ArgRef> argsInOrder = new ArrayList<>();
        if (stmt.where != null) collectArgs(stmt.where, argsInOrder);

        sb.append("java.lang.Object[] _xs = new java.lang.Object[]{");
        for (int i = 0; i < argsInOrder.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(resolveArg(argsInOrder.get(i), nameToIdx, method));
        }
        sb.append("}; ");

        if (stmt.kind == Stmt.Kind.COUNT) {
            sb.append("return runtime.countWhere(").append(metamodel).append(".$MODEL, _w, _xs);");
        } else {
            sb.append(dispatch(returnType, metamodel, trailingPageRequest, isCursoredPageReturn, method));
        }
        return sb.toString();
    }

    private static String emitUpdate(Stmt stmt, ExecutableElement method, String metamodel,
                                     Map<String, Integer> nameToIdx, TypeMirror returnType) {
        StringBuilder sb = new StringBuilder();
        // SET column references
        sb.append("java.util.List<io.vidocq.mansart.data.dialect.Attribute<?, ?>> _attrs = java.util.List.of(");
        for (int i = 0; i < stmt.setAssignments.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(metamodel).append('.').append(stmt.setAssignments.get(i).attr);
        }
        sb.append("); ");

        sb.append("io.vidocq.mansart.data.dialect.Where _w = ");
        emitPredicate(sb, stmt.where, metamodel);
        sb.append("; ");

        // args = SET values (in order) then WHERE args (in render order)
        sb.append("java.lang.Object[] _xs = new java.lang.Object[]{");
        boolean first = true;
        for (SetAssign sa : stmt.setAssignments) {
            if (!first) sb.append(", ");
            sb.append(resolveArg(sa.arg, nameToIdx, method));
            first = false;
        }
        if (stmt.where != null) {
            List<ArgRef> whereArgs = new ArrayList<>();
            collectArgs(stmt.where, whereArgs);
            for (ArgRef a : whereArgs) {
                if (!first) sb.append(", ");
                sb.append(resolveArg(a, nameToIdx, method));
                first = false;
            }
        }
        sb.append("}; ");

        String call = "runtime.executeUpdate(" + metamodel + ".$MODEL, _attrs, _w, _xs)";
        return sb.append(returnFromVoidLongInt(call, returnType)).toString();
    }

    private static String emitDelete(Stmt stmt, ExecutableElement method, String metamodel,
                                     Map<String, Integer> nameToIdx, TypeMirror returnType) {
        StringBuilder sb = new StringBuilder();
        sb.append("io.vidocq.mansart.data.dialect.Where _w = ");
        emitPredicate(sb, stmt.where, metamodel);
        sb.append("; ");

        List<ArgRef> argsInOrder = new ArrayList<>();
        if (stmt.where != null) collectArgs(stmt.where, argsInOrder);

        sb.append("java.lang.Object[] _xs = new java.lang.Object[]{");
        for (int i = 0; i < argsInOrder.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(resolveArg(argsInOrder.get(i), nameToIdx, method));
        }
        sb.append("}; ");

        String call = "runtime.deleteWhere(" + metamodel + ".$MODEL, _w, _xs)";
        return sb.append(returnFromVoidLongInt(call, returnType)).toString();
    }

    private static String returnFromVoidLongInt(String call, TypeMirror returnType) {
        return switch (returnType.getKind()) {
            case VOID -> call + ";";
            case INT  -> "return (int) " + call + ";";
            default   -> "return " + call + ";";
        };
    }

    /** Generates a body that builds Where + args dynamically when an IN takes a Collection. */
    private static String emitDynamicInBody(Stmt stmt, ExecutableElement method, String metamodel,
                                            Map<String, Integer> nameToIdx, TypeMirror returnType,
                                            boolean trailingPageRequest, boolean isCursoredPageReturn) {
        String pkg = "io.vidocq.mansart.data.dialect.Where";
        StringBuilder sb = new StringBuilder();
        sb.append("java.util.List<").append(pkg).append("> _parts = new java.util.ArrayList<>(); ");
        sb.append("java.util.List<java.lang.Object> _args = new java.util.ArrayList<>(); ");

        emitPredicateDynamic(sb, stmt.where, metamodel, nameToIdx, method, "_parts", "_args");

        sb.append(pkg).append(" _w = _parts.size() == 1 ? _parts.get(0) : ").append(pkg)
          .append(".and(_parts.toArray(new ").append(pkg).append("[0])); ");
        sb.append("Object[] _xs = _args.toArray(); ");

        sb.append("io.vidocq.mansart.data.dialect.OrderBy _ob = ");
        emitOrderBy(sb, stmt.orderBy, metamodel);
        sb.append("; ");

        if (stmt.kind == Stmt.Kind.COUNT) {
            sb.append("return runtime.countWhere(").append(metamodel).append(".$MODEL, _w, _xs);");
            return sb.toString();
        }
        sb.append(dispatch(returnType, metamodel, trailingPageRequest, isCursoredPageReturn, method));
        return sb.toString();
    }

    /** Walks the Where AST emitting code that appends to _parts and _args. */
    private static void emitPredicateDynamic(StringBuilder sb, Pred p, String metamodel,
                                             Map<String, Integer> nameToIdx, ExecutableElement m,
                                             String partsVar, String argsVar) {
        String pkg = "io.vidocq.mansart.data.dialect.Where";
        if (p == null) return;
        switch (p) {
            case In in -> {
                String collectionRef = resolveArg(in.arg, nameToIdx, m);
                sb.append("{ java.util.Collection<?> _col = (java.util.Collection<?>) ").append(collectionRef).append("; ");
                sb.append("if (_col.isEmpty()) ").append(emptyInGuard(p)).append(' ');
                sb.append(partsVar).append(".add(new ").append(pkg).append(".In(")
                  .append(metamodel).append('.').append(in.attr).append(", _col.size())); ");
                sb.append("for (Object _v : _col) ").append(argsVar).append(".add(_v); }");
            }
            case Cmp c -> {
                String cls = switch (c.op) {
                    case EQ -> "Eq"; case NE -> "NotEq";
                    case LT -> "Lt"; case LTE -> "Lte";
                    case GT -> "Gt"; case GTE -> "Gte";
                    case LIKE -> "Like";
                };
                sb.append(partsVar).append(".add(new ").append(pkg).append('.').append(cls).append('(')
                  .append(metamodel).append('.').append(c.attr).append(")); ");
                sb.append(argsVar).append(".add(").append(resolveArg(c.arg, nameToIdx, m)).append("); ");
            }
            case IsNull n -> sb.append(partsVar).append(".add(new ").append(pkg).append('.')
                  .append(n.negated ? "IsNotNull" : "IsNull").append('(')
                  .append(metamodel).append('.').append(n.attr).append(")); ");
            case Between b -> {
                sb.append(partsVar).append(".add(new ").append(pkg).append(".Between(")
                  .append(metamodel).append('.').append(b.attr).append(")); ");
                sb.append(argsVar).append(".add(").append(resolveArg(b.lo, nameToIdx, m)).append("); ");
                sb.append(argsVar).append(".add(").append(resolveArg(b.hi, nameToIdx, m)).append("); ");
            }
            case And a -> { for (Pred c : a.children) emitPredicateDynamic(sb, c, metamodel, nameToIdx, m, partsVar, argsVar); }
            case Or  o -> {
                // OR with mixed In is M5-3 — for now reject to avoid silent miswiring.
                throw new ParseException("OR combined with IN(:list) is not yet supported (M5-3).");
            }
            case Not n -> {
                throw new ParseException("NOT combined with IN(:list) is not yet supported (M5-3).");
            }
            default -> throw new IllegalStateException("Unhandled predicate: " + p);
        }
    }

    private static String emptyInGuard(Pred p) {
        // For SELECT/DELETE the natural empty result varies by return type; we conservatively
        // return List.of() / void / 0 — caller-side dispatch is responsible. For dynamic In
        // bodies generated from JDQL SELECTs we return List.of() (caller wraps as needed).
        return "return java.util.Collections.emptyList();";
    }

    private static boolean containsInList(Pred p) {
        return switch (p) {
            case In ignored  -> true;
            case And a       -> a.children.stream().anyMatch(JdqlParser::containsInList);
            case Or o        -> o.children.stream().anyMatch(JdqlParser::containsInList);
            case Not n       -> containsInList(n.child);
            default          -> false;
        };
    }

    private static String resolveArg(ArgRef ref, Map<String, Integer> nameToIdx, ExecutableElement m) {
        int idx;
        if (ref.named != null) {
            Integer mapped = nameToIdx.get(ref.named);
            if (mapped == null) {
                throw new ParseException("@Query references :" + ref.named
                        + " but method " + m.getSimpleName() + " has no parameter with that name. "
                        + "Compile with `-parameters` so APT can see the names, or use ?N.");
            }
            idx = mapped;
        } else {
            idx = ref.positional - 1;
        }
        return "arg" + idx;
    }

    private static String dispatch(TypeMirror returnType, String metamodel,
                                   boolean trailingPageRequest, boolean isCursoredPageReturn,
                                   ExecutableElement m) {
        String rt = returnType.toString();
        if (trailingPageRequest) {
            String runtimeMethod = isCursoredPageReturn ? "queryCursored" : "queryPage";
            int lastIdx = m.getParameters().size() - 1;
            return "return runtime." + runtimeMethod + "(" + metamodel + ".$MODEL, _w, _ob, arg"
                    + lastIdx + ", _xs);";
        }
        if (rt.startsWith("java.util.Optional")) {
            return "return runtime.queryOne(" + metamodel + ".$MODEL, _w, _xs);";
        }
        if (rt.startsWith("java.util.stream.Stream")) {
            return "return runtime.queryList(" + metamodel + ".$MODEL, _w, _ob, _xs).stream();";
        }
        if (rt.startsWith("java.util.List")
                || rt.startsWith("java.util.Collection")
                || rt.startsWith("java.lang.Iterable")) {
            return "return runtime.queryList(" + metamodel + ".$MODEL, _w, _ob, _xs);";
        }
        if (rt.equals("long")) {
            return "return runtime.countWhere(" + metamodel + ".$MODEL, _w, _xs);";
        }
        if (rt.equals("boolean")) {
            return "return runtime.existsWhere(" + metamodel + ".$MODEL, _w, _xs);";
        }
        // Single-entity return → fetch one, throw if absent.
        return "return runtime.queryOne(" + metamodel + ".$MODEL, _w, _xs).orElseThrow("
                + "() -> new io.vidocq.mansart.data.core.MansartDataException(\"@Query returned no result\"));";
    }

    /* ---------- AST ---------- */

    static final class Stmt {
        enum Kind { SELECT, COUNT, UPDATE, DELETE }
        Kind kind = Kind.SELECT;
        Pred where;
        List<Order> orderBy = new ArrayList<>();
        List<SetAssign> setAssignments = new ArrayList<>();
    }
    record SetAssign(String attr, ArgRef arg) {}
    sealed interface Pred permits Cmp, IsNull, Between, In, And, Or, Not {}
    record Cmp(String attr, Op op, ArgRef arg)              implements Pred {}
    record IsNull(String attr, boolean negated)             implements Pred {}
    record Between(String attr, ArgRef lo, ArgRef hi)       implements Pred {}
    record In(String attr, ArgRef arg)                      implements Pred {}
    record And(List<Pred> children)                         implements Pred {}
    record Or(List<Pred> children)                          implements Pred {}
    record Not(Pred child)                                  implements Pred {}
    record Order(String attr, boolean asc) {}
    /** A {@code :name} or {@code ?N} reference inside the JDQL. */
    static final class ArgRef { String named; int positional; ArgRef(String n) { named = n; } ArgRef(int p) { positional = p; } }
    enum Op { EQ, NE, LT, LTE, GT, GTE, LIKE }

    /* ---------- code emission ---------- */

    private static void emitPredicate(StringBuilder sb, Pred p, String metamodel) {
        String pkg = "io.vidocq.mansart.data.dialect.Where";
        if (p == null) { sb.append(pkg).append(".ALWAYS_TRUE"); return; }
        switch (p) {
            case Cmp c -> {
                String cls = switch (c.op) {
                    case EQ -> "Eq"; case NE -> "NotEq";
                    case LT -> "Lt"; case LTE -> "Lte";
                    case GT -> "Gt"; case GTE -> "Gte";
                    case LIKE -> "Like";
                };
                sb.append("new ").append(pkg).append('.').append(cls).append('(')
                  .append(metamodel).append('.').append(c.attr).append(')');
            }
            case IsNull n -> {
                sb.append("new ").append(pkg).append('.').append(n.negated ? "IsNotNull" : "IsNull")
                  .append('(').append(metamodel).append('.').append(n.attr).append(')');
            }
            case Between b -> {
                sb.append("new ").append(pkg).append(".Between(")
                  .append(metamodel).append('.').append(b.attr).append(')');
            }
            case In ignored -> throw new IllegalStateException(
                    "IN predicates are emitted via the dynamic-In path; emitPredicate should not see them.");
            case And a -> emitCombinator(sb, "and", a.children, metamodel);
            case Or  o -> emitCombinator(sb, "or",  o.children, metamodel);
            case Not n -> {
                sb.append("new ").append(pkg).append(".Not(");
                emitPredicate(sb, n.child, metamodel);
                sb.append(')');
            }
        }
    }

    private static void emitCombinator(StringBuilder sb, String op, List<Pred> children, String metamodel) {
        sb.append("io.vidocq.mansart.data.dialect.Where.").append(op).append('(');
        for (int i = 0; i < children.size(); i++) {
            if (i > 0) sb.append(", ");
            emitPredicate(sb, children.get(i), metamodel);
        }
        sb.append(')');
    }

    private static void emitOrderBy(StringBuilder sb, List<Order> orders, String metamodel) {
        if (orders.isEmpty()) { sb.append("io.vidocq.mansart.data.dialect.OrderBy.NONE"); return; }
        sb.append("new io.vidocq.mansart.data.dialect.OrderBy(java.util.List.of(");
        for (int i = 0; i < orders.size(); i++) {
            if (i > 0) sb.append(", ");
            Order o = orders.get(i);
            sb.append("io.vidocq.mansart.data.dialect.OrderBy.Order.")
              .append(o.asc ? "asc" : "desc")
              .append('(').append(metamodel).append('.').append(o.attr).append(')');
        }
        sb.append("))");
    }

    private static void collectArgs(Pred p, List<ArgRef> out) {
        switch (p) {
            case Cmp c       -> out.add(c.arg);
            case Between b   -> { out.add(b.lo); out.add(b.hi); }
            case IsNull ign  -> {}
            case In in       -> out.add(in.arg);   // single arg ref to the collection — dynamic path expands
            case And a       -> { for (Pred c : a.children) collectArgs(c, out); }
            case Or  o       -> { for (Pred c : o.children) collectArgs(c, out); }
            case Not n       -> collectArgs(n.child, out);
        }
    }

    /* ---------- lexer ---------- */

    private enum Tk { IDENT, KW, EQ, NE, LT, LTE, GT, GTE, LPAREN, RPAREN, COMMA, NAMED, POS, STAR, EOF }
    private record Token(Tk kind, String text) {}

    private static final class Lexer {
        private final String src;
        private int pos = 0;
        private Token lookahead;
        Lexer(String src) { this.src = src; }

        Token peek() {
            if (lookahead == null) lookahead = scan();
            return lookahead;
        }
        Token consume() {
            Token t = peek();
            lookahead = null;
            return t;
        }
        private void skipWs() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
        }
        private Token scan() {
            skipWs();
            if (pos >= src.length()) return new Token(Tk.EOF, "");
            char c = src.charAt(pos);
            switch (c) {
                case '(': pos++; return new Token(Tk.LPAREN, "(");
                case ')': pos++; return new Token(Tk.RPAREN, ")");
                case ',': pos++; return new Token(Tk.COMMA, ",");
                case '*': pos++; return new Token(Tk.STAR, "*");
                case '=': pos++; return new Token(Tk.EQ, "=");
                case '<': {
                    pos++;
                    if (pos < src.length() && src.charAt(pos) == '=') { pos++; return new Token(Tk.LTE, "<="); }
                    if (pos < src.length() && src.charAt(pos) == '>') { pos++; return new Token(Tk.NE, "<>"); }
                    return new Token(Tk.LT, "<");
                }
                case '>': {
                    pos++;
                    if (pos < src.length() && src.charAt(pos) == '=') { pos++; return new Token(Tk.GTE, ">="); }
                    return new Token(Tk.GT, ">");
                }
                case '!': {
                    pos++;
                    if (pos < src.length() && src.charAt(pos) == '=') { pos++; return new Token(Tk.NE, "!="); }
                    throw new ParseException("Unexpected '!'");
                }
                case ':': {
                    pos++;
                    int s = pos;
                    while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) pos++;
                    if (s == pos) throw new ParseException("Empty named parameter ':'");
                    return new Token(Tk.NAMED, src.substring(s, pos));
                }
                case '?': {
                    pos++;
                    int s = pos;
                    while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
                    if (s == pos) throw new ParseException("Positional parameter '?' must be followed by digits");
                    return new Token(Tk.POS, src.substring(s, pos));
                }
                default: {
                    if (Character.isLetter(c) || c == '_') {
                        int s = pos;
                        while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_'))
                            pos++;
                        String w = src.substring(s, pos);
                        return isKeyword(w) ? new Token(Tk.KW, w.toUpperCase())
                                            : new Token(Tk.IDENT, w);
                    }
                    throw new ParseException("Unexpected character '" + c + "' at position " + pos);
                }
            }
        }
        private static boolean isKeyword(String w) {
            return switch (w.toUpperCase()) {
                case "FROM", "WHERE", "ORDER", "BY", "AND", "OR", "NOT",
                     "IS", "NULL", "BETWEEN", "LIKE", "ASC", "DESC",
                     "SELECT", "UPDATE", "DELETE", "SET", "IN", "COUNT", "THIS" -> true;
                default -> false;
            };
        }
    }

    /* ---------- parser ---------- */

    private static final class Parser {
        private final Lexer lex;
        private final Set<String> attrNames;
        private final String entityName;

        Parser(Lexer lex, Set<String> attrNames, String entityName) {
            this.lex = lex; this.attrNames = attrNames; this.entityName = entityName;
        }

        Stmt parseStmt() {
            // Optional SELECT clause: SELECT this | SELECT COUNT(*) | SELECT COUNT(this)
            Stmt s = new Stmt();
            if (peekKw("SELECT")) {
                lex.consume();
                if (peekKw("COUNT")) {
                    lex.consume();
                    if (lex.peek().kind != Tk.LPAREN) throw new ParseException("Expected '(' after COUNT");
                    lex.consume();
                    Token inside = lex.consume();
                    boolean ok = inside.kind == Tk.STAR
                            || (inside.kind == Tk.KW && inside.text.equals("THIS"));
                    if (!ok) throw new ParseException("Expected COUNT(*) or COUNT(this), got: " + inside.text);
                    if (lex.peek().kind != Tk.RPAREN) throw new ParseException("Expected ')' after COUNT(...)");
                    lex.consume();
                    s.kind = Stmt.Kind.COUNT;
                } else if (peekKw("THIS")) {
                    lex.consume();
                    // SELECT this — same as default
                } else {
                    throw new ParseException("Only SELECT this and SELECT COUNT(...) are supported in M5-2");
                }
            }
            if (peekKw("UPDATE")) {
                lex.consume();
                String upTarget = expectIdent();
                if (!upTarget.equals(entityName)) {
                    throw new ParseException("@Query UPDATE " + upTarget + " does not match repository entity " + entityName);
                }
                expectKw("SET");
                s.kind = Stmt.Kind.UPDATE;
                s.setAssignments.add(parseSetAssign());
                while (lex.peek().kind == Tk.COMMA) { lex.consume(); s.setAssignments.add(parseSetAssign()); }
                if (peekKw("WHERE")) { lex.consume(); s.where = parseExpr(); }
                expectEof();
                return s;
            }
            if (peekKw("DELETE")) {
                lex.consume();
                expectKw("FROM");
                String delFrom = expectIdent();
                if (!delFrom.equals(entityName)) {
                    throw new ParseException("@Query DELETE FROM " + delFrom + " does not match repository entity " + entityName);
                }
                s.kind = Stmt.Kind.DELETE;
                if (peekKw("WHERE")) { lex.consume(); s.where = parseExpr(); }
                expectEof();
                return s;
            }
            expectKw("FROM");
            String from = expectIdent();
            if (!from.equals(entityName)) {
                throw new ParseException("@Query FROM " + from + " does not match repository entity " + entityName);
            }
            if (peekKw("WHERE")) { lex.consume(); s.where = parseExpr(); }
            if (peekKw("ORDER")) {
                lex.consume(); expectKw("BY");
                s.orderBy.add(parseOrder());
                while (lex.peek().kind == Tk.COMMA) { lex.consume(); s.orderBy.add(parseOrder()); }
            }
            expectEof();
            return s;
        }

        SetAssign parseSetAssign() {
            String attr = expectAttr();
            if (lex.peek().kind != Tk.EQ) throw new ParseException("Expected '=' in SET clause");
            lex.consume();
            return new SetAssign(attr, parseArg());
        }

        private void expectEof() {
            if (lex.peek().kind != Tk.EOF) {
                throw new ParseException("Trailing tokens after end of @Query: " + lex.peek().text);
            }
        }

        Order parseOrder() {
            String attr = expectAttr();
            boolean asc = true;
            if (peekKw("ASC")) { lex.consume(); }
            else if (peekKw("DESC")) { lex.consume(); asc = false; }
            return new Order(attr, asc);
        }

        Pred parseExpr() { return parseOr(); }

        Pred parseOr() {
            Pred left = parseAnd();
            while (peekKw("OR")) {
                lex.consume();
                Pred right = parseAnd();
                if (left instanceof Or o) {
                    List<Pred> all = new ArrayList<>(o.children); all.add(right); left = new Or(all);
                } else { left = new Or(List.of(left, right)); }
            }
            return left;
        }

        Pred parseAnd() {
            Pred left = parseUnary();
            while (peekKw("AND")) {
                lex.consume();
                Pred right = parseUnary();
                if (left instanceof And a) {
                    List<Pred> all = new ArrayList<>(a.children); all.add(right); left = new And(all);
                } else { left = new And(List.of(left, right)); }
            }
            return left;
        }

        Pred parseUnary() {
            if (peekKw("NOT")) { lex.consume(); return new Not(parseUnary()); }
            return parsePrimary();
        }

        Pred parsePrimary() {
            if (lex.peek().kind == Tk.LPAREN) {
                lex.consume();
                Pred p = parseExpr();
                if (lex.peek().kind != Tk.RPAREN) throw new ParseException("Expected ')'");
                lex.consume();
                return p;
            }
            String attr = expectAttr();
            if (peekKw("IS")) {
                lex.consume();
                boolean negated = false;
                if (peekKw("NOT")) { lex.consume(); negated = true; }
                expectKw("NULL");
                return new IsNull(attr, negated);
            }
            if (peekKw("BETWEEN")) {
                lex.consume();
                ArgRef lo = parseArg();
                expectKw("AND");
                ArgRef hi = parseArg();
                return new Between(attr, lo, hi);
            }
            if (peekKw("LIKE")) {
                lex.consume();
                return new Cmp(attr, Op.LIKE, parseArg());
            }
            if (peekKw("IN")) {
                lex.consume();
                // Accept either `attr IN :list` (single Collection arg) or `attr IN (:p1, :p2, …)`.
                // M5-2 supports the single-collection form only; the multi-arg form lands in M5-3.
                if (lex.peek().kind == Tk.LPAREN) {
                    throw new ParseException("attr IN (a, b, …) — multi-element IN literal lands in M5-3; "
                            + "use `attr IN :collectionParam` for now.");
                }
                return new In(attr, parseArg());
            }
            Op op = switch (lex.peek().kind) {
                case EQ  -> Op.EQ;
                case NE  -> Op.NE;
                case LT  -> Op.LT;
                case LTE -> Op.LTE;
                case GT  -> Op.GT;
                case GTE -> Op.GTE;
                default  -> throw new ParseException(
                        "Expected comparison operator after attribute '" + attr + "', got: " + lex.peek().text);
            };
            lex.consume();
            return new Cmp(attr, op, parseArg());
        }

        ArgRef parseArg() {
            Token t = lex.consume();
            return switch (t.kind) {
                case NAMED -> new ArgRef(t.text);
                case POS   -> new ArgRef(Integer.parseInt(t.text));
                default    -> throw new ParseException("Expected :name or ?N, got: " + t.text);
            };
        }

        private String expectAttr() {
            Token t = lex.consume();
            if (t.kind != Tk.IDENT) throw new ParseException("Expected attribute name, got: " + t.text);
            if (!attrNames.contains(t.text)) {
                throw new ParseException("Unknown attribute '" + t.text + "' on entity " + entityName);
            }
            return t.text;
        }
        private String expectIdent() {
            Token t = lex.consume();
            if (t.kind != Tk.IDENT) throw new ParseException("Expected identifier, got: " + t.text);
            return t.text;
        }
        private void expectKw(String kw) {
            Token t = lex.consume();
            if (t.kind != Tk.KW || !t.text.equals(kw)) {
                throw new ParseException("Expected " + kw + ", got: " + t.text);
            }
        }
        private boolean peekKw(String kw) {
            Token t = lex.peek();
            return t.kind == Tk.KW && t.text.equals(kw);
        }
    }

    static final class ParseException extends RuntimeException {
        ParseException(String message) { super(message); }
    }
}
