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
package io.vidocq.peano.processor.internal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.util.ElementFilter;

/** Emits statically compiled application-owned EL lambda adapters (EL 6.0 section 1.25.8). */
final class FunctionalGenerator {
    private final ProcessingEnvironment environment;

    FunctionalGenerator(ProcessingEnvironment environment) {
        this.environment=environment;
    }

    void append(StringBuilder text, TypeElement model) throws IOException {
        requireFunctionalAnnotation(model);
        var types=environment.getTypeUtils();
        var methods=functionalCandidates(model);
        if (methods.isEmpty()) throw new IOException("Selected interface has no functional method");
        ExecutableElement method=mostSpecific(model,methods);
        var signature=memberType(model,method);
        if (!signature.getTypeVariables().isEmpty())
            throw new IOException("Generic functional methods cannot be adapted");
        var accessibility=new MethodGenerator(environment);
        accessibility.accessible(types.erasure(signature.getReturnType()),model);
        for (var parameter:signature.getParameterTypes())
            accessibility.accessible(types.erasure(parameter),model);
        String target=model.getQualifiedName().toString();
        String result=types.erasure(signature.getReturnType()).toString();
        text.append("    public <T> T convertToType(jakarta.el.ELContext context, java.lang.Object value, java.lang.Class<T> target) {\n")
                .append("      if (target!=").append(target).append(".class || !(value instanceof jakarta.el.LambdaExpression lambda)) return null;\n")
                .append("      if (context!=null) context.setPropertyResolved(true);\n")
                .append("      return (T)new Adapter(lambda,context);\n    }\n")
                .append("    private static final class Adapter implements ").append(target).append(" {\n")
                .append("      private final jakarta.el.LambdaExpression lambda;\n")
                .append("      private final jakarta.el.ELContext context;\n")
                .append("      Adapter(jakarta.el.LambdaExpression lambda,jakarta.el.ELContext context) { this.lambda=lambda; this.context=context; }\n")
                .append("      public ").append(result).append(' ').append(method.getSimpleName()).append('(');
        var parameters=new ArrayList<String>();
        var arguments=new ArrayList<String>();
        for (int i=0;i<signature.getParameterTypes().size();i++) {
            parameters.add(types.erasure(signature.getParameterTypes().get(i))+" a"+i);
            arguments.add("a"+i);
        }
        text.append(String.join(",",parameters)).append(") {\n")
                .append("        java.lang.Object[] arguments=new java.lang.Object[]{").append(String.join(",",arguments)).append("};\n")
                .append("        java.lang.Object result=context==null ? lambda.invoke(arguments) : lambda.invoke(context,arguments);\n");
        if (signature.getReturnType().getKind()!=TypeKind.VOID) {
            text.append("        return ");
            if (!signature.getReturnType().getKind().isPrimitive()) text.append('(').append(result).append(')');
            text.append("(context==null ? jakarta.el.ExpressionFactory.newInstance().coerceToType(result,")
                    .append(result).append(".class) : context.convertToType(result,").append(result).append(".class));\n");
        }
        text.append("      }\n    }\n");
    }

    private static void requireFunctionalAnnotation(TypeElement model) throws IOException {
        if (model.getModifiers().contains(Modifier.SEALED)
                || model.getAnnotationMirrors().stream().noneMatch(annotation ->
                        annotation.getAnnotationType().toString().equals("java.lang.FunctionalInterface"))) {
            throw new IOException("Selected interface must be non-sealed and annotated with @FunctionalInterface");
        }
    }

    /** The abstract instance methods of the interface that do not restate a public Object method. */
    private List<ExecutableElement> functionalCandidates(TypeElement model) {
        var types=environment.getTypeUtils();
        var elements=environment.getElementUtils();
        var methods=new ArrayList<ExecutableElement>();
        var objectMethods=ElementFilter.methodsIn(elements.getTypeElement("java.lang.Object").getEnclosedElements())
                .stream().filter(method->method.getModifiers().contains(Modifier.PUBLIC)).toList();
        for (var method:ElementFilter.methodsIn(elements.getAllMembers(model))) {
            if (!method.getModifiers().contains(Modifier.ABSTRACT)
                    || method.getModifiers().contains(Modifier.STATIC)) continue;
            var signature=memberType(model,method);
            boolean objectMethod=objectMethods.stream().anyMatch(other ->
                    method.getSimpleName().contentEquals(other.getSimpleName())
                    && types.isSubsignature(signature,(ExecutableType)other.asType()));
            if (!objectMethod) methods.add(method);
        }
        return methods;
    }

    /** The candidate with the most specific return type; distinct functional methods are rejected. */
    private ExecutableElement mostSpecific(TypeElement model, List<ExecutableElement> methods) throws IOException {
        var types=environment.getTypeUtils();
        ExecutableElement method=methods.getFirst();
        var signature=memberType(model,method);
        for (var candidate:methods) {
            var other=memberType(model,candidate);
            if (!method.getSimpleName().contentEquals(candidate.getSimpleName())
                    || !(types.isSubsignature(signature,other) || types.isSubsignature(other,signature)))
                throw new IOException("Selected interface has multiple functional methods");
            if (types.isSubtype(other.getReturnType(),signature.getReturnType())) {
                method=candidate;
                signature=other;
            }
        }
        return method;
    }

    private ExecutableType memberType(TypeElement model, ExecutableElement method) {
        return (ExecutableType)environment.getTypeUtils().asMemberOf((DeclaredType)model.asType(),method);
    }
}
