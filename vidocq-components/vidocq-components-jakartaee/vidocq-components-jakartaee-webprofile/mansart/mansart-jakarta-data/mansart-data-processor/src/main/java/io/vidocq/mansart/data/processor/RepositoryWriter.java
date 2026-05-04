package io.vidocq.mansart.data.processor;

import javax.annotation.processing.Filer;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;

/**
 * Generates {@code XxxRepositoryImpl} for an interface annotated with {@code @jakarta.data.repository.Repository}.
 *
 * <p>M3a scope: implements only the methods inherited from {@code BasicRepository<T, K>} or
 * {@code CrudRepository<T, K>} (save, findById, findAll, deleteById, delete, count, existsById).
 * Custom abstract methods raise an {@code UnsupportedOperationException} until M3b adds derived
 * query methods and {@code @Query} JDQL.
 */
final class RepositoryWriter {

    private static final String BASIC_REPOSITORY = "jakarta.data.repository.BasicRepository";
    private static final String CRUD_REPOSITORY  = "jakarta.data.repository.CrudRepository";
    private static final String DATA_REPOSITORY  = "jakarta.data.repository.DataRepository";

    private final Filer    filer;
    private final Elements elements;
    private final Types    types;

    RepositoryWriter(Filer filer, Elements elements, Types types) {
        this.filer = filer;
        this.elements = elements;
        this.types = types;
    }

    /** @return true if {@code repo} is a {@code @Repository} interface and an Impl was emitted. */
    boolean writeIfRepository(TypeElement repo) throws IOException {
        TypeArgs args = findEntityAndKey(repo);
        if (args == null) return false;

        String pkg       = elements.getPackageOf(repo).getQualifiedName().toString();
        String simple    = repo.getSimpleName().toString();
        String implName  = simple + "Impl";
        String fqn       = pkg.isEmpty() ? implName : pkg + "." + implName;
        TypeElement entityType = (TypeElement) args.entity.asElement();
        String entitySimple = entityType.getSimpleName().toString();
        String entityPkg = elements.getPackageOf(entityType).getQualifiedName().toString();
        String metamodel = entityPkg.isEmpty() ? "_" + entitySimple : entityPkg + "._" + entitySimple;
        String entityFqn = entityType.getQualifiedName().toString();
        String idFqn     = args.idBoxedFqn();

        java.util.Set<String> attributeNames = collectAttributeNames(entityType);

        JavaFileObject file = filer.createSourceFile(fqn, repo);
        try (PrintWriter w = new PrintWriter(file.openWriter())) {
            if (!pkg.isEmpty()) {
                w.println("package " + pkg + ";");
                w.println();
            }
            w.println("import io.vidocq.mansart.data.core.RepositoryRuntime;");
            w.println("import javax.annotation.processing.Generated;");
            w.println("import java.util.List;");
            w.println("import java.util.Optional;");
            w.println();
            w.println("@Generated(\"io.vidocq.mansart.data.processor.MansartProcessor\")");
            w.println("public final class " + implName + " implements " + simple + " {");
            w.println();
            w.println("    private final RepositoryRuntime runtime;");
            w.println();
            w.println("    public " + implName + "(RepositoryRuntime runtime) {");
            w.println("        this.runtime = runtime;");
            w.println("    }");
            w.println();

            DeclaredType repoType = (DeclaredType) repo.asType();
            for (ExecutableElement m : abstractMethods(repo)) {
                writeMethod(w, m, repo, repoType, metamodel, entityFqn, idFqn, attributeNames);
            }

            w.println("}");
        }
        return true;
    }

