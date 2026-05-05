package io.vidocq.mansart.data.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Pure-Java JDQL lexer + parser, shared by the compile-time emitter
 * ({@code mansart-data-processor.JdqlParser}) and the runtime proxy
 * ({@link RuntimeRepositoryProxy}).
 *
 * <p>Identical grammar to the M5 compile-time parser; only the AST data carrier types live here
 * — codegen happens elsewhere (compile-time emits Java source; runtime walks the AST directly).
 */
public final class JdqlAst {

    private JdqlAst() {}

    /* ---------- AST ---------- */

    public static final class Stmt {
        public enum Kind { SELECT, COUNT, UPDATE, DELETE, AGGREGATE, PROJECT }
        public Kind kind = Kind.SELECT;
        public Pred where;
        public List<Order> orderBy = new ArrayList<>();
        public List<SetAssign> setAssignments = new ArrayList<>();
        public String aggregateOp;
        public String scalarAttr;
    }

    public record SetAssign(String attr, Expr value) {
        public SetAssign(String attr, ArgRef arg) { this(attr, new ExprArg(arg)); }
    }
    /** M7-24 — RHS of a SET assignment can now be an arithmetic expression
     *  ({@code length + ?1}, {@code y / :yDivisor}, …).
     *  <p>M8-1 — also supports unary scalar functions ({@code UPPER}, {@code LOWER},
     *  {@code LENGTH}, {@code ABS}) and n-ary {@code CONCAT}. */
    public sealed interface Expr permits ExprArg, ExprAttr, ExprBin, ExprFunc {}
    public record ExprArg(ArgRef arg) implements Expr {}
    public record ExprAttr(String attr) implements Expr {}
    public record ExprBin(Expr left, char op, Expr right) implements Expr {}
    /** M8-1 — scalar function call: {@code UPPER(name)}, {@code LENGTH(title)}, {@code CONCAT(a, ', ', b)}. */
    public record ExprFunc(String name, List<Expr> args) implements Expr {
        public ExprFunc { args = List.copyOf(args); }
    }
    public sealed interface Pred permits Cmp, FnCmp, IsNull, Between, FnBetween, In, FnIn,
                                          FnIsNull, And, Or, Not {}
    public record Cmp(String attr, Op op, ArgRef arg)              implements Pred {}
    /** M8-1 — comparator with a unary scalar function on the LHS: {@code UPPER(attr) op ?}. */
    public record FnCmp(String fn, String attr, Op op, ArgRef arg) implements Pred {}
    /** M8-1 — {@code fn(attr) BETWEEN ? AND ?}. */
    public record FnBetween(String fn, String attr, ArgRef lo, ArgRef hi) implements Pred {}
    /** M8-1 — {@code fn(attr) IN (...)}. */
    public record FnIn(String fn, String attr, List<ArgRef> args, boolean collection) implements Pred {}
    /** M8-1 — {@code fn(attr) IS [NOT] NULL}. */
    public record FnIsNull(String fn, String attr, boolean negated) implements Pred {}
    public record IsNull(String attr, boolean negated)             implements Pred {}
    public record Between(String attr, ArgRef lo, ArgRef hi)       implements Pred {}
    public record In(String attr, List<ArgRef> args, boolean collection) implements Pred {}
    public record And(List<Pred> children)                         implements Pred {}
    public record Or(List<Pred> children)                          implements Pred {}
    public record Not(Pred child)                                  implements Pred {}
    public record Order(String attr, boolean asc) {}

    public static final class ArgRef {
        public String named;
        public int positional;
        public Object literal;          // M7-5c — boolean / number / String / enum FQN literal
        public boolean isLiteral;
        public ArgRef(String n) { named = n; }
        public ArgRef(int p) { positional = p; }
        private ArgRef() {}
        public static ArgRef ofLiteral(Object value) {
            ArgRef r = new ArgRef(); r.literal = value; r.isLiteral = true; return r;
        }
        public boolean isNamed() { return named != null; }
    }
    public enum Op { EQ, NE, LT, LTE, GT, GTE, LIKE }

