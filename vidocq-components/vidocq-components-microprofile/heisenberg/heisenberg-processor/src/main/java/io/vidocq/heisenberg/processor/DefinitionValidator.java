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
package io.vidocq.heisenberg.processor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * The MicroProfile Fault Tolerance 4.1 definition errors, checked on the language model: the same rules, and the
 * same messages, as the run-time validation of {@code HeisenbergExtension} and {@code FallbackResolver}, which stays
 * the portable path for a class compiled without this processor (vidocq-workspace#40, item 6).
 *
 * <p>The annotations checked are the ones the runtime applies to a method: its own, else the nearest class's (the
 * resolution of {@link FaultTolerancePoliciesProcessor}). A fallback method is looked up as the runtime does: in the
 * bean class, its superclasses and their interfaces, with the same visibility and compatibility rules.</p>
 */
final class DefinitionValidator {

    private static final String FALLBACK_DEFAULT = "org.eclipse.microprofile.faulttolerance.Fallback.DEFAULT";
    private static final String FALLBACK_HANDLER = "org.eclipse.microprofile.faulttolerance.FallbackHandler";

    private static final String VALUE = "value";

    private final Elements elements;
    private final Types types;

    DefinitionValidator(ProcessingEnvironment env) {
        this.elements = env.getElementUtils();
        this.types = env.getTypeUtils();
    }

    /**
     * The definition errors of one method of {@code bean}.
     *
     * @param policies the method's resolved annotations by simple name ({@code Retry}, {@code Fallback}, ...)
     * @return the messages, each naming the bean class and the method; empty when the definition is valid
     */
    List<String> check(TypeElement bean, ExecutableElement method, Map<String, AnnotationMirror> policies) {
        List<String> errors = new ArrayList<>();
        String where = ": " + bean.getQualifiedName() + "#" + method;
        AnnotationMirror asynchronous = policies.get("Asynchronous");
        if (asynchronous != null) {
            checkAsynchronous(method, where, errors);
        }
        if (policies.containsKey("Bulkhead")) {
            checkBulkhead(values(policies.get("Bulkhead")), asynchronous != null, where, errors);
        }
        if (policies.containsKey("CircuitBreaker")) {
            checkCircuitBreaker(values(policies.get("CircuitBreaker")), where, errors);
        }
        if (policies.containsKey("Retry")) {
            checkRetry(values(policies.get("Retry")), where, errors);
        }
        if (policies.containsKey("Timeout") && number(values(policies.get("Timeout")), VALUE) < 0) {
            errors.add("@Timeout value must be >= 0" + where);
        }
        if (policies.containsKey("Fallback")) {
            checkFallback(bean, method, values(policies.get("Fallback")), errors);
        }
        return errors;
    }

    private void checkAsynchronous(ExecutableElement method, String where, List<String> errors) {
        TypeMirror erased = types.erasure(method.getReturnType());
        if (!isSubtypeOfNamed(erased, "java.util.concurrent.CompletionStage")
                && !isSubtypeOfNamed(erased, "java.util.concurrent.Future")) {
            errors.add("@Asynchronous requires Future or CompletionStage return type" + where);
        }
    }

    private static void checkBulkhead(Map<String, Object> bulkhead, boolean asynchronous, String where,
                                      List<String> errors) {
        if (number(bulkhead, VALUE) < 1) {
            errors.add("@Bulkhead value must be >= 1" + where);
        }
        if (asynchronous && number(bulkhead, "waitingTaskQueue") < 0) {
            errors.add("@Bulkhead waitingTaskQueue must be >= 0 for @Asynchronous" + where);
        }
    }

    private static void checkCircuitBreaker(Map<String, Object> breaker, String where, List<String> errors) {
        double failureRatio = number(breaker, "failureRatio");
        if (failureRatio < 0.0d || failureRatio > 1.0d) {
            errors.add("@CircuitBreaker failureRatio must be within [0,1]" + where);
        }
        if (number(breaker, "requestVolumeThreshold") < 1) {
            errors.add("@CircuitBreaker requestVolumeThreshold must be >= 1" + where);
        }
        if (number(breaker, "successThreshold") < 1) {
            errors.add("@CircuitBreaker successThreshold must be >= 1" + where);
        }
    }