    private void writeMethod(PrintWriter w, ExecutableElement m, TypeElement repo, DeclaredType repoType,
                             String metamodel, String entityFqn, String idFqn,
                             java.util.Set<String> attributeNames) {
        ExecutableType resolved = (ExecutableType) types.asMemberOf(repoType, m);
        String name = m.getSimpleName().toString();
        TypeMirror returnType = resolved.getReturnType();
        List<? extends TypeMirror> paramTypes = resolved.getParameterTypes();

        StringBuilder sig = new StringBuilder("    @Override public ");
        var typeVars = resolved.getTypeVariables();
        if (!typeVars.isEmpty()) {
            sig.append('<');
            for (int i = 0; i < typeVars.size(); i++) {
                if (i > 0) sig.append(", ");
                var tv = typeVars.get(i);
                sig.append(tv);
                var upper = tv.getUpperBound();
                if (upper != null && !upper.toString().equals("java.lang.Object")) {
                    sig.append(" extends ").append(upper);
                }
            }
            sig.append("> ");
        }
        sig.append(returnType).append(' ').append(name).append('(');
        for (int i = 0; i < paramTypes.size(); i++) {
            if (i > 0) sig.append(", ");
            sig.append(paramTypes.get(i)).append(' ').append(p(i));
        }
        sig.append(')');
        if (!m.getThrownTypes().isEmpty()) {
            sig.append(" throws ");
            for (int i = 0; i < m.getThrownTypes().size(); i++) {
                if (i > 0) sig.append(", ");
                sig.append(m.getThrownTypes().get(i));
            }
        }
        sig.append(" {");
        w.println(sig);

        String body = lifecycleBody(m, returnType, metamodel, entityFqn, repo);
        if (body == null) body = bodyFor(name, paramTypes, returnType, metamodel, entityFqn);
        if (body == null) {
            // Try derived-query parsing (M3b).
            QueryMethodParser.QueryDescriptor desc = QueryMethodParser.parse(name, attributeNames);
            if (desc != null) {
                body = derivedQueryBody(desc, returnType, metamodel, (List<? extends TypeMirror>) paramTypes);
            }
        }
        if (body == null) {
            w.println("        throw new UnsupportedOperationException(\""
                    + name + ": no matching CRUD or derived-query rule. Check method name and attribute spelling.\");");
        } else {
            w.println("        " + body);
        }
        w.println("    }");
        w.println();
    }

    private String lifecycleBody(ExecutableElement m, TypeMirror returnType,
                                 String metamodel, String entityFqn, TypeElement repo) {
        // Only fire for methods declared on the user's repo, not for inherited methods
        // (BasicRepository.save / delete carry @Save / @Delete in the spec, but their
        // semantics are upsert / silent-delete — handled by bodyFor by name).
        if (!m.getEnclosingElement().equals(repo)) return null;
        if (m.getParameters().size() != 1) return null;
        boolean isVoid = returnType.getKind() == javax.lang.model.type.TypeKind.VOID;
        if (hasJakartaAnnotation(m, "jakarta.data.repository.Insert")) {
            return wrapReturn("runtime.insertStrict(" + metamodel + ".$MODEL, ("
                    + entityFqn + ") " + p(0) + ")", isVoid);
        }
        if (hasJakartaAnnotation(m, "jakarta.data.repository.Update")) {
            return wrapReturn("runtime.updateStrict(" + metamodel + ".$MODEL, ("
                    + entityFqn + ") " + p(0) + ")", isVoid);
        }
        if (hasJakartaAnnotation(m, "jakarta.data.repository.Save")) {
            return wrapReturn("runtime.save(" + metamodel + ".$MODEL, ("
                    + entityFqn + ") " + p(0) + ")", isVoid);
        }
        if (hasJakartaAnnotation(m, "jakarta.data.repository.Delete")) {
            // Delete annotation: parameter is the entity to delete (strict, with @Version check).
            String call = "runtime.deleteStrict(" + metamodel + ".$MODEL, ("
                    + entityFqn + ") " + p(0) + ")";
            return isVoid ? call + ";" : call + "; return null;";
        }
        return null;
    }

    private String wrapReturn(String call, boolean isVoid) {
        return isVoid ? call + ";" : "return " + call + ";";
    }

    private boolean hasJakartaAnnotation(ExecutableElement m, String fqn) {
        for (var a : m.getAnnotationMirrors()) {
            if (((TypeElement) a.getAnnotationType().asElement()).getQualifiedName().contentEquals(fqn)) {
                return true;
            }
        }
        return false;
    }

