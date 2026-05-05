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
            case AGGREGATE     -> emitAggregate(stmt, method, metamodel, nameToIdx, returnType);
            case PROJECT       -> emitProject(stmt, method, metamodel, nameToIdx, returnType);
            case PROJECT_MULTI -> emitProjectMulti(stmt, method, metamodel, nameToIdx, returnType);
            case UPDATE        -> emitUpdate(stmt, method, metamodel, nameToIdx, returnType);
            case DELETE        -> emitDelete(stmt, method, metamodel, nameToIdx, returnType);
        };
    }

    private static String emitAggregate(Stmt stmt, ExecutableElement method, String metamodel,
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

        String boxed = boxedReturnType(returnType);
        boolean isPrimitive = returnType.getKind().isPrimitive();
        sb.append(boxed).append(" _v = runtime.aggregate(").append(metamodel).append(".$MODEL, \"")
          .append(stmt.aggregateOp).append("\", ").append(attrRef(metamodel, stmt.scalarAttr))
          .append(", ").append(boxed).append(".class, _w, _xs); ");
        if (isPrimitive) {
            // Primitive returns can't carry null — substitute a zero of the right type when SUM/MAX
            // returns no rows.
            sb.append("return _v == null ? ").append(zeroLiteralFor(returnType)).append(" : _v;");
        } else {
            sb.append("return _v;");
        }
        return sb.toString();
    }

    private static String emitProject(Stmt stmt, ExecutableElement method, String metamodel,
                                      Map<String, Integer> nameToIdx, TypeMirror returnType) {
        StringBuilder sb = new StringBuilder();
        sb.append("io.vidocq.mansart.data.dialect.Where _w = ");
        emitPredicate(sb, stmt.where, metamodel);
        sb.append("; io.vidocq.mansart.data.dialect.OrderBy _ob = ");
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

        String rt = returnType.toString();
        if (rt.startsWith("java.util.List")
                || rt.startsWith("java.util.Collection")
                || rt.startsWith("java.lang.Iterable")) {
            String elem = projectionElementBoxed(rt);
            sb.append("return runtime.projectColumn(").append(metamodel).append(".$MODEL, ")
              .append(attrRef(metamodel, stmt.scalarAttr))
              .append(", ").append(elem).append(".class, _w, _ob, _xs);");
        } else if (rt.startsWith("java.util.stream.Stream")) {
            String elem = projectionElementBoxed(rt);
            sb.append("return runtime.projectColumn(").append(metamodel).append(".$MODEL, ")
              .append(attrRef(metamodel, stmt.scalarAttr))
              .append(", ").append(elem).append(".class, _w, _ob, _xs).stream();");
        } else if (rt.startsWith("java.util.Optional")) {
            String elem = projectionElementBoxed(rt);
            sb.append("var _list = runtime.projectColumn(").append(metamodel).append(".$MODEL, ")
              .append(attrRef(metamodel, stmt.scalarAttr))
              .append(", ").append(elem).append(".class, _w, _ob, _xs); ");
            sb.append("return _list.isEmpty() ? java.util.Optional.empty() : java.util.Optional.ofNullable(_list.get(0));");
        } else {
            // Single scalar return — fetch first, throw if absent
            String boxed = boxedReturnType(returnType);
            sb.append("var _list = runtime.projectColumn(").append(metamodel).append(".$MODEL, ")
              .append(attrRef(metamodel, stmt.scalarAttr))
              .append(", ").append(boxed).append(".class, _w, _ob, _xs); ");
            sb.append("if (_list.isEmpty()) throw new io.vidocq.mansart.data.core.MansartDataException("
                    + "\"Projection returned no result\"); return _list.get(0);");
        }
        return sb.toString();
    }

    /**
     * M8-2 — emit a multi-column projection. Builds the {@code List<Attribute>} from the JDQL
     * SELECT list, calls {@code runtime.projectColumns}, and dispatches via the shared
     * {@code JdqlExecutor.dispatchMultiProjection(rt, elem, rows, attrs)} helper.
     */
    private static String emitProjectMulti(Stmt stmt, ExecutableElement method, String metamodel,
                                           Map<String, Integer> nameToIdx, TypeMirror returnType) {
        StringBuilder sb = new StringBuilder();
        sb.append("io.vidocq.mansart.data.dialect.Where _w = ");
        emitPredicate(sb, stmt.where, metamodel);
        sb.append("; io.vidocq.mansart.data.dialect.OrderBy _ob = ");
        emitOrderBy(sb, stmt.orderBy, metamodel);
        sb.append("; ");

        sb.append("java.util.List<io.vidocq.mansart.data.dialect.Attribute<?, ?>> _attrs = java.util.List.of(");
        for (int i = 0; i < stmt.projectAttrs.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(attrRef(metamodel, stmt.projectAttrs.get(i)));
        }
        sb.append("); ");

        List<ArgRef> argsInOrder = new ArrayList<>();
        if (stmt.where != null) collectArgs(stmt.where, argsInOrder);
        sb.append("java.lang.Object[] _xs = new java.lang.Object[]{");
        for (int i = 0; i < argsInOrder.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(resolveArg(argsInOrder.get(i), nameToIdx, method));
        }
        sb.append("}; ");

        sb.append("var _rows = runtime.projectColumns(").append(metamodel).append(".$MODEL, _attrs, _w, _ob, _xs); ");
        String rt = returnType.toString();
        String elem = projectionElementMulti(rt);
        sb.append("return (").append(rawType(rt)).append(") ")
          .append("io.vidocq.mansart.data.core.JdqlExecutor.dispatchMultiProjection(")
          .append(rawType(rt)).append(".class, ").append(elem).append(".class, _rows, _attrs);");
        return sb.toString();
    }

    /** M8-2 — extract the parameterised element type for multi-projection. Defaults to Object[]. */
    private static String projectionElementMulti(String rt) {
        if (rt.startsWith("java.util.Optional<") || rt.startsWith("java.util.List<")
         || rt.startsWith("java.util.Collection<") || rt.startsWith("java.lang.Iterable<")
         || rt.startsWith("java.util.stream.Stream<")) {
            int lt = rt.indexOf('<');
            int gt = rt.lastIndexOf('>');
            String inner = rt.substring(lt + 1, gt).trim();
            if (inner.startsWith("? extends ")) inner = inner.substring("? extends ".length());
            if (inner.startsWith("? super "))   inner = inner.substring("? super ".length());
            return inner.equals("java.lang.Object[]") ? "java.lang.Object[]" : inner;
        }
        if (rt.endsWith("[][]")) return "java.lang.Object[]";
        if (rt.endsWith("[]"))   return rt.substring(0, rt.length() - 2);
        return rt;
    }

    private static String rawType(String rt) {
        int lt = rt.indexOf('<');
        return lt < 0 ? rt : rt.substring(0, lt);
    }

    private static String projectionElementBoxed(String genericRt) {
        int lt = genericRt.indexOf('<');
        int gt = genericRt.lastIndexOf('>');
        if (lt < 0 || gt < 0) return "java.lang.Object";
        String inner = genericRt.substring(lt + 1, gt).trim();
        // Strip wildcard markers and bounds for simple reflection-friendly extraction
        if (inner.startsWith("? extends ")) inner = inner.substring("? extends ".length());
        if (inner.startsWith("? super "))   inner = inner.substring("? super ".length());
        return inner;
    }

    private static String boxedReturnType(TypeMirror t) {
        return switch (t.toString()) {
            case "boolean" -> "java.lang.Boolean";
            case "byte"    -> "java.lang.Byte";
            case "short"   -> "java.lang.Short";
            case "int"     -> "java.lang.Integer";
            case "long"    -> "java.lang.Long";
            case "float"   -> "java.lang.Float";
            case "double"  -> "java.lang.Double";
            case "char"    -> "java.lang.Character";
            default        -> t.toString();
        };
    }

    private static String zeroLiteralFor(TypeMirror t) {
        return switch (t.toString()) {
            case "long"   -> "0L";
            case "double" -> "0.0";
            case "float"  -> "0.0f";
            default       -> "0";
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

    /**
     * Generates a body that builds {@code Where} + args dynamically when any {@code IN} takes a
     * {@code Collection}. Predeclares one {@code _colN} per In(collection); the Where is built as
     * a single nested expression, with empty-In substituted by {@link io.vidocq.mansart.data.dialect.Where#ALWAYS_FALSE}.
     */
    private static String emitDynamicInBody(Stmt stmt, ExecutableElement method, String metamodel,
                                            Map<String, Integer> nameToIdx, TypeMirror returnType,
                                            boolean trailingPageRequest, boolean isCursoredPageReturn) {
        StringBuilder sb = new StringBuilder();
        DynCtx ctx = new DynCtx();

        // 1) Pre-declare one local Collection variable per In(collection) node.
        declareInCollections(sb, stmt.where, nameToIdx, method, ctx);

        // 2) Build args at runtime in the Where AST traversal order. Emit an empty list first.
        sb.append("java.util.List<java.lang.Object> _args = new java.util.ArrayList<>(); ");

        // 3) Build the Where as a single Java expression; emitArgs side-effect appends to _args.
        sb.append("io.vidocq.mansart.data.dialect.Where _w = ");
        sb.append(buildWhereExpr(stmt.where, metamodel, nameToIdx, method, ctx));
        sb.append("; ");

        // 4) Append args in the order the AST traversal will render bind sites.
        emitArgs(sb, stmt.where, nameToIdx, method, ctx);

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

    private static final class DynCtx { final Map<Object, String> colVar = new java.util.IdentityHashMap<>(); int counter = 0; }

    private static void declareInCollections(StringBuilder sb, Pred p,
                                             Map<String, Integer> nameToIdx, ExecutableElement m,
                                             DynCtx ctx) {
        if (p == null) return;
        switch (p) {
            case In in -> {
                if (in.collection) {
                    String var = "_col" + ctx.counter++;
                    ctx.colVar.put(in, var);
                    sb.append("java.util.Collection<?> ").append(var)
                      .append(" = (java.util.Collection<?>) ").append(resolveArg(in.args.get(0), nameToIdx, m)).append("; ");
                }
            }
            case FnIn in -> {
                if (in.collection) {
                    String var = "_col" + ctx.counter++;
                    ctx.colVar.put(in, var);
                    sb.append("java.util.Collection<?> ").append(var)
                      .append(" = (java.util.Collection<?>) ").append(resolveArg(in.args.get(0), nameToIdx, m)).append("; ");
                }
            }
            case And a -> { for (Pred c : a.children) declareInCollections(sb, c, nameToIdx, m, ctx); }
            case Or  o -> { for (Pred c : o.children) declareInCollections(sb, c, nameToIdx, m, ctx); }
            case Not n -> declareInCollections(sb, n.child, nameToIdx, m, ctx);
            default    -> {}
        }
    }

    private static String buildWhereExpr(Pred p, String metamodel,
                                         Map<String, Integer> nameToIdx, ExecutableElement m,
                                         DynCtx ctx) {
        String pkg = "io.vidocq.mansart.data.dialect.Where";
        if (p == null) return pkg + ".ALWAYS_TRUE";
        return switch (p) {
            case Cmp c        -> cmpExpr(pkg, metamodel, c.op, c.attr);
            case FnCmp c      -> wrapFunc(pkg, c.fn, cmpExpr(pkg, metamodel, c.op, c.attr));
            case IsNull n     -> isNullExpr(pkg, metamodel, n.negated, n.attr);
            case FnIsNull n   -> wrapFunc(pkg, n.fn, isNullExpr(pkg, metamodel, n.negated, n.attr));
            case Between b    -> betweenExpr(pkg, metamodel, b.attr);
            case FnBetween b  -> wrapFunc(pkg, b.fn, betweenExpr(pkg, metamodel, b.attr));
            case In in        -> inExpr(pkg, metamodel, in, ctx);
            case FnIn in      -> wrapFunc(pkg, in.fn, fnInExpr(pkg, metamodel, in, ctx));
            case And a -> pkg + ".and(" + a.children.stream()
                    .map(c -> buildWhereExpr(c, metamodel, nameToIdx, m, ctx))
                    .reduce((x, y) -> x + ", " + y).orElse("") + ")";
            case Or  o -> pkg + ".or("  + o.children.stream()
                    .map(c -> buildWhereExpr(c, metamodel, nameToIdx, m, ctx))
                    .reduce((x, y) -> x + ", " + y).orElse("") + ")";
            case Not n -> "new " + pkg + ".Not(" + buildWhereExpr(n.child, metamodel, nameToIdx, m, ctx) + ")";
        };
    }

    private static String cmpExpr(String pkg, String metamodel, Op op, String attr) {
        String cls = switch (op) {
            case EQ -> "Eq"; case NE -> "NotEq";
            case LT -> "Lt"; case LTE -> "Lte";
            case GT -> "Gt"; case GTE -> "Gte";
            case LIKE -> "Like";
        };
        return "new " + pkg + "." + cls + "(" + attrRef(metamodel, attr) + ")";
    }

    private static String isNullExpr(String pkg, String metamodel, boolean neg, String attr) {
        return "new " + pkg + "." + (neg ? "IsNotNull" : "IsNull") + "(" + attrRef(metamodel, attr) + ")";
    }

    private static String betweenExpr(String pkg, String metamodel, String attr) {
        return "new " + pkg + ".Between(" + attrRef(metamodel, attr) + ")";
    }

    private static String inExpr(String pkg, String metamodel, In in, DynCtx ctx) {
        if (in.collection) {
            String col = ctx.colVar.get(in);
            return col + ".isEmpty() ? " + pkg + ".ALWAYS_FALSE : new " + pkg + ".In("
                    + attrRef(metamodel, in.attr) + ", " + col + ".size())";
        }
        return "new " + pkg + ".In(" + attrRef(metamodel, in.attr) + ", " + in.args.size() + ")";
    }

    private static String fnInExpr(String pkg, String metamodel, FnIn in, DynCtx ctx) {
        if (in.collection) {
            String col = ctx.colVar.get(in);
            return col + ".isEmpty() ? " + pkg + ".ALWAYS_FALSE : new " + pkg + ".In("
                    + attrRef(metamodel, in.attr) + ", " + col + ".size())";
        }
        return "new " + pkg + ".In(" + attrRef(metamodel, in.attr) + ", " + in.args.size() + ")";
    }

    private static String wrapFunc(String pkg, String fn, String inner) {
        return "new " + pkg + ".Func(\"" + fn + "\", " + inner + ")";
    }

    /**
     * M8-3 — emit an Attribute reference. Flat names map to {@code metamodel.attr} (compile-time
     * static field), dotted paths route through {@code PathResolver.resolve(model, "a.b.c")}
     * which returns a {@code JoinedAttribute} for the dialect to interpret as joined column.
     */
    private static String attrRef(String metamodel, String attr) {
        if (attr.indexOf('.') < 0) return metamodel + "." + attr;
        return "io.vidocq.mansart.data.core.PathResolver.resolve(" + metamodel + ".$MODEL, \"" + attr + "\")";
    }

    private static void emitArgs(StringBuilder sb, Pred p,
                                 Map<String, Integer> nameToIdx, ExecutableElement m, DynCtx ctx) {
        if (p == null) return;
        switch (p) {
            case Cmp c       -> sb.append("_args.add(").append(resolveArg(c.arg, nameToIdx, m)).append("); ");
            case FnCmp c     -> sb.append("_args.add(").append(resolveArg(c.arg, nameToIdx, m)).append("); ");
            case IsNull n    -> {}
            case FnIsNull n  -> {}
            case Between b   -> {
                sb.append("_args.add(").append(resolveArg(b.lo, nameToIdx, m)).append("); ");
                sb.append("_args.add(").append(resolveArg(b.hi, nameToIdx, m)).append("); ");
            }
            case FnBetween b -> {
                sb.append("_args.add(").append(resolveArg(b.lo, nameToIdx, m)).append("); ");
                sb.append("_args.add(").append(resolveArg(b.hi, nameToIdx, m)).append("); ");
            }
            case In in -> {
                if (in.collection) {
                    sb.append("for (Object _v : ").append(ctx.colVar.get(in)).append(") _args.add(_v); ");
                } else {
                    for (ArgRef a : in.args) sb.append("_args.add(").append(resolveArg(a, nameToIdx, m)).append("); ");
                }
            }
            case FnIn in -> {
                if (in.collection) {
                    sb.append("for (Object _v : ").append(ctx.colVar.get(in)).append(") _args.add(_v); ");
                } else {
                    for (ArgRef a : in.args) sb.append("_args.add(").append(resolveArg(a, nameToIdx, m)).append("); ");
                }
            }
            case And a -> { for (Pred c : a.children) emitArgs(sb, c, nameToIdx, m, ctx); }
            case Or  o -> { for (Pred c : o.children) emitArgs(sb, c, nameToIdx, m, ctx); }
            case Not n -> emitArgs(sb, n.child, nameToIdx, m, ctx);
        }
    }

    private static boolean containsInList(Pred p) {
        return switch (p) {
            case In in       -> in.collection;
            case FnIn in     -> in.collection;
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
        enum Kind { SELECT, COUNT, UPDATE, DELETE, AGGREGATE, PROJECT, PROJECT_MULTI }
        Kind kind = Kind.SELECT;
        Pred where;
        List<Order> orderBy = new ArrayList<>();
        List<SetAssign> setAssignments = new ArrayList<>();
        String aggregateOp;       // "SUM" | "AVG" | "MIN" | "MAX" when kind == AGGREGATE
        String scalarAttr;        // attribute name for AGGREGATE / PROJECT
        /** M8-2 — for {@link Kind#PROJECT_MULTI}: list of attribute names selected. */
        List<String> projectAttrs = new ArrayList<>();
    }
    record SetAssign(String attr, ArgRef arg) {}
    sealed interface Pred permits Cmp, FnCmp, IsNull, FnIsNull, Between, FnBetween,
                                  In, FnIn, And, Or, Not {}
    record Cmp(String attr, Op op, ArgRef arg)              implements Pred {}
    /** M8-1 — comparator with a unary scalar function on the LHS: {@code UPPER(attr) op ?}. */
    record FnCmp(String fn, String attr, Op op, ArgRef arg)  implements Pred {}
    record IsNull(String attr, boolean negated)             implements Pred {}
    /** M8-1 — {@code fn(attr) IS [NOT] NULL}. */
    record FnIsNull(String fn, String attr, boolean negated) implements Pred {}
    record Between(String attr, ArgRef lo, ArgRef hi)       implements Pred {}
    /** M8-1 — {@code fn(attr) BETWEEN ? AND ?}. */
    record FnBetween(String fn, String attr, ArgRef lo, ArgRef hi) implements Pred {}
    /** {@code attr IN :collectionParam} (single ArgRef whose value is a Collection)
     *  OR {@code attr IN (:p1, :p2, ?N …)} (multiple ArgRefs, each a scalar). */
    record In(String attr, List<ArgRef> args, boolean collection) implements Pred {}
    /** M8-1 — {@code fn(attr) IN (...)}. */
    record FnIn(String fn, String attr, List<ArgRef> args, boolean collection) implements Pred {}
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
            case Cmp c -> emitCmpExpr(sb, pkg, metamodel, c.op, c.attr);
            case FnCmp c -> {
                sb.append("new ").append(pkg).append(".Func(\"").append(c.fn).append("\", ");
                emitCmpExpr(sb, pkg, metamodel, c.op, c.attr);
                sb.append(')');
            }
            case IsNull n -> emitIsNullExpr(sb, pkg, metamodel, n.negated, n.attr);
            case FnIsNull n -> {
                sb.append("new ").append(pkg).append(".Func(\"").append(n.fn).append("\", ");
                emitIsNullExpr(sb, pkg, metamodel, n.negated, n.attr);
                sb.append(')');
            }
            case Between b -> emitBetweenExpr(sb, pkg, metamodel, b.attr);
            case FnBetween b -> {
                sb.append("new ").append(pkg).append(".Func(\"").append(b.fn).append("\", ");
                emitBetweenExpr(sb, pkg, metamodel, b.attr);
                sb.append(')');
            }
            case In in -> {
                if (in.collection) throw new IllegalStateException("Collection-IN must go through the dynamic path");
                emitInExpr(sb, pkg, metamodel, in.attr, in.args.size());
            }
            case FnIn in -> {
                if (in.collection) throw new IllegalStateException("Collection-IN must go through the dynamic path");
                sb.append("new ").append(pkg).append(".Func(\"").append(in.fn).append("\", ");
                emitInExpr(sb, pkg, metamodel, in.attr, in.args.size());
                sb.append(')');
            }
            case And a -> emitCombinator(sb, "and", a.children, metamodel);
            case Or  o -> emitCombinator(sb, "or",  o.children, metamodel);
            case Not n -> {
                sb.append("new ").append(pkg).append(".Not(");
                emitPredicate(sb, n.child, metamodel);
                sb.append(')');
            }
        }
    }

    private static void emitCmpExpr(StringBuilder sb, String pkg, String metamodel, Op op, String attr) {
        String cls = switch (op) {
            case EQ -> "Eq"; case NE -> "NotEq";
            case LT -> "Lt"; case LTE -> "Lte";
            case GT -> "Gt"; case GTE -> "Gte";
            case LIKE -> "Like";
        };
        sb.append("new ").append(pkg).append('.').append(cls).append('(')
          .append(attrRef(metamodel, attr)).append(')');
    }

    private static void emitIsNullExpr(StringBuilder sb, String pkg, String metamodel, boolean neg, String attr) {
        sb.append("new ").append(pkg).append('.').append(neg ? "IsNotNull" : "IsNull")
          .append('(').append(attrRef(metamodel, attr)).append(')');
    }

    private static void emitBetweenExpr(StringBuilder sb, String pkg, String metamodel, String attr) {
        sb.append("new ").append(pkg).append(".Between(")
          .append(attrRef(metamodel, attr)).append(')');
    }

    private static void emitInExpr(StringBuilder sb, String pkg, String metamodel, String attr, int n) {
        sb.append("new ").append(pkg).append(".In(")
          .append(attrRef(metamodel, attr)).append(", ").append(n).append(')');
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
              .append('(').append(attrRef(metamodel, o.attr)).append(')');
        }
        sb.append("))");
    }

    private static void collectArgs(Pred p, List<ArgRef> out) {
        switch (p) {
            case Cmp c        -> out.add(c.arg);
            case FnCmp c      -> out.add(c.arg);
            case Between b    -> { out.add(b.lo); out.add(b.hi); }
            case FnBetween b  -> { out.add(b.lo); out.add(b.hi); }
            case IsNull ign   -> {}
            case FnIsNull ign -> {}
            case In in        -> out.addAll(in.args);   // works for non-collection literal IN
            case FnIn in      -> out.addAll(in.args);
            case And a        -> { for (Pred c : a.children) collectArgs(c, out); }
            case Or  o        -> { for (Pred c : o.children) collectArgs(c, out); }
            case Not n        -> collectArgs(n.child, out);
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
                        // M8-3 — allow dotted paths (book.author.name) inside an IDENT.
                        while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos))
                                || src.charAt(pos) == '_' || src.charAt(pos) == '.'))
                            pos++;
                        String w = src.substring(s, pos);
                        // Keywords are matched on the *full* token but only when they don't contain
                        // a dot — a dotted token is necessarily an attribute path.
                        if (isKeyword(w) && w.indexOf('.') < 0) return new Token(Tk.KW, w.toUpperCase());
                        return new Token(Tk.IDENT, w);
                    }
                    throw new ParseException("Unexpected character '" + c + "' at position " + pos);
                }
            }
        }
        private static boolean isKeyword(String w) {
            // M8-1 — scalar function names (UPPER/LOWER/LENGTH/ABS/CONCAT) are NOT registered
            // as keywords here — they would shadow homonym attribute names (e.g. TCK Box.length).
            // The parser detects them contextually as IDENT followed by '('.
            return switch (w.toUpperCase()) {
                case "FROM", "WHERE", "ORDER", "BY", "AND", "OR", "NOT",
                     "IS", "NULL", "BETWEEN", "LIKE", "ASC", "DESC",
                     "SELECT", "UPDATE", "DELETE", "SET", "IN", "COUNT", "THIS",
                     "SUM", "AVG", "MIN", "MAX" -> true;
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
                } else if (peekAggregate()) {
                    Token agg = lex.consume();
                    if (lex.peek().kind != Tk.LPAREN) throw new ParseException("Expected '(' after " + agg.text);
                    lex.consume();
                    String attr = expectAttr();
                    if (lex.peek().kind != Tk.RPAREN) throw new ParseException("Expected ')' after " + agg.text + "(...)");
                    lex.consume();
                    s.kind = Stmt.Kind.AGGREGATE;
                    s.aggregateOp = agg.text;
                    s.scalarAttr = attr;
                } else if (lex.peek().kind == Tk.IDENT) {
                    // M8-2 — SELECT a [, b, c, …] : single attr → PROJECT, multi → PROJECT_MULTI.
                    String first = expectAttr();
                    if (lex.peek().kind == Tk.COMMA) {
                        s.kind = Stmt.Kind.PROJECT_MULTI;
                        s.projectAttrs.add(first);
                        while (lex.peek().kind == Tk.COMMA) {
                            lex.consume();
                            s.projectAttrs.add(expectAttr());
                        }
                    } else {
                        s.kind = Stmt.Kind.PROJECT;
                        s.scalarAttr = first;
                    }
                } else {
                    throw new ParseException("Unexpected token after SELECT: " + lex.peek().text);
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
            // M8-1 — LHS may be a unary scalar function on an attribute: UPPER(name) = ?, …
            // Detected contextually (IDENT immediately followed by '(') so that attribute
            // names like Box.length are not shadowed by the LENGTH function name.
            if (lex.peek().kind == Tk.IDENT) {
                Token saved = lex.peek();
                String upper = saved.text.toUpperCase();
                if (isUnaryFn(upper)) {
                    lex.consume();
                    if (lex.peek().kind == Tk.LPAREN) {
                        lex.consume();
                        String attr = expectAttr();
                        if (lex.peek().kind != Tk.RPAREN) throw new ParseException("Expected ')' after " + upper + "(attr)");
                        lex.consume();
                        return parseFnRhs(upper, attr);
                    }
                    if (!attrNames.contains(saved.text)) {
                        throw new ParseException("Unknown attribute '" + saved.text + "' on entity " + entityName);
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
                    if (lex.peek().kind != Tk.RPAREN) throw new ParseException("Expected ')' to close IN(...)");
                    lex.consume();
                    return new In(attr, elems, false);
                }
                return new In(attr, List.of(parseArg()), true);
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
            if (peekKw("BETWEEN")) {
                lex.consume();
                ArgRef lo = parseArg();
                expectKw("AND");
                ArgRef hi = parseArg();
                return new FnBetween(fn, attr, lo, hi);
            }
            if (peekKw("LIKE")) {
                lex.consume();
                return new FnCmp(fn, attr, Op.LIKE, parseArg());
            }
            if (peekKw("IN")) {
                lex.consume();
                if (lex.peek().kind == Tk.LPAREN) {
                    lex.consume();
                    List<ArgRef> elems = new ArrayList<>();
                    elems.add(parseArg());
                    while (lex.peek().kind == Tk.COMMA) { lex.consume(); elems.add(parseArg()); }
                    if (lex.peek().kind != Tk.RPAREN) throw new ParseException("Expected ')' to close IN(...)");
                    lex.consume();
                    return new FnIn(fn, attr, elems, false);
                }
                return new FnIn(fn, attr, List.of(parseArg()), true);
            }
            Op op = expectCmpOp(fn + "(" + attr + ")");
            return new FnCmp(fn, attr, op, parseArg());
        }

        private Op expectCmpOp(String label) {
            Op op = switch (lex.peek().kind) {
                case EQ  -> Op.EQ;
                case NE  -> Op.NE;
                case LT  -> Op.LT;
                case LTE -> Op.LTE;
                case GT  -> Op.GT;
                case GTE -> Op.GTE;
                default  -> throw new ParseException(
                        "Expected comparison operator after '" + label + "', got: " + lex.peek().text);
            };
            lex.consume();
            return op;
        }

        private static boolean isUnaryFn(String kw) {
            return switch (kw) { case "UPPER", "LOWER", "LENGTH", "ABS" -> true; default -> false; };
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
            // M8-3 — accept dotted paths (book.author.name). Validate only the head; downstream
            // resolution in JdqlExecutor walks the chain via target metamodels.
            int dot = t.text.indexOf('.');
            String head = dot < 0 ? t.text : t.text.substring(0, dot);
            if (!attrNames.contains(head)) {
                throw new ParseException("Unknown attribute '" + head + "' on entity " + entityName);
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
        private boolean peekAggregate() {
            Token t = lex.peek();
            if (t.kind != Tk.KW) return false;
            return switch (t.text) { case "SUM", "AVG", "MIN", "MAX" -> true; default -> false; };
        }
    }

    static final class ParseException extends RuntimeException {
        ParseException(String message) { super(message); }
    }
}
