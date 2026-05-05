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

    public record SetAssign(String attr, ArgRef arg) {}
    public sealed interface Pred permits Cmp, IsNull, Between, In, And, Or, Not {}
    public record Cmp(String attr, Op op, ArgRef arg)              implements Pred {}
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
        public ArgRef(String n) { named = n; }
        public ArgRef(int p) { positional = p; }
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

    private enum Tk { IDENT, KW, EQ, NE, LT, LTE, GT, GTE, LPAREN, RPAREN, COMMA, NAMED, POS, STAR, EOF }
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
                    if (Character.isLetter(c) || c == '_') {
                        int s = pos;
                        while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_'))
                            pos++;
                        String w = src.substring(s, pos);
                        return isKeyword(w) ? new Token(Tk.KW, w.toUpperCase()) : new Token(Tk.IDENT, w);
                    }
                    throw new ParseException("Unexpected '" + c + "' at " + pos);
            }
        }
        private static boolean isKeyword(String w) {
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
                String t = expectIdent();
                if (!t.equals(entityName)) throw new ParseException("UPDATE " + t + " ≠ " + entityName);
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
                String t = expectIdent();
                if (!t.equals(entityName)) throw new ParseException("DELETE FROM " + t + " ≠ " + entityName);
                s.kind = Stmt.Kind.DELETE;
                if (peekKw("WHERE")) { lex.consume(); s.where = parseExpr(); }
                expectEof();
                return s;
            }
            expectKw("FROM");
            String from = expectIdent();
            if (!from.equals(entityName)) throw new ParseException("FROM " + from + " ≠ " + entityName);
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
            return new SetAssign(attr, parseArg());
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
                if (lex.peek().kind == Tk.LPAREN) {
                    lex.consume();
                    List<ArgRef> elems = new ArrayList<>();
                    elems.add(parseArg());
                    while (lex.peek().kind == Tk.COMMA) { lex.consume(); elems.add(parseArg()); }
                    if (lex.peek().kind != Tk.RPAREN) throw new ParseException("Expected ')'");
                    lex.consume();
                    return new In(attr, elems, false);
                }
                return new In(attr, List.of(parseArg()), true);
            }
            Op op = switch (lex.peek().kind) {
                case EQ -> Op.EQ; case NE -> Op.NE;
                case LT -> Op.LT; case LTE -> Op.LTE;
                case GT -> Op.GT; case GTE -> Op.GTE;
                default -> throw new ParseException("Expected operator after '" + attr + "': " + lex.peek().text);
            };
            lex.consume();
            return new Cmp(attr, op, parseArg());
        }

        ArgRef parseArg() {
            Token t = lex.consume();
            return switch (t.kind) {
                case NAMED -> new ArgRef(t.text);
                case POS   -> new ArgRef(Integer.parseInt(t.text));
                default    -> throw new ParseException("Expected :name or ?N: " + t.text);
            };
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