    private String derivedQueryBody(QueryMethodParser.QueryDescriptor d, TypeMirror returnType,
                                    String metamodel, List<? extends TypeMirror> paramTypes) {
        // Trailing PageRequest: split it off and dispatch to queryPage.
        boolean trailingPageRequest = !paramTypes.isEmpty()
                && isPageRequest(paramTypes.get(paramTypes.size() - 1));
        int predicateArity = trailingPageRequest ? paramTypes.size() - 1 : paramTypes.size();

        // Dynamic-arity In: when the sole predicate is `In` and the param is a Collection,
        // build the Where with the runtime-known size and spread the args.
        if (d.predicates().size() == 1
                && d.predicates().get(0).comparator() == QueryMethodParser.Comparator.IN
                && predicateArity == 1
                && isCollectionLike(paramTypes.get(0))) {
            return inCollectionBody(d, returnType, metamodel);
        }
        String where = renderWhere(d, metamodel);
        String orderBy = renderOrderBy(d, metamodel);
        String args = "new java.lang.Object[]{" + argsList(predicateArity) + "}";

        if (trailingPageRequest && d.op() == QueryMethodParser.Operation.FIND) {
            String runtimeMethod = isCursoredPageReturn(returnType) ? "queryCursored" : "queryPage";
            return "return runtime." + runtimeMethod + "(" + metamodel + ".$MODEL, " + where + ", " + orderBy
                    + ", " + p(paramTypes.size() - 1) + ", " + args + ");";
        }

        return switch (d.op()) {
            case FIND     -> findBody(returnType, metamodel, where, orderBy, args);
            case FIND_ONE -> "return runtime.queryOne(" + metamodel + ".$MODEL, " + where + ", " + args + ");";
            case COUNT    -> "return runtime.countWhere(" + metamodel + ".$MODEL, " + where + ", " + args + ");";
            case EXISTS   -> "return runtime.existsWhere(" + metamodel + ".$MODEL, " + where + ", " + args + ");";
            case DELETE   -> deleteBody(returnType, metamodel, where, args);
        };
    }

    private String inCollectionBody(QueryMethodParser.QueryDescriptor d, TypeMirror returnType, String metamodel) {
        QueryMethodParser.Predicate pred = d.predicates().get(0);
        String attr = metamodel + "." + pred.attribute();
        StringBuilder sb = new StringBuilder();
        sb.append("java.util.Collection<?> col = (java.util.Collection<?>) ").append(p(0)).append("; ");
        // Short-circuit empty In: SQL `IN ()` is invalid; return the natural empty result.
        sb.append("if (col.isEmpty()) ").append(emptyInReturn(d.op(), returnType)).append(' ');
        sb.append("io.vidocq.mansart.data.dialect.Where w = new io.vidocq.mansart.data.dialect.Where.In(")
          .append(attr).append(", col.size()); ");
        sb.append("java.lang.Object[] xs = col.toArray(); ");
        String orderBy = renderOrderBy(d, metamodel);
        return switch (d.op()) {
            case FIND     -> sb.append(findBody(returnType, metamodel, "w", orderBy, "xs")).toString();
            case FIND_ONE -> sb.append("return runtime.queryOne(").append(metamodel).append(".$MODEL, w, xs);").toString();
            case COUNT    -> sb.append("return runtime.countWhere(").append(metamodel).append(".$MODEL, w, xs);").toString();
            case EXISTS   -> sb.append("return runtime.existsWhere(").append(metamodel).append(".$MODEL, w, xs);").toString();
            case DELETE   -> sb.append(deleteBody(returnType, metamodel, "w", "xs")).toString();
        };
    }

    private String emptyInReturn(QueryMethodParser.Operation op, TypeMirror rt) {
        return switch (op) {
            case FIND -> {
                String s = rt.toString();
                if (s.startsWith("java.util.Optional"))            yield "return java.util.Optional.empty();";
                if (s.startsWith("java.util.stream.Stream"))       yield "return java.util.stream.Stream.empty();";
                if (s.startsWith("java.util.List")
                        || s.startsWith("java.util.Collection")
                        || s.startsWith("java.lang.Iterable"))      yield "return java.util.List.of();";
                yield "throw new io.vidocq.mansart.data.core.MansartDataException(\"empty In with single-entity return\");";
            }
            case FIND_ONE -> "return java.util.Optional.empty();";
            case COUNT    -> "return 0L;";
            case EXISTS   -> "return false;";
            case DELETE   -> rt.getKind() == javax.lang.model.type.TypeKind.VOID ? "return;"
                          : (rt.getKind() == javax.lang.model.type.TypeKind.INT ? "return 0;" : "return 0L;");
        };
    }

