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
        String entitySimple = args.entity.asElement().getSimpleName().toString();
        String entityPkg = elements.getPackageOf((TypeElement) args.entity.asElement()).getQualifiedName().toString();
        String metamodel = entityPkg.isEmpty() ? "_" + entitySimple : entityPkg + "._" + entitySimple;
        String entityFqn = ((TypeElement) args.entity.asElement()).getQualifiedName().toString();
        String idFqn     = args.idBoxedFqn();

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
                writeMethod(w, m, repoType, metamodel, entityFqn, idFqn);
            }

            w.println("}");
        }
        return true;
    }

    private void writeMethod(PrintWriter w, ExecutableElement m, DeclaredType repoType,
                             String metamodel, String entityFqn, String idFqn) {
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

        String body = bodyFor(name, paramTypes, returnType, metamodel, entityFqn);
        if (body == null) {
            w.println("        throw new UnsupportedOperationException(\""
                    + name + ": derived/custom queries land in M3b.\");");
        } else {
            w.println("        " + body);
        }
        w.println("    }");
        w.println();
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
            case "findAll"     -> arity == 0 ? findAllBody(metamodel, returnsStream) : null;
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