    private static void checkRetry(Map<String, Object> retry, String where, List<String> errors) {
        double maxRetries = number(retry, "maxRetries");
        double delay = number(retry, "delay");
        double maxDuration = number(retry, "maxDuration");
        if (maxRetries < -1) {
            errors.add("@Retry maxRetries must be >= -1" + where);
        }
        if (delay < 0) {
            errors.add("@Retry delay must be >= 0" + where);
        }
        if (number(retry, "jitter") < 0) {
            errors.add("@Retry jitter must be >= 0" + where);
        }
        if (maxDuration < 0) {
            errors.add("@Retry maxDuration must be >= 0" + where);
        }
        if (maxDuration > 0 && maxDuration < delay) {
            errors.add("@Retry maxDuration must be >= delay when maxDuration > 0" + where);
        }
    }

    // ------------------------------------------------------------------
    // @Fallback
    // ------------------------------------------------------------------

    private void checkFallback(TypeElement bean, ExecutableElement method, Map<String, Object> fallback,
                               List<String> errors) {
        String fallbackMethod = (String) fallback.get("fallbackMethod");
        TypeMirror handler = (TypeMirror) fallback.get(VALUE);
        boolean hasMethod = !fallbackMethod.isEmpty();
        boolean hasHandler = !isNamed(handler, FALLBACK_DEFAULT);
        String where = ": " + bean.getQualifiedName() + "#" + method;
        if (hasMethod == hasHandler) {
            errors.add("@Fallback must define exactly one strategy between fallbackMethod and value()" + where);
        } else if (hasMethod) {
            if (!hasCompatibleMethod(bean, method, fallbackMethod)) {
                errors.add("Invalid fallbackMethod '" + fallbackMethod + "' for " + method + where);
            }
        } else if (handlerReturnTypeMismatch(method, handler)) {
            errors.add("FallbackHandler return type mismatch" + where);
        }
    }

    /** A {@code FallbackHandler<X>} whose X, a class, cannot stand for the guarded method's (boxed) return type. */
    private boolean handlerReturnTypeMismatch(ExecutableElement method, TypeMirror handler) {
        if (!(handler instanceof DeclaredType declared)) {
            return false;
        }
        for (TypeMirror itf : ((TypeElement) declared.asElement()).getInterfaces()) {
            if (itf instanceof DeclaredType d && ((TypeElement) d.asElement()).getQualifiedName()
                    .contentEquals(FALLBACK_HANDLER)) {
                List<? extends TypeMirror> arguments = d.getTypeArguments();
                if (arguments.size() == 1 && arguments.get(0) instanceof DeclaredType argument) {
                    return !types.isSubtype(types.erasure(argument), types.erasure(boxed(method.getReturnType())));
                }
                return false;
            }
        }
        return false;
    }

    private TypeMirror boxed(TypeMirror type) {
        if (type.getKind() == TypeKind.VOID) {
            return elements.getTypeElement("java.lang.Void").asType();
        }
        return type.getKind().isPrimitive() ? types.boxedClass(types.getPrimitiveType(type.getKind())).asType() : type;
    }

    private boolean hasCompatibleMethod(TypeElement bean, ExecutableElement guarded, String name) {
        TypeElement current = bean;
        while (current != null && !current.getQualifiedName().contentEquals("java.lang.Object")) {
            for (ExecutableElement candidate : methodsOf(current)) {
                if (isCandidate(candidate, bean, guarded, name)) {
                    return true;
                }
            }
            if (onInterfaces(current, bean, guarded, name)) {
                return true;
            }
            TypeMirror superclass = current.getSuperclass();
            current = superclass.getKind() == TypeKind.DECLARED ? (TypeElement) ((DeclaredType) superclass).asElement()
                    : null;
        }
        return false;
    }

    private boolean onInterfaces(TypeElement type, TypeElement bean, ExecutableElement guarded, String name) {
        for (TypeMirror itf : type.getInterfaces()) {
            TypeElement element = (TypeElement) ((DeclaredType) itf).asElement();
            for (ExecutableElement candidate : methodsOf(element)) {
                if (isCandidate(candidate, bean, guarded, name)) {
                    return true;
                }
            }
            if (onInterfaces(element, bean, guarded, name)) {
                return true;
            }
        }
        return false;
    }