    private boolean isCollectionLike(TypeMirror t) {
        String s = types.erasure(t).toString();
        return s.equals("java.util.Collection") || s.equals("java.util.List") || s.equals("java.util.Set")
                || s.equals("java.lang.Iterable");
    }

    private boolean isPageRequest(TypeMirror t) {
        return types.erasure(t).toString().equals("jakarta.data.page.PageRequest");
    }

    private boolean isOrder(TypeMirror t) {
        return types.erasure(t).toString().equals("jakarta.data.Order");
    }

    private boolean isPageReturn(TypeMirror t) {
        return t.toString().startsWith("jakarta.data.page.Page");
    }

    private boolean isCursoredPageReturn(TypeMirror t) {
        return t.toString().startsWith("jakarta.data.page.CursoredPage");
    }

    private String findBody(TypeMirror returnType, String metamodel, String where, String orderBy, String args) {
        String rt = returnType.toString();
        String list = "runtime.queryList(" + metamodel + ".$MODEL, " + where + ", " + orderBy + ", " + args + ")";
        if (rt.startsWith("java.util.Optional")) {
            return "return runtime.queryOne(" + metamodel + ".$MODEL, " + where + ", " + args + ");";
        }
        if (rt.startsWith("java.util.stream.Stream"))    return "return " + list + ".stream();";
        if (rt.startsWith("java.util.List"))             return "return " + list + ";";
        if (rt.startsWith("java.util.Collection"))       return "return " + list + ";";
        if (rt.startsWith("java.lang.Iterable"))         return "return " + list + ";";
        // Single-entity return → use queryOne and unwrap (throws if missing per Jakarta Data semantics)
        return "return runtime.queryOne(" + metamodel + ".$MODEL, " + where + ", " + args
                + ").orElseThrow(() -> new io.vidocq.mansart.data.core.MansartDataException(\"No result\"));";
    }

    private String deleteBody(TypeMirror returnType, String metamodel, String where, String args) {
        String call = "runtime.deleteWhere(" + metamodel + ".$MODEL, " + where + ", " + args + ")";
        return switch (returnType.getKind()) {
            case VOID -> call + ";";
            case LONG -> "return " + call + ";";
            case INT  -> "return (int) " + call + ";";
            default   -> "return " + call + ";";
        };
    }

    private String renderWhere(QueryMethodParser.QueryDescriptor d, String metamodel) {
        if (d.predicates().isEmpty()) return "io.vidocq.mansart.data.dialect.Where.ALWAYS_TRUE";
        if (d.predicates().size() == 1) return predicateExpr(d.predicates().get(0), metamodel);
        StringBuilder sb = new StringBuilder();
        sb.append("io.vidocq.mansart.data.dialect.Where.")
          .append(d.combinator() == QueryMethodParser.Combinator.AND ? "and" : "or")
          .append('(');
        for (int i = 0; i < d.predicates().size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(predicateExpr(d.predicates().get(i), metamodel));
        }
        sb.append(')');
        return sb.toString();
    }

    private String predicateExpr(QueryMethodParser.Predicate p, String metamodel) {
        String attr = metamodel + "." + p.attribute();
        String pkg = "io.vidocq.mansart.data.dialect.Where";
        return switch (p.comparator()) {
            case EQ        -> "new " + pkg + ".Eq(" + attr + ")";
            case NOT_EQ    -> "new " + pkg + ".NotEq(" + attr + ")";
            case LT        -> "new " + pkg + ".Lt(" + attr + ")";
            case LTE       -> "new " + pkg + ".Lte(" + attr + ")";
            case GT        -> "new " + pkg + ".Gt(" + attr + ")";
            case GTE       -> "new " + pkg + ".Gte(" + attr + ")";
            case LIKE      -> "new " + pkg + ".Like(" + attr + ")";
            case BETWEEN   -> "new " + pkg + ".Between(" + attr + ")";
            case IN        -> "new " + pkg + ".In(" + attr + ", 1)"; // arity refined when caller passes a List — M3c
            case IS_NULL    -> "new " + pkg + ".IsNull(" + attr + ")";
            case IS_NOT_NULL -> "new " + pkg + ".IsNotNull(" + attr + ")";
        };
    }