    public static final class ParseException extends RuntimeException {
        public ParseException(String m) { super(m); }
    }

    /* ---------- public parse entry ---------- */

    public static Stmt parse(String jdql, Set<String> attributeNames, String entitySimpleName) {
        return new Parser(new Lexer(jdql), attributeNames, entitySimpleName).parseStmt();
    }

    /* ---------- Lexer ---------- */

    private enum Tk { IDENT, KW, EQ, NE, LT, LTE, GT, GTE, LPAREN, RPAREN, COMMA, NAMED, POS, STAR,
                       PLUS, MINUS, SLASH,
                       NUM_LIT, STR_LIT, BOOL_LIT, EOF }
    private record Token(Tk kind, String text) {}

    private static final class Lexer {
        private final String src;
        private int pos;
        private Token lookahead;
        Lexer(String src) { this.src = src; }
        Token peek() { if (lookahead == null) lookahead = scan(); return lookahead; }
        Token consume() { Token t = peek(); lookahead = null; return t; }
        private void skipWs() { while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++; }
        private Token scan() {
            skipWs();
            if (pos >= src.length()) return new Token(Tk.EOF, "");
            char c = src.charAt(pos);
            switch (c) {
                case '(': pos++; return new Token(Tk.LPAREN, "(");
                case ')': pos++; return new Token(Tk.RPAREN, ")");
                case ',': pos++; return new Token(Tk.COMMA, ",");
                case '*': pos++; return new Token(Tk.STAR, "*");
                case '+': pos++; return new Token(Tk.PLUS, "+");
                case '/': pos++; return new Token(Tk.SLASH, "/");
                case '-':
                    pos++;
                    return new Token(Tk.MINUS, "-");
                case '=': pos++; return new Token(Tk.EQ, "=");
                case '<':
                    pos++;
                    if (pos < src.length() && src.charAt(pos) == '=') { pos++; return new Token(Tk.LTE, "<="); }
                    if (pos < src.length() && src.charAt(pos) == '>') { pos++; return new Token(Tk.NE, "<>"); }
                    return new Token(Tk.LT, "<");
                case '>':
                    pos++;
                    if (pos < src.length() && src.charAt(pos) == '=') { pos++; return new Token(Tk.GTE, ">="); }
                    return new Token(Tk.GT, ">");
                case '!':
                    pos++;
                    if (pos < src.length() && src.charAt(pos) == '=') { pos++; return new Token(Tk.NE, "!="); }
                    throw new ParseException("Unexpected '!'");
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
                default:
                    if (Character.isDigit(c)) {
                        int s = pos;
                        while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) pos++;
                        // JDQL accepts the Java numeric suffixes l/L (long), f/F (float), d/D (double).
                        // Strip them for parsing — the AST stores Long / Double values.
                        int end = pos;
                        if (pos < src.length()) {
                            char sfx = src.charAt(pos);
                            if (sfx == 'l' || sfx == 'L' || sfx == 'f' || sfx == 'F' || sfx == 'd' || sfx == 'D') pos++;
                        }
                        return new Token(Tk.NUM_LIT, src.substring(s, end));
                    }
                    if (c == '\'') {
                        pos++;
                        StringBuilder text = new StringBuilder();
                        while (pos < src.length()) {
                            char ch = src.charAt(pos);
                            if (ch == '\'') {
                                // SQL-style escape: '' inside a string literal stands for one '
                                if (pos + 1 < src.length() && src.charAt(pos + 1) == '\'') {
                                    text.append('\''); pos += 2; continue;
                                }
                                pos++;
                                return new Token(Tk.STR_LIT, text.toString());
                            }
                            text.append(ch); pos++;
                        }
                        throw new ParseException("Unterminated string literal");
                    }
                    if (Character.isLetter(c) || c == '_') {
                        int s = pos;
                        while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos))
                                || src.charAt(pos) == '_' || src.charAt(pos) == '.'))
                            pos++;
                        String w = src.substring(s, pos);
                        if (w.equalsIgnoreCase("true") || w.equalsIgnoreCase("false")) {
                            return new Token(Tk.BOOL_LIT, w.toLowerCase());
                        }
                        if (isKeyword(w) && w.indexOf('.') < 0) return new Token(Tk.KW, w.toUpperCase());
                        return new Token(Tk.IDENT, w);
                    }
                    throw new ParseException("Unexpected '" + c + "' at " + pos);
            }
        }
        private static boolean isKeyword(String w) {
            // M8-1 — scalar function names (UPPER/LOWER/LENGTH/ABS/CONCAT) are NOT registered
            // as keywords here. Doing so would shadow homonym attribute names (notably the
            // TCK uses an entity attribute called {@code length}). The parser detects them
            // contextually as IDENT followed by '('.
            return switch (w.toUpperCase()) {
                case "FROM", "WHERE", "ORDER", "BY", "AND", "OR", "NOT",
                     "IS", "NULL", "BETWEEN", "LIKE", "ASC", "DESC",
                     "SELECT", "UPDATE", "DELETE", "SET", "IN", "COUNT", "THIS",
                     "SUM", "AVG", "MIN", "MAX" -> true;
                default -> false;
            };
        }
    }

    /* ---------- Parser ---------- */

    private static final class Parser {
        private final Lexer lex;
        private final Set<String> attrNames;
        private final String entityName;
        Parser(Lexer l, Set<String> a, String e) { lex = l; attrNames = a; entityName = e; }

        Stmt parseStmt() {
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
                } else if (peekAggregate()) {
                    Token agg = lex.consume();
                    if (lex.peek().kind != Tk.LPAREN) throw new ParseException("Expected '('");
                    lex.consume();
                    String attr = expectAttr();
                    if (lex.peek().kind != Tk.RPAREN) throw new ParseException("Expected ')'");
                    lex.consume();
                    s.kind = Stmt.Kind.AGGREGATE;
                    s.aggregateOp = agg.text;
                    s.scalarAttr = attr;
                } else if (lex.peek().kind == Tk.IDENT) {
                    String attr = expectAttr();
                    s.kind = Stmt.Kind.PROJECT;
                    s.scalarAttr = attr;
                } else {
                    throw new ParseException("Unexpected token after SELECT: " + lex.peek().text);
                }
            }
            if (peekKw("UPDATE")) {
                lex.consume();
                if (lex.peek().kind == Tk.IDENT) {
                    String t = expectIdent();
                    if (!t.equals(entityName)) throw new ParseException("UPDATE " + t + " ≠ " + entityName);
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
                if (peekKw("FROM")) {
                    lex.consume();
                    if (lex.peek().kind == Tk.IDENT) {
                        String t = expectIdent();
                        if (!t.equals(entityName)) throw new ParseException("DELETE FROM " + t + " ≠ " + entityName);
                    }
                }
                s.kind = Stmt.Kind.DELETE;
                if (peekKw("WHERE")) { lex.consume(); s.where = parseExpr(); }
                expectEof();
                return s;
            }
            // Jakarta Data 1.0 — FROM is optional. The implicit entity is the one bound to the
            // repository.  If the query starts with WHERE / ORDER BY (or is just "WHERE …" after
            // a SELECT prefix) the entity is inferred.
            if (peekKw("FROM")) {
                lex.consume();
                String from = expectIdent();
                if (!from.equals(entityName)) throw new ParseException("FROM " + from + " ≠ " + entityName);
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

        Order parseOrder() {
            String attr = expectAttr();
            boolean asc = true;
            if (peekKw("ASC")) lex.consume();
            else if (peekKw("DESC")) { lex.consume(); asc = false; }
            return new Order(attr, asc);
        }

        SetAssign parseSetAssign() {
            String attr = expectAttr();
            if (lex.peek().kind != Tk.EQ) throw new ParseException("Expected '=' in SET");
            lex.consume();
            return new SetAssign(attr, parseValueExpr());
        }

        /**
         * M7-24 — RHS of a SET assignment. Supports binary +, -, *, / over attribute references
         * and {@link ArgRef}s (named/positional/literal). Left-associative; + and - bind looser
         * than * and /. Sufficient for the TCK queries
         * {@code length = length + ?1, width = width - ?1, height = height * ?2}.
         */
        Expr parseValueExpr() { return parseAddSub(); }

        Expr parseAddSub() {
            Expr l = parseMulDiv();
            while (lex.peek().kind == Tk.PLUS || lex.peek().kind == Tk.MINUS) {
                char op = lex.consume().text.charAt(0);
                Expr r = parseMulDiv();
                l = new ExprBin(l, op, r);
            }
            return l;
        }

        Expr parseMulDiv() {
            Expr l = parseValueAtom();
            while (lex.peek().kind == Tk.STAR || lex.peek().kind == Tk.SLASH) {
                char op = lex.consume().text.charAt(0);
                Expr r = parseValueAtom();
                l = new ExprBin(l, op, r);
            }
            return l;
        }

        Expr parseValueAtom() {
            Token t = lex.peek();
            if (t.kind == Tk.LPAREN) {
                lex.consume();
                Expr e = parseValueExpr();
                if (lex.peek().kind != Tk.RPAREN) throw new ParseException("Expected ')'");
                lex.consume();
                return e;
            }
            if (t.kind == Tk.IDENT) {
                // M8-1 — IDENT followed by '(' is a scalar function call (UPPER/LOWER/LENGTH/ABS/CONCAT).
                // Otherwise it's an attribute reference. We detect contextually rather than
                // promoting these names to keywords, because TCK entities use them as attribute
                // names too (e.g. Box.length).
                String name = lex.consume().text;
                String upper = name.toUpperCase();
                if (lex.peek().kind == Tk.LPAREN && isScalarFn(upper)) {
                    lex.consume();
                    List<Expr> args = new ArrayList<>();
                    args.add(parseValueExpr());
                    while (lex.peek().kind == Tk.COMMA) { lex.consume(); args.add(parseValueExpr()); }
                    if (lex.peek().kind != Tk.RPAREN) throw new ParseException("Expected ')' after " + upper + "(...)");
                    lex.consume();
                    checkFnArity(upper, args.size());
                    return new ExprFunc(upper, args);
                }
                if (!attrNames.contains(name)) {
                    throw new ParseException("Unknown attribute '" + name + "' in SET expression");
                }
                return new ExprAttr(name);
            }
            // Otherwise it's an ArgRef (named/positional/literal/string/bool/number)
            return new ExprArg(parseArg());
        }

        private static boolean isScalarFn(String upper) {
            return switch (upper) { case "UPPER", "LOWER", "LENGTH", "ABS", "CONCAT" -> true; default -> false; };
        }

        private static boolean isUnaryFn(String upper) {
            return switch (upper) { case "UPPER", "LOWER", "LENGTH", "ABS" -> true; default -> false; };
        }

        private static void checkFnArity(String fn, int n) {
            switch (fn) {
                case "UPPER", "LOWER", "LENGTH", "ABS" -> {
                    if (n != 1) throw new ParseException(fn + " expects 1 argument, got " + n);
                }
                case "CONCAT" -> {
                    if (n < 2) throw new ParseException("CONCAT expects 2+ arguments, got " + n);
                }
                default -> throw new ParseException("Unknown scalar function: " + fn);
            }
        }

        Pred parseExpr() { return parseOr(); }

        Pred parseOr() {
            Pred l = parseAnd();
            while (peekKw("OR")) {
                lex.consume();
                Pred r = parseAnd();
                if (l instanceof Or o) {
                    List<Pred> all = new ArrayList<>(o.children); all.add(r); l = new Or(all);
                } else l = new Or(List.of(l, r));
            }
            return l;
        }

        Pred parseAnd() {
            Pred l = parseUnary();
            while (peekKw("AND")) {
                lex.consume();
                Pred r = parseUnary();
                if (l instanceof And a) {
                    List<Pred> all = new ArrayList<>(a.children); all.add(r); l = new And(all);
                } else l = new And(List.of(l, r));
            }
            return l;
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
            // M8-1 — LHS may be a unary scalar function on an attribute: UPPER(name) = ?, …
            // We detect this contextually: an IDENT whose name matches a scalar function and
            // is immediately followed by '(' — this avoids shadowing entity attributes that
            // happen to be named "length" etc.
            if (lex.peek().kind == Tk.IDENT) {
                Token saved = lex.peek();
                String upper = saved.text.toUpperCase();
                if (isUnaryFn(upper)) {
                    // We need to look one further — but the lexer is single-token lookahead. Use a
                    // throw-away consume sequence guarded by attribute fallback.
                    lex.consume();
                    if (lex.peek().kind == Tk.LPAREN) {
                        lex.consume();
                        String attr = expectAttr();
                        if (lex.peek().kind != Tk.RPAREN) throw new ParseException("Expected ')' after " + upper + "(attr)");
                        lex.consume();
                        return parseFnRhs(upper, attr);
                    }
                    // Not a function call — must be an attribute named "length" / "upper" etc.
                    if (!attrNames.contains(saved.text)) {
                        throw new ParseException("Unknown attribute '" + saved.text + "' on " + entityName);
                    }
                    return parseRhs(saved.text);
                }
            }
            String attr = expectAttr();
            return parseRhs(attr);
        }

        private Pred parseRhs(String attr) {
            if (peekKw("IS")) {
                lex.consume();
                boolean negated = false;
                if (peekKw("NOT")) { lex.consume(); negated = true; }
                expectKw("NULL");
                return new IsNull(attr, negated);
            }
            boolean infixNot = consumeInfixNot(attr);
            if (peekKw("BETWEEN")) {
                lex.consume();
                ArgRef lo = parseArg();
                expectKw("AND");
                ArgRef hi = parseArg();
                Pred p = new Between(attr, lo, hi);
                return infixNot ? new Not(p) : p;
            }
            if (peekKw("LIKE")) {
                lex.consume();
                Pred p = new Cmp(attr, Op.LIKE, parseArg());
                return infixNot ? new Not(p) : p;
            }
            if (peekKw("IN")) {
                lex.consume();
                return wrapNot(parseInTail(false, attr, null), infixNot);
            }
            Op op = expectCmpOp(attr);
            return new Cmp(attr, op, parseArg());
        }

        /** M8-1 — RHS parser for {@code fn(attr) ...}. Mirrors {@link #parseRhs} but emits Fn* preds. */
        private Pred parseFnRhs(String fn, String attr) {
            if (peekKw("IS")) {
                lex.consume();
                boolean negated = false;
                if (peekKw("NOT")) { lex.consume(); negated = true; }
                expectKw("NULL");
                return new FnIsNull(fn, attr, negated);
            }
            boolean infixNot = consumeInfixNot(fn + "(" + attr + ")");
            if (peekKw("BETWEEN")) {
                lex.consume();
                ArgRef lo = parseArg();
                expectKw("AND");
                ArgRef hi = parseArg();
                Pred p = new FnBetween(fn, attr, lo, hi);
                return infixNot ? new Not(p) : p;
            }
            if (peekKw("LIKE")) {
                lex.consume();
                Pred p = new FnCmp(fn, attr, Op.LIKE, parseArg());
                return infixNot ? new Not(p) : p;
            }
            if (peekKw("IN")) {
                lex.consume();
                return wrapNot(parseInTail(true, attr, fn), infixNot);
            }
            Op op = expectCmpOp(fn + "(" + attr + ")");
            return new FnCmp(fn, attr, op, parseArg());
        }

        private boolean consumeInfixNot(String label) {
            if (!peekKw("NOT")) return false;
            Token saved = lex.peek();
            lex.consume();
            if (peekKw("BETWEEN") || peekKw("LIKE") || peekKw("IN")) return true;
            throw new ParseException("Unexpected NOT after '" + label + "': " + saved.text);
        }

        private Pred parseInTail(boolean withFn, String attr, String fn) {
            if (lex.peek().kind == Tk.LPAREN) {
                lex.consume();
                List<ArgRef> elems = new ArrayList<>();
                elems.add(parseArg());
                while (lex.peek().kind == Tk.COMMA) { lex.consume(); elems.add(parseArg()); }
                if (lex.peek().kind != Tk.RPAREN) throw new ParseException("Expected ')'");
                lex.consume();
                return withFn ? new FnIn(fn, attr, elems, false) : new In(attr, elems, false);
            }
            ArgRef one = parseArg();
            return withFn ? new FnIn(fn, attr, List.of(one), true) : new In(attr, List.of(one), true);
        }

        private Op expectCmpOp(String label) {
            Op op = switch (lex.peek().kind) {
                case EQ -> Op.EQ; case NE -> Op.NE;
                case LT -> Op.LT; case LTE -> Op.LTE;
                case GT -> Op.GT; case GTE -> Op.GTE;
                default -> throw new ParseException("Expected operator after '" + label + "': " + lex.peek().text);
            };
            lex.consume();
            return op;
        }

        private static Pred wrapNot(Pred p, boolean infixNot) {
            return infixNot ? new Not(p) : p;
        }

        ArgRef parseArg() {
            Token t = lex.consume();
            return switch (t.kind) {
                case NAMED    -> new ArgRef(t.text);
                case POS      -> new ArgRef(Integer.parseInt(t.text));
                case NUM_LIT  -> ArgRef.ofLiteral(parseNumber(t.text));
                case STR_LIT  -> ArgRef.ofLiteral(t.text);
                case BOOL_LIT -> ArgRef.ofLiteral(Boolean.parseBoolean(t.text));
                case IDENT    -> ArgRef.ofLiteral(t.text);   // enum FQN like pkg.Enum.VAL
                default       -> throw new ParseException("Expected literal, :name, or ?N: " + t.text);
            };
        }

        private static Object parseNumber(String s) {
            if (s.contains(".")) return Double.parseDouble(s);
            try { return Integer.parseInt(s); }
            catch (NumberFormatException e) { return Long.parseLong(s); }
        }

        private boolean peekKw(String kw)    { Token t = lex.peek(); return t.kind == Tk.KW && t.text.equals(kw); }
        private boolean peekAggregate() {
            Token t = lex.peek();
            return t.kind == Tk.KW && switch (t.text) { case "SUM", "AVG", "MIN", "MAX" -> true; default -> false; };
        }
        private String expectAttr() {
            Token t = lex.consume();
            if (t.kind != Tk.IDENT) throw new ParseException("Expected attribute: " + t.text);
            if (!attrNames.contains(t.text)) throw new ParseException("Unknown attribute '" + t.text + "' on " + entityName);
            return t.text;
        }
        private String expectIdent() {
            Token t = lex.consume();
            if (t.kind != Tk.IDENT) throw new ParseException("Expected identifier: " + t.text);
            return t.text;
        }
        private void expectKw(String kw) {
            Token t = lex.consume();
            if (t.kind != Tk.KW || !t.text.equals(kw)) throw new ParseException("Expected " + kw + ": " + t.text);
        }
        private void expectEof() {
            if (lex.peek().kind != Tk.EOF) throw new ParseException("Trailing tokens: " + lex.peek().text);
        }
    }
}