    private static List<ExecutableElement> methodsOf(TypeElement type) {
        return javax.lang.model.util.ElementFilter.methodsIn(type.getEnclosedElements());
    }

    private boolean isCandidate(ExecutableElement candidate, TypeElement bean, ExecutableElement guarded, String name) {
        return candidate.getSimpleName().contentEquals(name) && visibleFrom(bean, candidate)
                && compatible(guarded, candidate);
    }

    /** The runtime's visibility rule: abstract never; private on the bean class only; package-private in its package. */
    private boolean visibleFrom(TypeElement bean, ExecutableElement candidate) {
        java.util.Set<Modifier> modifiers = candidate.getModifiers();
        if (modifiers.contains(Modifier.ABSTRACT)) {
            return false;
        }
        TypeElement owner = (TypeElement) candidate.getEnclosingElement();
        boolean interfaceMethod = owner.getKind().isInterface();
        if (interfaceMethod || modifiers.contains(Modifier.PUBLIC)) {
            return true;
        }
        if (modifiers.contains(Modifier.PRIVATE)) {
            return owner.equals(bean);
        }
        boolean samePackage = elements.getPackageOf(owner).equals(elements.getPackageOf(bean));
        return samePackage || (modifiers.contains(Modifier.PROTECTED) && types.isSubtype(types.erasure(bean.asType()),
                types.erasure(owner.asType())));
    }

    private boolean compatible(ExecutableElement guarded, ExecutableElement candidate) {
        List<? extends VariableElement> guardedParameters = guarded.getParameters();
        List<? extends VariableElement> candidateParameters = candidate.getParameters();
        if (guardedParameters.size() != candidateParameters.size()) {
            return false;
        }
        for (int i = 0; i < guardedParameters.size(); i++) {
            if (!assignable(guardedParameters.get(i).asType(), candidateParameters.get(i).asType())) {
                return false;
            }
        }
        if (!assignable(candidate.getReturnType(), guarded.getReturnType())) {
            return false;
        }
        for (int i = 0; i < guardedParameters.size(); i++) {
            TypeMirror left = guardedParameters.get(i).asType();
            TypeMirror right = candidateParameters.get(i).asType();
            if (!containsTypeVariable(left) && !containsTypeVariable(right) && !types.isSameType(left, right)) {
                return false;
            }
        }
        return true;
    }

    /** {@code Class.isAssignableFrom} on erasures: primitives only by identity, void only to void. */
    private boolean assignable(TypeMirror from, TypeMirror to) {
        if (from.getKind().isPrimitive() || to.getKind().isPrimitive()
                || from.getKind() == TypeKind.VOID || to.getKind() == TypeKind.VOID) {
            return from.getKind() == to.getKind();
        }
        return types.isSubtype(types.erasure(from), types.erasure(to));
    }

    private static boolean containsTypeVariable(TypeMirror type) {
        return switch (type.getKind()) {
            case TYPEVAR -> true;
            case ARRAY -> containsTypeVariable(((ArrayType) type).getComponentType());
            case WILDCARD -> {
                WildcardType wildcard = (WildcardType) type;
                yield (wildcard.getExtendsBound() != null && containsTypeVariable(wildcard.getExtendsBound()))
                        || (wildcard.getSuperBound() != null && containsTypeVariable(wildcard.getSuperBound()));
            }
            case DECLARED -> ((DeclaredType) type).getTypeArguments().stream()
                    .anyMatch(DefinitionValidator::containsTypeVariable);
            default -> false;
        };
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private boolean isSubtypeOfNamed(TypeMirror erased, String name) {
        TypeElement target = elements.getTypeElement(name);
        return target != null && types.isSubtype(erased, types.erasure(target.asType()));
    }

    private static boolean isNamed(TypeMirror type, String name) {
        return type instanceof DeclaredType d && ((TypeElement) d.asElement()).getQualifiedName().contentEquals(name);
    }

    private Map<String, Object> values(AnnotationMirror mirror) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry
                : elements.getElementValuesWithDefaults(mirror).entrySet()) {
            values.put(entry.getKey().getSimpleName().toString(), entry.getValue().getValue());
        }
        return values;
    }

    private static double number(Map<String, Object> values, String member) {
        return ((Number) values.get(member)).doubleValue();
    }
}
