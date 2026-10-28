/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.mansart.data.processor;

import io.vidocq.mansart.data.core.QueryMethodParser;

import javax.annotation.processing.Messager;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
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
    private static final String OPTIONAL   = "java.util.Optional";
    private static final String STREAM     = "java.util.stream.Stream";
    private static final String LIST       = "java.util.List";
    private static final String COLLECTION = "java.util.Collection";
    private static final String ITERABLE   = "java.lang.Iterable";
    private static final String VALUE      = "value";

    private final SourceSink sink;
    private final Elements elements;
    private final Types    types;
    private final EntityRegistry entityRegistry;
    private final Messager messager; // nullable — null in the Maven-plugin path

    RepositoryWriter(SourceSink sink, Elements elements, Types types, EntityRegistry entityRegistry,
                     Messager messager) {
        this.sink = sink;
        this.elements = elements;
        this.types = types;
        this.entityRegistry = entityRegistry;
        this.messager = messager;
    }

    /**
     * M8-3i — builds a {@link QueryMethodParser.PathResolver} backed by the cross-entity
     * {@link EntityRegistry}. Given a CamelCase chunk, tries the longest-prefix match against
     * the current entity's attributes; if that prefix is a relation, recurses into the target
     * entity for the suffix. Returns a dotted path like {@code "author.name"} when the entire
     * chunk decomposes through known relations, or {@code null} otherwise.
     */
    private QueryMethodParser.PathResolver buildPathResolver(String rootEntityFqn) {
        return (chunk, ignored) -> resolvePath(chunk, rootEntityFqn);
    }

    private String resolvePath(String camelChunk, String entityFqn) {
        EntityRegistry.EntityFacet facet = entityRegistry == null ? null : entityRegistry.get(entityFqn);
        if (facet == null || camelChunk.isEmpty()) return null;
        String lcFirst = Character.toLowerCase(camelChunk.charAt(0)) + camelChunk.substring(1);
        // Greedy from longest — favour the longer attribute match so "primaryAddress" beats "primary".
        for (int len = lcFirst.length(); len > 0; len--) {
            String head = lcFirst.substring(0, len);
            if (!facet.attrs().contains(head)) continue;
            // Full-chunk match → terminal attribute (no further descent).
            if (len == camelChunk.length()) return head;
            // Suffix exists → head must be a relation to descend.
            String targetFqn = facet.relations().get(head);
            if (targetFqn == null) continue;
            String tail = resolvePath(camelChunk.substring(len), targetFqn);
            if (tail != null) return head + "." + tail;
        }
        return null;
    }

    /** @return the entity {@link TypeElement} backing {@code repo}, or {@code null} when it is not
     *  a {@code @Repository} extending {@code BasicRepository<E, K>} / {@code CrudRepository<E, K>}. */
    TypeElement entityTypeOf(TypeElement repo) {
        TypeArgs args = findEntityAndKey(repo);
        return args == null ? null : (TypeElement) args.entity.asElement();
    }

    /**
     * In-place emission (APT): the {@code Impl} is written in the repository's own package and
     * refers to the entity metamodel by its package-qualified name relative to that package.
     *
     * @return the fully-qualified name of the generated {@code Impl}, or {@code null} when
     *         {@code repo} is not a {@code @Repository}.
     */
    String writeIfRepository(TypeElement repo) throws IOException {
        TypeArgs args = findEntityAndKey(repo);
        if (args == null) return null;

        String pkg       = elements.getPackageOf(repo).getQualifiedName().toString();
        String simple    = repo.getSimpleName().toString();
        String implName  = simple + "Impl";
        // For nested repository interfaces (e.g. EnclosingTest.MyRepo), the simple name
        // alone won't resolve from a top-level generated Impl class. We compute the
        // access name relative to the package — for a top-level interface this is the
        // simple name; for a nested interface it's "EnclosingType.MyRepo".
        String repoFqn   = repo.getQualifiedName().toString();
        String repoRef   = pkg.isEmpty() ? repoFqn : repoFqn.substring(pkg.length() + 1);
        TypeElement entityType = (TypeElement) args.entity.asElement();
        String entitySimple = entityType.getSimpleName().toString();
        String entityPkg = elements.getPackageOf(entityType).getQualifiedName().toString();
        String metamodel = entityPkg.isEmpty() ? "_" + entitySimple : entityPkg + "._" + entitySimple;
        return emit(repo, args, pkg, implName, repoRef, metamodel);
    }

    /**
     * Relocated emission (Maven plugin / {@link ExternalRepositoryCodegen}): the {@code Impl} is
     * written into an application-owned package and refers to the (external) repository interface
     * and metamodel by their fully-qualified names, so it never shares a package with the
     * dependency jar that owns the {@code @Repository} (no Java Modules split package).
     *
     * @param targetPkg     application-owned package the {@code Impl} is emitted into
     * @param implName      simple name of the generated {@code Impl} class (collision-free)
     * @param metamodelFqn  fully-qualified name of the relocated metamodel (e.g. {@code app.gen._Book})
     * @return the fully-qualified name of the generated {@code Impl}, or {@code null} when
     *         {@code repo} is not a {@code @Repository}.
     */
    String writeRelocated(TypeElement repo, String targetPkg, String implName, String metamodelFqn)
            throws IOException {
        TypeArgs args = findEntityAndKey(repo);
        if (args == null) return null;
        String repoRef = repo.getQualifiedName().toString();
        return emit(repo, args, targetPkg, implName, repoRef, metamodelFqn);
    }

    private String emit(TypeElement repo, TypeArgs args, String pkg, String implName,
                        String repoRef, String metamodel) throws IOException {
        String fqn = pkg.isEmpty() ? implName : pkg + "." + implName;
        TypeElement entityType = (TypeElement) args.entity.asElement();
        String entityFqn = entityType.getQualifiedName().toString();
        String idFqn     = args.idBoxedFqn();

        java.util.Set<String> attributeNames = collectAttributeNames(entityType);
        // M8-3i — path resolver: greedy split of CamelCase chunks against the cross-entity registry.
        QueryMethodParser.PathResolver pathResolver =
                buildPathResolver(entityType.getQualifiedName().toString());

        try (PrintWriter w = new PrintWriter(sink.createSource(fqn))) {
            if (!pkg.isEmpty()) {
                w.println("package " + pkg + ";");
                w.println();
            }
            w.println("import io.vidocq.mansart.data.core.RepositoryRuntime;");
            w.println("import jakarta.inject.Inject;");
            w.println("import jakarta.inject.Singleton;");
            w.println("import javax.annotation.processing.Generated;");
            w.println("import java.util.List;");
            w.println("import java.util.Optional;");
            // Conditional import for @Transactional when needed
            boolean classTransactional = hasAnnotation(repo, "jakarta.transaction.Transactional");
            boolean anyMethodTransactional = abstractMethods(repo).stream()
                    .anyMatch(m -> hasAnnotation(m, "jakarta.transaction.Transactional"));
            if (classTransactional || anyMethodTransactional) {
                w.println("import jakarta.transaction.Transactional;");
            }
            w.println();
            w.println("/**");
            w.println(" * APT-generated CDI bean implementing the {@code @Repository} interface.");
            w.println(" * <p>The class is {@code @Singleton} (CDI pseudo-scope, no client proxy required)");
            w.println(" * and intentionally non-{@code final} so the container can subclass it to apply");
            w.println(" * Jakarta Interceptors — notably {@code @Transactional} inherited from the");
            w.println(" * {@code @Repository} interface (Jakarta Data 1.0 §X). Interception on a");
            w.println(" * pseudo-scoped bean works via direct subclassing of the implementation,");
            w.println(" * avoiding the normal-scope client-proxy validation pass.");
            w.println(" * <p>The repository is stateless — every call forwards to {@link RepositoryRuntime}.");
            w.println(" */");
            w.println("@Generated(\"io.vidocq.mansart.data.processor.MansartProcessor\")");
            w.println("@Singleton");
            if (classTransactional) {
                emitTransactionalAnnotation(w, repo, "");
            }
            w.println("public class " + implName + " implements " + repoRef + " {");
            w.println();
            w.println("    private final RepositoryRuntime runtime;");
            w.println();
            w.println("    @Inject");
            w.println("    public " + implName + "(RepositoryRuntime runtime) {");
            w.println("        this.runtime = runtime;");
            w.println("    }");
            w.println();

            DeclaredType repoType = (DeclaredType) repo.asType();
            for (ExecutableElement m : abstractMethods(repo)) {
                writeMethod(w, m, repo, repoType, metamodel, entityFqn, idFqn, attributeNames, pathResolver);
            }

            w.println("}");
        }
        return fqn;
    }

    private void writeMethod(PrintWriter w, ExecutableElement m, TypeElement repo, DeclaredType repoType,
                             String metamodel, String entityFqn, String idFqn,
                             java.util.Set<String> attributeNames,
                             QueryMethodParser.PathResolver pathResolver) {
        ExecutableType resolved = (ExecutableType) types.asMemberOf(repoType, m);
        String name = m.getSimpleName().toString();
        TypeMirror returnType = resolved.getReturnType();
        List<? extends TypeMirror> paramTypes = resolved.getParameterTypes();

        // Emit @Transactional on the method override whenever the interface method carries it.
        // Even if the class already has a class-level @Transactional, the method-level annotation
        // may specify a different TxType (e.g. REQUIRES_NEW) — CDI interceptor binding uses the
        // most specific (method-level) annotation, so we must always reproduce it faithfully.
        if (hasAnnotation(m, "jakarta.transaction.Transactional")) {
            emitTransactionalAnnotation(w, m, "    ");
        }
        w.println(signature(m, resolved, name));

        String body = jdqlBody(m, returnType, metamodel, attributeNames, paramTypes);
        if (body == null) body = lifecycleBody(m, returnType, metamodel, entityFqn, repo);
        if (body == null) body = bodyFor(name, paramTypes, returnType, metamodel, entityFqn);
        if (body == null) body = derivedBody(name, returnType, metamodel, entityFqn, attributeNames, paramTypes,
                pathResolver);
        if (body == null) {
            // MANSART-002: never emit a runtime-throwing stub silently — surface it at build time so an
            // unresolved derived query (typo, unsupported keyword) is seen now, not on first invocation.
            if (messager != null) {
                messager.printMessage(Diagnostic.Kind.WARNING,
                        "Mansart could not derive a query for '"
                                + ((TypeElement) m.getEnclosingElement()).getQualifiedName() + "." + name
                                + "'; the generated method will throw UnsupportedOperationException at runtime."
                                + " Check the method name and attribute spelling.", m);
            }
            w.println("        throw new UnsupportedOperationException(\""
                    + name + ": no matching CRUD or derived-query rule. Check method name and attribute spelling.\");");
        } else {
            w.println("        " + body);
        }
        w.println("    }");
        w.println();
    }

    /** The {@code @Override public} line implementing {@code m}, up to its opening brace. */
    private String signature(ExecutableElement m, ExecutableType resolved, String name) {
        StringBuilder sig = new StringBuilder("    @Override public ");
        appendTypeVariables(sig, resolved.getTypeVariables());
        sig.append(resolved.getReturnType()).append(' ').append(name).append('(');
        List<? extends TypeMirror> paramTypes = resolved.getParameterTypes();
        for (int i = 0; i < paramTypes.size(); i++) {
            if (i > 0) sig.append(", ");
            sig.append(paramTypes.get(i)).append(' ').append(p(i));
        }
        sig.append(')');
        List<? extends TypeMirror> thrown = m.getThrownTypes();
        for (int i = 0; i < thrown.size(); i++) {
            sig.append(i == 0 ? " throws " : ", ").append(thrown.get(i));
        }
        return sig.append(" {").toString();
    }

    private static void appendTypeVariables(StringBuilder sig,
                                            List<? extends javax.lang.model.type.TypeVariable> typeVars) {
        if (typeVars.isEmpty()) return;
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

    /**
     * The body of a derived query ({@code findByX}, {@code countByX}, …), or {@code null} when {@code name} is not
     * one. The {@code First} keyword keeps the first result, {@code First<N>} the first N (MANSART-013).
     */
    private String derivedBody(String name, TypeMirror returnType, String metamodel, String entityFqn,
                               java.util.Set<String> attributeNames, List<? extends TypeMirror> paramTypes,
                               QueryMethodParser.PathResolver pathResolver) {
        int[] firstN = {0};
        QueryMethodParser.QueryDescriptor desc = QueryMethodParser.parse(name, attributeNames, firstN, pathResolver);
        if (desc == null) return null;
        boolean paged = !paramTypes.isEmpty() && isPageRequest(paramTypes.get(paramTypes.size() - 1));
        boolean first = desc.op() == QueryMethodParser.Operation.FIND_ONE;
        if (paged || (!first && firstN[0] == 0)) {
            return derivedQueryBody(desc, returnType, metamodel, paramTypes);
        }
        var asFind = new QueryMethodParser.QueryDescriptor(QueryMethodParser.Operation.FIND, desc.predicates(),
                desc.combinator(), desc.orderBy());
        String query = derivedQueryBody(asFind, types.getDeclaredType(elements.getTypeElement(LIST)), metamodel,
                paramTypes);
        return "java.util.function.Supplier<java.util.List<" + entityFqn + ">> _query = () -> { " + query + " }; "
                + "java.util.List<" + entityFqn + "> _found = _query.get(); "
                + firstResults(returnType, first ? 1 : firstN[0]);
    }

    /** Returns, as {@code returnType}, the first {@code n} elements of the list {@code _found}. */
    private static String firstResults(TypeMirror returnType, int n) {
        String rt = returnType.toString();
        if (rt.startsWith(OPTIONAL)) return "return _found.stream().findFirst();";
        if (rt.startsWith(STREAM)) return "return _found.stream().limit(" + n + ");";
        if (rt.startsWith(LIST) || rt.startsWith(COLLECTION) || rt.startsWith(ITERABLE)) {
            return "return _found.stream().limit(" + n + ").toList();";
        }
        return "if (_found.isEmpty()) throw new jakarta.data.exceptions.EmptyResultException(\"No result\"); "
                + "return _found.get(0);";
    }

    /** Read {@code @jakarta.data.repository.Query("…")} from {@code m} and emit the body. */
    private String jdqlBody(ExecutableElement m, TypeMirror returnType, String metamodel,
                            java.util.Set<String> attributeNames, List<? extends TypeMirror> paramTypes) {
        String jdql = readQueryAnnotation(m);
        // The built-in repositories' @Find methods (findById, findAll…) have their own bodies (bodyFor).
        boolean builtIn = ((TypeElement) m.getEnclosingElement()).getQualifiedName().toString().startsWith("jakarta.data.");
        if (jdql == null && !builtIn && hasAnnotation(m, "jakarta.data.repository.Find")) jdql = findQuery(m, paramTypes);
        if (jdql == null) return null;

        TypeElement entityType = entityForRepo(m);
        String entitySimple = entityType == null ? "" : entityType.getSimpleName().toString();

        boolean trailingPageRequest = !paramTypes.isEmpty()
                && isPageRequest(paramTypes.get(paramTypes.size() - 1));
        boolean cursoredPage = isCursoredPageReturn(returnType);

        try {
            return JdqlParser.generateBody(jdql, m, metamodel, entitySimple, attributeNames,
                    returnType, trailingPageRequest, cursoredPage);
        } catch (JdqlParser.ParseException e) {
            throw new IllegalStateException((readQueryAnnotation(m) == null ? "@Find" : "@Query")
                    + " parse error on " + m.getSimpleName()
                    + ": " + e.getMessage() + " — JDQL: " + jdql, e);
        }
    }

    /**
     * The JDQL a {@code @Find} method stands for (mansart#41): each parameter equals the attribute its {@code @By}
     * names, else the attribute of its own name, all of them combined with {@code AND}; a trailing
     * {@code PageRequest} is left to the paging path; {@code @OrderBy} gives the {@code ORDER BY}. {@code null}, with
     * a warning, for a {@code Sort}, {@code Order} or {@code Limit} parameter, not supported yet.
     */
    private String findQuery(ExecutableElement m, List<? extends TypeMirror> paramTypes) {
        String where = findWhere(m, paramTypes);
        if (where == null) return null;
        String orderBy = findOrderBy(m);
        var jdql = new StringBuilder();
        if (!where.isEmpty()) jdql.append("WHERE ").append(where);
        if (!orderBy.isEmpty()) jdql.append(jdql.isEmpty() ? "" : " ").append("ORDER BY ").append(orderBy);
        return jdql.toString();
    }

    /** The {@code WHERE} condition of a {@code @Find} method, or {@code null} (warned) for an unsupported parameter. */
    private String findWhere(ExecutableElement m, List<? extends TypeMirror> paramTypes) {
        var where = new StringBuilder();
        var params = m.getParameters();
        for (int i = 0; i < params.size(); i++) {
            String type = paramTypes.get(i).toString().replaceAll("<.*", "");
            if (i == params.size() - 1 && isPageRequest(paramTypes.get(i))) continue;
            if (type.equals("jakarta.data.Sort") || type.equals("jakarta.data.Order") || type.equals("jakarta.data.Limit")
                    || type.equals("jakarta.data.Sort[]")) {
                if (messager != null) {
                    messager.printMessage(Diagnostic.Kind.WARNING, "Mansart does not support a " + type
                            + " parameter on a @Find method yet ("
                            + ((TypeElement) m.getEnclosingElement()).getQualifiedName() + "." + m.getSimpleName()
                            + "); use @OrderBy, or @Query with ORDER BY.", m);
                }
                return null;
            }
            if (!where.isEmpty()) where.append(" AND ");
            where.append(findAttribute(m, params.get(i))).append(" = ?").append(i + 1);
        }
        return where.toString();
    }

    /**
     * The attribute a {@code @Find} parameter is compared to: the one its {@code @By} names, the entity's id for
     * {@code @By(By.ID)} (MANSART-018), else the attribute of the parameter's own name.
     */
    private String findAttribute(ExecutableElement m, javax.lang.model.element.VariableElement param) {
        String attribute = annotationValue(param, "jakarta.data.repository.By");
        if (attribute == null) return param.getSimpleName().toString();
        if (!attribute.equals("id(this)")) return attribute;
        TypeElement entity = entityForRepo(m);
        for (var member : entity.getEnclosedElements()) {
            if (member.getKind() == javax.lang.model.element.ElementKind.FIELD
                    && hasAnnotation(member, "jakarta.persistence.Id")) {
                return member.getSimpleName().toString();
            }
        }
        return "id";
    }

    /** The {@code ORDER BY} list of a {@code @Find} method's {@code @OrderBy} annotations, in declaration order. */
    private String findOrderBy(ExecutableElement m) {
        var orderBy = new StringBuilder();
        for (var a : m.getAnnotationMirrors()) {
            String fqn = ((TypeElement) a.getAnnotationType().asElement()).getQualifiedName().toString();
            if (fqn.equals("jakarta.data.repository.OrderBy")) {
                appendOrder(orderBy, a);
            } else if (fqn.equals("jakarta.data.repository.OrderBy.List")) {
                appendOrderList(orderBy, a);
            }
        }
        return orderBy.toString();
    }

    private void appendOrderList(StringBuilder orderBy, javax.lang.model.element.AnnotationMirror list) {
        for (var entry : elements.getElementValuesWithDefaults(list).entrySet()) {
            if (!entry.getKey().getSimpleName().contentEquals(VALUE)) continue;
            for (Object item : (List<?>) entry.getValue().getValue()) {
                appendOrder(orderBy, (javax.lang.model.element.AnnotationMirror)
                        ((javax.lang.model.element.AnnotationValue) item).getValue());
            }
        }
    }

    private void appendOrder(StringBuilder orderBy, javax.lang.model.element.AnnotationMirror a) {
        String attribute = null;
        boolean descending = false;
        for (var entry : elements.getElementValuesWithDefaults(a).entrySet()) {
            String key = entry.getKey().getSimpleName().toString();
            if (key.equals(VALUE)) attribute = String.valueOf(entry.getValue().getValue());
            if (key.equals("descending")) descending = Boolean.TRUE.equals(entry.getValue().getValue());
        }
        if (attribute == null) return;
        if (!orderBy.isEmpty()) orderBy.append(", ");
        orderBy.append(attribute).append(descending ? " DESC" : " ASC");
    }

    /** The {@code value} of {@code element}'s annotation {@code annotationFqn}, or {@code null} without one. */
    private String annotationValue(javax.lang.model.element.Element element, String annotationFqn) {
        for (var a : element.getAnnotationMirrors()) {
            String fqn = ((TypeElement) a.getAnnotationType().asElement()).getQualifiedName().toString();
            if (!fqn.equals(annotationFqn)) continue;
            for (var entry : elements.getElementValuesWithDefaults(a).entrySet()) {
                if (entry.getKey().getSimpleName().contentEquals(VALUE)) return String.valueOf(entry.getValue().getValue());
            }
        }
        return null;
    }

    private String readQueryAnnotation(ExecutableElement m) {
        for (var a : m.getAnnotationMirrors()) {
            String fqn = ((TypeElement) a.getAnnotationType().asElement()).getQualifiedName().toString();
            if (!fqn.equals("jakarta.data.repository.Query")) continue;
            for (var entry : elements.getElementValuesWithDefaults(a).entrySet()) {
                if (entry.getKey().getSimpleName().contentEquals(VALUE)) {
                    return (String) entry.getValue().getValue();
                }
            }
        }
        return null;
    }

    /** Returns the entity {@link TypeElement} from the enclosing repo's parent {@code BasicRepository<E, K>}. */
    private TypeElement entityForRepo(ExecutableElement m) {
        TypeElement repo = (TypeElement) m.getEnclosingElement();
        TypeArgs args = findEntityAndKey(repo);
        return args == null ? null : (TypeElement) args.entity().asElement();
    }

    private String lifecycleBody(ExecutableElement m, TypeMirror returnType,
                                 String metamodel, String entityFqn, TypeElement repo) {
        // Only fire for methods declared on the user's repo, not for inherited methods
        // (BasicRepository.save / delete carry @Save / @Delete in the spec, but their
        // semantics are upsert / silent-delete — handled by bodyFor by name).
        if (!m.getEnclosingElement().equals(repo)) return null;
        if (m.getParameters().size() != 1) return null;
        boolean isVoid = returnType.getKind() == javax.lang.model.type.TypeKind.VOID;
        if (isBatch(m.getParameters().get(0).asType())) {
            return batchLifecycleBody(m, returnType, metamodel, entityFqn);
        }
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

    /** {@code true} for an array or an {@code Iterable} parameter: a lifecycle method on several entities. */
    private boolean isBatch(TypeMirror param) {
        if (param.getKind() == javax.lang.model.type.TypeKind.ARRAY) return true;
        TypeElement iterable = elements.getTypeElement(ITERABLE);
        return iterable != null && types.isAssignable(types.erasure(param), types.erasure(iterable.asType()));
    }

    /**
     * A lifecycle method on several entities (MANSART-016): the strict operation on each one, in order, returning
     * nothing, a list or an array of the results. {@code null} for a {@code @Delete} that returns a value.
     */
    private String batchLifecycleBody(ExecutableElement m, TypeMirror returnType, String metamodel,
                                      String entityFqn) {
        boolean isVoid = returnType.getKind() == javax.lang.model.type.TypeKind.VOID;
        String loop = "for (" + entityFqn + " _e : " + p(0) + ") ";
        if (hasJakartaAnnotation(m, "jakarta.data.repository.Delete")) {
            return isVoid ? loop + "runtime.deleteStrict(" + metamodel + ".$MODEL, _e);" : null;
        }
        String op;
        if (hasJakartaAnnotation(m, "jakarta.data.repository.Insert")) op = "insertStrict";
        else if (hasJakartaAnnotation(m, "jakarta.data.repository.Update")) op = "updateStrict";
        else if (hasJakartaAnnotation(m, "jakarta.data.repository.Save")) op = "save";
        else return null;
        String call = "runtime." + op + "(" + metamodel + ".$MODEL, _e)";
        if (isVoid) return loop + call + ";";
        String collect = "var _out = new java.util.ArrayList<" + entityFqn + ">(); " + loop + "_out.add(" + call + "); ";
        if (returnType.getKind() == javax.lang.model.type.TypeKind.ARRAY) {
            return collect + "return _out.toArray(new " + entityFqn + "[0]);";
        }
        return collect + "return _out;";
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

        // Mixed predicates with at least one In(Collection): switch to runtime-built Where.
        if (anyInWithCollection(d.predicates(), paramTypes, predicateArity)) {
            return mixedInBody(d, returnType, metamodel, paramTypes, predicateArity, trailingPageRequest);
        }

        String where = renderWhere(d, metamodel);
        String orderBy = renderOrderBy(d, metamodel);
        String args = "new java.lang.Object[]{" + renderArgsForPredicates(d.predicates()) + "}";

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

    private boolean anyInWithCollection(List<QueryMethodParser.Predicate> preds,
                                        List<? extends TypeMirror> paramTypes, int predicateArity) {
        int idx = 0;
        for (QueryMethodParser.Predicate p : preds) {
            if (idx >= predicateArity) return false;
            if (p.comparator() == QueryMethodParser.Comparator.IN
                    && isCollectionLike(paramTypes.get(idx))) return true;
            idx += paramConsumed(p);
        }
        return false;
    }

    private int paramConsumed(QueryMethodParser.Predicate p) {
        return switch (p.comparator()) {
            case BETWEEN              -> 2;
            case IS_NULL, IS_NOT_NULL -> 0;
            default                   -> 1;
        };
    }

    /**
     * Builds the {@code Where} AST and the args array at runtime so that {@code In(Collection)}
     * predicates can use the actual collection size for arity. Used whenever the method has at
     * least one {@code In} that takes a {@code Collection}.
     */
    private String mixedInBody(QueryMethodParser.QueryDescriptor d, TypeMirror returnType,
                               String metamodel, List<? extends TypeMirror> paramTypes,
                               int predicateArity, boolean trailingPageRequest) {
        String pkg = "io.vidocq.mansart.data.dialect.Where";
        StringBuilder sb = new StringBuilder();
        sb.append("java.util.List<").append(pkg).append("> _parts = new java.util.ArrayList<>(); ");
        sb.append("java.util.List<java.lang.Object> _args = new java.util.ArrayList<>(); ");

        int idx = 0;
        for (QueryMethodParser.Predicate p : d.predicates()) {
            // M8-3i — paths route through PathResolver at runtime; flat attrs use static field.
            String attr = attrRef(metamodel, p.attribute());
            String paramName = p(idx);
            // M7-28 — same wrapping logic as predicateExpr: comparator → IgnoreCase → Not.
            String partExpr;
            switch (p.comparator()) {
                case IN -> {
                    if (isCollectionLike(paramTypes.get(idx))) {
                        sb.append("java.util.Collection<?> _col").append(idx)
                          .append(" = (java.util.Collection<?>) ").append(paramName).append("; ");
                        sb.append("if (_col").append(idx).append(".isEmpty()) ")
                          .append(emptyInReturn(d.op(), returnType)).append(' ');
                        partExpr = "new " + pkg + ".In(" + attr + ", _col" + idx + ".size())";
                        partExpr = wrapIgnoreCaseAndNot(partExpr, p, pkg);
                        sb.append("_parts.add(").append(partExpr).append("); ");
                        sb.append("for (Object _v : _col").append(idx).append(") _args.add(_v); ");
                    } else {
                        partExpr = wrapIgnoreCaseAndNot("new " + pkg + ".In(" + attr + ", 1)", p, pkg);
                        sb.append("_parts.add(").append(partExpr).append("); ");
                        sb.append("_args.add(").append(paramName).append("); ");
                    }
                    idx += 1;
                }
                case BETWEEN -> {
                    partExpr = wrapIgnoreCaseAndNot("new " + pkg + ".Between(" + attr + ")", p, pkg);
                    sb.append("_parts.add(").append(partExpr).append("); ");
                    sb.append("_args.add(").append(p(idx)).append("); _args.add(").append(p(idx + 1)).append("); ");
                    idx += 2;
                }
                case IS_NULL, EMPTY -> {
                    partExpr = wrapIgnoreCaseAndNot("new " + pkg + ".IsNull(" + attr + ")", p, pkg);
                    sb.append("_parts.add(").append(partExpr).append("); ");
                }
                case IS_NOT_NULL, NOT_EMPTY -> {
                    partExpr = wrapIgnoreCaseAndNot("new " + pkg + ".IsNotNull(" + attr + ")", p, pkg);
                    sb.append("_parts.add(").append(partExpr).append("); ");
                }
                case TRUE -> {
                    partExpr = wrapIgnoreCaseAndNot("new " + pkg + ".Eq(" + attr + ")", p, pkg);
                    sb.append("_parts.add(").append(partExpr).append("); ");
                    sb.append("_args.add(java.lang.Boolean.TRUE); ");
                }
                case FALSE -> {
                    partExpr = wrapIgnoreCaseAndNot("new " + pkg + ".Eq(" + attr + ")", p, pkg);
                    sb.append("_parts.add(").append(partExpr).append("); ");
                    sb.append("_args.add(java.lang.Boolean.FALSE); ");
                }
                case CONTAINS -> {
                    partExpr = wrapIgnoreCaseAndNot("new " + pkg + ".Like(" + attr + ")", p, pkg);
                    sb.append("_parts.add(").append(partExpr).append("); ");
                    sb.append("_args.add(\"%\" + ").append(paramName).append(" + \"%\"); ");
                    idx += 1;
                }
                case STARTS_WITH -> {
                    partExpr = wrapIgnoreCaseAndNot("new " + pkg + ".Like(" + attr + ")", p, pkg);
                    sb.append("_parts.add(").append(partExpr).append("); ");
                    sb.append("_args.add(").append(paramName).append(" + \"%\"); ");
                    idx += 1;
                }
                case ENDS_WITH -> {
                    partExpr = wrapIgnoreCaseAndNot("new " + pkg + ".Like(" + attr + ")", p, pkg);
                    sb.append("_parts.add(").append(partExpr).append("); ");
                    sb.append("_args.add(\"%\" + ").append(paramName).append("); ");
                    idx += 1;
                }
                default -> {
                    partExpr = wrapIgnoreCaseAndNot(
                            "new " + pkg + '.' + comparatorClass(p.comparator()) + '(' + attr + ')',
                            p, pkg);
                    sb.append("_parts.add(").append(partExpr).append("); ");
                    sb.append("_args.add(").append(paramName).append("); ");
                    idx += 1;
                }
            }
        }

        String combine = d.combinator() == QueryMethodParser.Combinator.AND ? "and" : "or";
        sb.append(pkg).append(" _w = _parts.size() == 1 ? _parts.get(0) : ")
          .append(pkg).append('.').append(combine).append("(_parts.toArray(new ").append(pkg).append("[0])); ");
        sb.append("Object[] _xs = _args.toArray(); ");

        String orderBy = renderOrderBy(d, metamodel);
        if (trailingPageRequest && d.op() == QueryMethodParser.Operation.FIND) {
            String rt = isCursoredPageReturn(returnType) ? "queryCursored" : "queryPage";
            sb.append("return runtime.").append(rt).append('(').append(metamodel).append(".$MODEL, _w, ")
              .append(orderBy).append(", ").append(p(paramTypes.size() - 1)).append(", _xs);");
            return sb.toString();
        }
        return switch (d.op()) {
            case FIND     -> sb.append(findBody(returnType, metamodel, "_w", orderBy, "_xs")).toString();
            case FIND_ONE -> sb.append("return runtime.queryOne(").append(metamodel).append(".$MODEL, _w, _xs);").toString();
            case COUNT    -> sb.append("return runtime.countWhere(").append(metamodel).append(".$MODEL, _w, _xs);").toString();
            case EXISTS   -> sb.append("return runtime.existsWhere(").append(metamodel).append(".$MODEL, _w, _xs);").toString();
            case DELETE   -> sb.append(deleteBody(returnType, metamodel, "_w", "_xs")).toString();
        };
    }

    /** M7-28 — wraps a Where expression with IgnoreCase and/or Not according to predicate flags. */
    private String wrapIgnoreCaseAndNot(String inner, QueryMethodParser.Predicate p, String pkg) {
        String e = inner;
        if (p.ignoreCase()) e = "new " + pkg + ".IgnoreCase(" + e + ")";
        if (p.negated())    e = "new " + pkg + ".Not(" + e + ")";
        return e;
    }

    private String comparatorClass(QueryMethodParser.Comparator c) {
        return switch (c) {
            case EQ     -> "Eq";
            case NOT_EQ -> "NotEq";
            case LT     -> "Lt";
            case LTE    -> "Lte";
            case GT     -> "Gt";
            case GTE    -> "Gte";
            case LIKE   -> "Like";
            default     -> throw new IllegalStateException("unsupported comparator " + c);
        };
    }

    private String inCollectionBody(QueryMethodParser.QueryDescriptor d, TypeMirror returnType, String metamodel) {
        QueryMethodParser.Predicate pred = d.predicates().get(0);
        // M8-3i — paths route through PathResolver at runtime; flat attrs use static field.
        String attr = attrRef(metamodel, pred.attribute());
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
                if (s.startsWith(OPTIONAL))            yield "return java.util.Optional.empty();";
                if (s.startsWith(STREAM))       yield "return java.util.stream.Stream.empty();";
                if (s.startsWith(LIST)
                        || s.startsWith(COLLECTION)
                        || s.startsWith(ITERABLE))      yield "return java.util.List.of();";
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
        return s.equals(COLLECTION) || s.equals(LIST) || s.equals("java.util.Set")
                || s.equals(ITERABLE);
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
        if (rt.startsWith(OPTIONAL)) {
            return "return runtime.queryOne(" + metamodel + ".$MODEL, " + where + ", " + args + ");";
        }
        if (rt.startsWith(STREAM))    return "return " + list + ".stream();";
        if (rt.startsWith(LIST))             return "return " + list + ";";
        if (rt.startsWith(COLLECTION))       return "return " + list + ";";
        if (rt.startsWith(ITERABLE))         return "return " + list + ";";
        // Single-entity return → use queryOne and unwrap (throws if missing per Jakarta Data semantics)
        return "return runtime.queryOne(" + metamodel + ".$MODEL, " + where + ", " + args
                + ").orElseThrow(() -> new jakarta.data.exceptions.EmptyResultException(\"No result\"));";
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

    /**
     * M8-3i — emit an Attribute reference. Flat names map to {@code metamodel.attr} (compile-time
     * static field); dotted paths route through {@code PathResolver.resolve(model, "a.b.c")}
     * which returns a {@code JoinedAttribute} for the dialect to render with INNER JOIN aliasing.
     */
    private static String attrRef(String metamodel, String attr) {
        if (attr.indexOf('.') < 0) return metamodel + "." + attr;
        return "io.vidocq.mansart.data.core.PathResolver.resolve(" + metamodel + ".$MODEL, \"" + attr + "\")";
    }

    private String predicateExpr(QueryMethodParser.Predicate p, String metamodel) {
        // M8-3i — paths (a.b.c) route through PathResolver at runtime; flat attrs use the
        // compile-time static field reference.
        String attr = attrRef(metamodel, p.attribute());
        String pkg = "io.vidocq.mansart.data.dialect.Where";
        String inner = switch (p.comparator()) {
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
            // M7-28 — wildcards materialised in renderArgsForPredicates; the predicate
            // itself is a plain Like.
            case CONTAINS, STARTS_WITH, ENDS_WITH -> "new " + pkg + ".Like(" + attr + ")";
            // M7-28 — TRUE/FALSE: same Eq predicate, args populated with Boolean literal.
            case TRUE, FALSE                      -> "new " + pkg + ".Eq(" + attr + ")";
            case EMPTY                             -> "new " + pkg + ".IsNull(" + attr + ")";
            case NOT_EMPTY                         -> "new " + pkg + ".IsNotNull(" + attr + ")";
        };
        // M7-28 — IgnoreCase wraps the inner predicate so the dialect emits LOWER(col) <op> LOWER(?).
        // Done BEFORE Not so the SQL renders as NOT (LOWER(col) <op> LOWER(?)).
        if (p.ignoreCase()) {
            inner = "new " + pkg + ".IgnoreCase(" + inner + ")";
        }
        if (p.negated()) {
            inner = "new " + pkg + ".Not(" + inner + ")";
        }
        return inner;
    }

    private String renderOrderBy(QueryMethodParser.QueryDescriptor d, String metamodel) {
        if (d.orderBy().isEmpty()) return "io.vidocq.mansart.data.dialect.OrderBy.NONE";
        StringBuilder sb = new StringBuilder("new io.vidocq.mansart.data.dialect.OrderBy(java.util.List.of(");
        for (int i = 0; i < d.orderBy().size(); i++) {
            if (i > 0) sb.append(", ");
            QueryMethodParser.Order o = d.orderBy().get(i);
            sb.append("io.vidocq.mansart.data.dialect.OrderBy.Order.")
              .append(o.asc() ? "asc" : "desc")
              .append('(').append(attrRef(metamodel, o.attribute())).append(')');
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

    /**
     * M7-28 — emits the bound-args list expression for a sequence of predicates, materialising:
     * <ul>
     *   <li>{@code CONTAINS} → {@code "%" + p<i> + "%"}</li>
     *   <li>{@code STARTS_WITH} → {@code p<i> + "%"}</li>
     *   <li>{@code ENDS_WITH} → {@code "%" + p<i>}</li>
     *   <li>{@code TRUE} / {@code FALSE} → {@code Boolean.TRUE} / {@code Boolean.FALSE} (no method param)</li>
     *   <li>{@code IS_NULL} / {@code IS_NOT_NULL} / {@code EMPTY} / {@code NOT_EMPTY} → no arg</li>
     *   <li>{@code BETWEEN} → two parameter refs</li>
     *   <li>everything else → one parameter ref</li>
     * </ul>
     * The parameter index advances only when a real method parameter is consumed.
     */
    private String renderArgsForPredicates(List<QueryMethodParser.Predicate> preds) {
        StringBuilder sb = new StringBuilder();
        int paramIdx = 0;
        boolean first = true;
        for (QueryMethodParser.Predicate pr : preds) {
            switch (pr.comparator()) {
                case CONTAINS -> {
                    if (!first) sb.append(", ");
                    sb.append("\"%\" + ").append(p(paramIdx++)).append(" + \"%\"");
                    first = false;
                }
                case STARTS_WITH -> {
                    if (!first) sb.append(", ");
                    sb.append(p(paramIdx++)).append(" + \"%\"");
                    first = false;
                }
                case ENDS_WITH -> {
                    if (!first) sb.append(", ");
                    sb.append("\"%\" + ").append(p(paramIdx++));
                    first = false;
                }
                case TRUE -> {
                    if (!first) sb.append(", ");
                    sb.append("java.lang.Boolean.TRUE");
                    first = false;
                }
                case FALSE -> {
                    if (!first) sb.append(", ");
                    sb.append("java.lang.Boolean.FALSE");
                    first = false;
                }
                case BETWEEN -> {
                    if (!first) sb.append(", ");
                    sb.append(p(paramIdx++)).append(", ").append(p(paramIdx++));
                    first = false;
                }
                case IS_NULL, IS_NOT_NULL, EMPTY, NOT_EMPTY -> {
                    /* no argument */
                }
                default -> {
                    if (!first) sb.append(", ");
                    sb.append(p(paramIdx++));
                    first = false;
                }
            }
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
        boolean returnsStream = returnType.toString().startsWith(STREAM);
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
            default            -> arity == 1 ? crudBody(name, returnType, metamodel, entityFqn) : null;
        };
    }

    /**
     * The methods {@code CrudRepository} adds to {@code BasicRepository} (MANSART-017): {@code <S extends T> S
     * insert(S)}, {@code update(S)} and their {@code List<S>} forms {@code insertAll} and {@code updateAll}.
     */
    private String crudBody(String name, TypeMirror returnType, String metamodel, String entityFqn) {
        String op = switch (name) {
            case "insert", "insertAll" -> "insertStrict";
            case "update", "updateAll" -> "updateStrict";
            default -> null;
        };
        if (op == null) return null;
        if (!name.endsWith("All")) {
            String s = returnType.toString();
            return "@SuppressWarnings(\"unchecked\") " + s + " _r = (" + s + ") runtime." + op + "(" + metamodel
                    + ".$MODEL, (" + entityFqn + ") " + p(0) + "); return _r;";
        }
        if (!(returnType instanceof DeclaredType list) || list.getTypeArguments().size() != 1) return null;
        String s = list.getTypeArguments().get(0).toString();
        return "var _out = new java.util.ArrayList<" + s + ">(); for (" + s + " _e : " + p(0) + ") { "
                + "@SuppressWarnings(\"unchecked\") " + s + " _r = (" + s + ") runtime." + op + "(" + metamodel
                + ".$MODEL, (" + entityFqn + ") _e); _out.add(_r); } return _out;";
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

    /* ---------- @Transactional propagation helpers ---------- */

    /**
     * Returns true if {@code element} (a {@link TypeElement} or {@link ExecutableElement}) carries
     * the annotation identified by {@code annotationFqn}.
     */
    private boolean hasAnnotation(javax.lang.model.element.Element element, String annotationFqn) {
        for (var mirror : element.getAnnotationMirrors()) {
            if (((TypeElement) mirror.getAnnotationType().asElement())
                    .getQualifiedName().contentEquals(annotationFqn)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Emits the {@code @Transactional} annotation found on {@code element}, preserving its
     * {@code value()}, {@code rollbackOn()}, and {@code dontRollbackOn()} attributes if present.
     * {@code indent} is prepended to the emitted line (e.g. {@code ""} for class-level,
     * {@code "    "} for method-level).
     *
     * <p>Only explicitly set attributes (not defaults) are reproduced, so {@code @Transactional}
     * without attributes emits as {@code @Transactional} and one with {@code REQUIRES_NEW} emits
     * as {@code @Transactional(value = Transactional.TxType.REQUIRES_NEW)}.
     */
    private void emitTransactionalAnnotation(PrintWriter w,
                                             javax.lang.model.element.Element element,
                                             String indent) {
        for (var mirror : element.getAnnotationMirrors()) {
            TypeElement annType = (TypeElement) mirror.getAnnotationType().asElement();
            if (!annType.getQualifiedName().contentEquals("jakarta.transaction.Transactional")) continue;

            // Only iterate over explicitly set values (mirror.getElementValues() excludes defaults).
            java.util.List<String> parts = new java.util.ArrayList<>();
            for (var entry : mirror.getElementValues().entrySet()) {
                String attrName = entry.getKey().getSimpleName().toString();
                javax.lang.model.element.AnnotationValue av = entry.getValue();
                switch (attrName) {
                    case VALUE -> parts.add(VALUE + " = " + renderAnnotationValue(av));
                    case "rollbackOn" -> parts.add("rollbackOn = " + renderAnnotationValue(av));
                    case "dontRollbackOn" -> parts.add("dontRollbackOn = " + renderAnnotationValue(av));
                }
            }
            String attrs = parts.isEmpty() ? "" : "(" + String.join(", ", parts) + ")";
            w.println(indent + "@Transactional" + attrs);
            return;
        }
    }

    /**
     * Renders an {@link javax.lang.model.element.AnnotationValue} as a Java source string
     * that is safe to embed in generated code.
     *
     * <ul>
     *   <li>Enum constant → {@code TypeSimpleName.CONSTANT} (e.g. {@code Transactional.TxType.REQUIRES_NEW})</li>
     *   <li>Class literal → {@code Fqn.class}</li>
     *   <li>Array → {@code {elem1, elem2, …}}</li>
     *   <li>String, primitive, annotation → delegated to {@code av.toString()}</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private String renderAnnotationValue(javax.lang.model.element.AnnotationValue av) {
        Object value = av.getValue();
        if (value instanceof javax.lang.model.element.VariableElement ve) {
            // Enum constant — emit as EnclosingType.CONSTANT using the enclosing type's simple name
            // so the generated import statement (e.g. `import jakarta.transaction.Transactional;`)
            // resolves it correctly without a separate import for the nested TxType enum.
            TypeElement enclosing = (TypeElement) ve.getEnclosingElement();
            // Walk up to find the top-level enclosing type name (e.g. Transactional for TxType).
            String typePath = buildRelativeTypePath(enclosing);
            return typePath + "." + ve.getSimpleName();
        }
        if (value instanceof TypeMirror tm) {
            return tm + ".class";
        }
        if (value instanceof java.util.List<?> list) {
            var items = (java.util.List<? extends javax.lang.model.element.AnnotationValue>) list;
            if (items.isEmpty()) return "{}";
            if (items.size() == 1) return renderAnnotationValue(items.get(0));
            StringBuilder sb = new StringBuilder("{");
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(renderAnnotationValue(items.get(i)));
            }
            return sb.append("}").toString();
        }
        // String, primitives, nested annotations: toString() is correct source syntax.
        return av.toString();
    }

    /**
     * Builds a relative type path for a {@link TypeElement} using only the simple names of the
     * enclosing type chain, stopping at the top-level (package-level) class.
     * Example: {@code jakarta.transaction.Transactional.TxType} → {@code "Transactional.TxType"}.
     */
    private static String buildRelativeTypePath(TypeElement te) {
        java.util.Deque<String> parts = new java.util.ArrayDeque<>();
        javax.lang.model.element.Element current = te;
        while (current instanceof TypeElement t) {
            parts.addFirst(t.getSimpleName().toString());
            current = t.getEnclosingElement();
        }
        return String.join(".", parts);
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