    private String renderOrderBy(QueryMethodParser.QueryDescriptor d, String metamodel) {
        if (d.orderBy().isEmpty()) return "io.vidocq.mansart.data.dialect.OrderBy.NONE";
        StringBuilder sb = new StringBuilder("new io.vidocq.mansart.data.dialect.OrderBy(java.util.List.of(");
        for (int i = 0; i < d.orderBy().size(); i++) {
            if (i > 0) sb.append(", ");
            QueryMethodParser.Order o = d.orderBy().get(i);
            sb.append("io.vidocq.mansart.data.dialect.OrderBy.Order.")
              .append(o.asc() ? "asc" : "desc")
              .append('(').append(metamodel).append('.').append(o.attribute()).append(')');
        }
        sb.append("))");
        return sb.toString();
    }

    private String argsList(int arity) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arity; i++) {
            if (i > 0) sb.append(", ");
            sb.append(p(i));
        }
        return sb.toString();
    }

    private java.util.Set<String> collectAttributeNames(TypeElement entity) {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        for (var member : entity.getEnclosedElements()) {
            if (member.getKind() == javax.lang.model.element.ElementKind.FIELD
                    && !member.getModifiers().contains(javax.lang.model.element.Modifier.STATIC)
                    && !member.getModifiers().contains(javax.lang.model.element.Modifier.TRANSIENT)) {
                names.add(member.getSimpleName().toString());
            }
        }
        return names;
    }

    private String bodyFor(String name, List<? extends TypeMirror> paramTypes,
                           TypeMirror returnType, String metamodel, String entityFqn) {
        int arity = paramTypes.size();
        boolean isVoid = returnType.getKind() == javax.lang.model.type.TypeKind.VOID;
        boolean returnsStream = returnType.toString().startsWith("java.util.stream.Stream");
        return switch (name) {
            case "save"        -> arity == 1 ? saveBody(metamodel, entityFqn) : null;
            case "saveAll"     -> arity == 1 ? saveAllBody(metamodel, entityFqn) : null;
            case "findById"    -> arity == 1 ? "return runtime.findById(" + metamodel + ".$MODEL, " + p(0) + ");" : null;
            case "findAll" -> {
                if (arity == 0) yield findAllBody(metamodel, returnsStream);
                if (arity == 2 && isPageRequest(paramTypes.get(0)) && isOrder(paramTypes.get(1))) {
                    yield "return runtime.queryPage(" + metamodel + ".$MODEL, "
                            + "io.vidocq.mansart.data.dialect.Where.ALWAYS_TRUE, "
                            + "runtime.toOrderBy(" + metamodel + ".$MODEL, " + p(1) + "), "
                            + p(0) + ", new java.lang.Object[0]);";
                }
                if (arity == 1 && isPageRequest(paramTypes.get(0))) {
                    yield "return runtime.queryPage(" + metamodel + ".$MODEL, "
                            + "io.vidocq.mansart.data.dialect.Where.ALWAYS_TRUE, "
                            + "io.vidocq.mansart.data.dialect.OrderBy.NONE, "
                            + p(0) + ", new java.lang.Object[0]);";
                }
                yield null;
            }
            case "deleteById"  -> arity == 1 ? simpleVoidOrBool("deleteById", metamodel, isVoid) : null;
            case "delete"      -> arity == 1 ? deleteEntityBody(metamodel, entityFqn, isVoid) : null;
            case "deleteAll" -> {
                if (arity == 0) yield "for (var e : runtime.findAll(" + metamodel + ".$MODEL)) runtime.delete("
                        + metamodel + ".$MODEL, e);";
                if (arity == 1) yield "for (var e : " + p(0) + ") runtime.delete(" + metamodel + ".$MODEL, ("
                        + entityFqn + ") e);";
                yield null;
            }
            case "count"       -> arity == 0 ? "return runtime.count(" + metamodel + ".$MODEL);" : null;
            case "existsById"  -> arity == 1 ? "return runtime.existsById(" + metamodel + ".$MODEL, " + p(0) + ");" : null;
            default            -> null;
        };
    }

    private String saveBody(String metamodel, String entityFqn) {
        // <S extends T> S save(S e) → runtime.save is typed on T; widen in, unchecked-cast out.
        return "@SuppressWarnings(\"unchecked\") S r = (S) runtime.save(" + metamodel
                + ".$MODEL, (" + entityFqn + ") " + p(0) + "); return r;";
    }

    private String findAllBody(String metamodel, boolean returnsStream) {
        String call = "runtime.findAll(" + metamodel + ".$MODEL)";
        return returnsStream ? "return " + call + ".stream();" : "return " + call + ";";
    }

    private String simpleVoidOrBool(String op, String metamodel, boolean isVoid) {
        String call = "runtime." + op + "(" + metamodel + ".$MODEL, " + p(0) + ")";
        return isVoid ? call + ";" : "return " + call + ";";
    }

    private String p(int idx) { return "arg" + idx; }

    private String saveAllBody(String metamodel, String entityFqn) {
        return "var out = new java.util.ArrayList<S>(); for (S e : " + p(0)
                + ") { @SuppressWarnings(\"unchecked\") S r = (S) runtime.save(" + metamodel
                + ".$MODEL, (" + entityFqn + ") e); out.add(r); } return out;";
    }

    private String deleteEntityBody(String metamodel, String entityFqn, boolean isVoid) {
        String call = "runtime.delete(" + metamodel + ".$MODEL, (" + entityFqn + ") " + p(0) + ")";
        return isVoid ? call + ";" : "return " + call + ";";
    }

    /* ---------- helpers ---------- */

    private List<ExecutableElement> abstractMethods(TypeElement repo) {
        List<ExecutableElement> out = new java.util.ArrayList<>();
        for (var member : elements.getAllMembers(repo)) {
            if (member instanceof ExecutableElement e
                    && e.getKind() == javax.lang.model.element.ElementKind.METHOD
                    && e.getModifiers().contains(javax.lang.model.element.Modifier.ABSTRACT)
                    && !e.getModifiers().contains(javax.lang.model.element.Modifier.STATIC)
                    && !e.getModifiers().contains(javax.lang.model.element.Modifier.DEFAULT)) {
                if (member.getEnclosingElement() instanceof TypeElement enc
                        && enc.getQualifiedName().contentEquals("java.lang.Object")) continue;
                out.add(e);
            }
        }
        // Rewrite parameter names to argN (we lose original names through the API anyway when
        // generating signatures using TypeMirror.toString()). The generator above already uses
        // arg0/arg1 — but the printed signature must match. We override by remapping in writeMethod.
        return out;
    }

    /** Walk the supertype chain looking for {@code BasicRepository<T, K>} or {@code CrudRepository<T, K>}. */
    private TypeArgs findEntityAndKey(TypeElement repo) {
        for (TypeMirror itf : allInterfaces(repo)) {
            if (itf instanceof DeclaredType dt
                    && dt.asElement() instanceof TypeElement te) {
                String fqn = te.getQualifiedName().toString();
                if ((fqn.equals(BASIC_REPOSITORY) || fqn.equals(CRUD_REPOSITORY) || fqn.equals(DATA_REPOSITORY))
                        && dt.getTypeArguments().size() >= 2) {
                    TypeMirror entity = dt.getTypeArguments().get(0);
                    TypeMirror id     = dt.getTypeArguments().get(1);
                    if (entity instanceof DeclaredType e) return new TypeArgs(e, id);
                }
            }
        }
        return null;
    }

    private List<TypeMirror> allInterfaces(TypeElement repo) {
        List<TypeMirror> out = new java.util.ArrayList<>();
        collectInterfaces(repo.asType(), out);
        return out;
    }

    private void collectInterfaces(TypeMirror t, List<TypeMirror> out) {
        if (!(t instanceof DeclaredType dt)) return;
        out.add(t);
        for (TypeMirror s : types.directSupertypes(t)) {
            collectInterfaces(s, out);
        }
    }

    private record TypeArgs(DeclaredType entity, TypeMirror id) {
        String idBoxedFqn() {
            return switch (id.toString()) {
                case "byte"    -> "java.lang.Byte";
                case "short"   -> "java.lang.Short";
                case "int"     -> "java.lang.Integer";
                case "long"    -> "java.lang.Long";
                case "boolean" -> "java.lang.Boolean";
                case "float"   -> "java.lang.Float";
                case "double"  -> "java.lang.Double";
                default        -> id.toString();
            };
        }
    }
}
