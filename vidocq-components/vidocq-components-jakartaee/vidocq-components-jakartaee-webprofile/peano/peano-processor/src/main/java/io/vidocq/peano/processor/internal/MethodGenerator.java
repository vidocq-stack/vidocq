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
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.ElementFilter;

/** Emits direct method dispatch and immutable signature constants inside the owning module. */
final class MethodGenerator {
    private static final String CASE_INDENT = "          ";
    private final ProcessingEnvironment environment;
    private final List<String> annotationClasses = new ArrayList<>();

    MethodGenerator(ProcessingEnvironment environment) { this.environment=environment; }

    void append(StringBuilder text, TypeElement model) throws IOException {
        List<ExecutableElement> methods = dispatchableMethods(model);
        for (ExecutableElement method : methods) checkSignature(method, model);
        appendDescriptors(text, methods, model);
        appendDispatchHeader(text);
        for(int i=0;i<methods.size();i++) appendCase(text, i, methods.get(i), model);
        text.append("        default: throw new jakarta.el.MethodNotFoundException(\"Unknown method descriptor\");\n")
                .append("      } } catch(jakarta.el.ELException failure) { throw failure; }\n")
                .append("      catch(java.lang.Throwable failure) { throw new jakarta.el.ELException(\"Generated method invocation failed\",failure); }\n")
                .append("    }\n");
        annotationClasses.forEach(text::append);
    }

    /** The public methods of the model (Object's excluded), one per signature, the most derived one winning. */
    private List<ExecutableElement> dispatchableMethods(TypeElement model) {
        List<ExecutableElement> available = ElementFilter.methodsIn(environment.getElementUtils().getAllMembers(model))
                .stream().filter(m->m.getModifiers().contains(Modifier.PUBLIC))
                .filter(m->!((TypeElement)m.getEnclosingElement()).getQualifiedName().contentEquals("java.lang.Object"))
                .sorted(java.util.Comparator.comparing(this::signature)).toList();
        var signatures = new java.util.TreeMap<String, ExecutableElement>();
        for (ExecutableElement method : available) {
            ExecutableElement previous = signatures.get(signature(method));
            if (previous == null || environment.getElementUtils().overrides(method,previous,model)
                    || environment.getElementUtils().hides(method,previous)) {
                signatures.put(signature(method), method);
            }
        }
        return new ArrayList<>(signatures.values());
    }

    private void checkSignature(ExecutableElement method, TypeElement model) throws IOException {
        accessible(environment.getTypeUtils().erasure(method.getReturnType()),model);
        for (var parameter : method.getParameters()) accessible(environment.getTypeUtils().erasure(parameter.asType()),model);
        ExecutableType bound=(ExecutableType)environment.getTypeUtils().asMemberOf((DeclaredType)model.asType(),method);
        for(int i=0;i<method.getParameters().size();i++) {
            if(!environment.getTypeUtils().isSameType(
                    environment.getTypeUtils().erasure(bound.getParameterTypes().get(i)),
                    environment.getTypeUtils().erasure(method.getParameters().get(i).asType()))) {
                throw new IOException("Inherited specialized generic methods are not supported yet: "+method);
            }
        }
    }

    private void appendDescriptors(StringBuilder text, List<ExecutableElement> methods, TypeElement model) throws IOException {
        text.append("    private static final io.vidocq.peano.spi.MethodDescriptor[] METHODS = {\n");
        for (ExecutableElement method : methods) {
            text.append("      new io.vidocq.peano.spi.MethodDescriptor(").append(quote(method.getSimpleName().toString()))
                    .append(", ").append(erased(method.getReturnType())).append(".class, new java.lang.Class<?>[]{");
            text.append(method.getParameters().stream().map(p->erased(p.asType())+".class")
                    .collect(java.util.stream.Collectors.joining(",")));
            text.append("}, ").append(method.isVarArgs()).append(", ")
                    .append(method.getModifiers().contains(Modifier.STATIC)).append(", new java.lang.annotation.Annotation[]{");
            var annotations=new ArrayList<String>();
            for(AnnotationMirror annotation : method.getAnnotationMirrors()) {
                if(runtime(annotation)) annotations.add(annotation(annotation,model));
            }
            text.append(String.join(",",annotations)).append("}),\n");
        }
        text.append("    };\n");
    }

    private static void appendDispatchHeader(StringBuilder text) {
        text.append("    private boolean methodTarget(jakarta.el.ELContext context, java.lang.Object base) {\n")
                .append("      java.util.Objects.requireNonNull(context); return base != null && (base.getClass()==targetType()")
                .append(" || base instanceof jakarta.el.ELClass c && c.getKlass()==targetType());\n    }\n")
                .append("    private boolean hasMethod(java.lang.String name, boolean staticOnly) {\n")
                .append("      for(var descriptor:METHODS) if(descriptor.name().equals(name) && (!staticOnly || descriptor.staticMethod())) return true;\n")
                .append("      return false;\n    }\n")
                .append("    public jakarta.el.MethodReference getMethodReference(jakarta.el.ELContext context, java.lang.Object base, java.lang.String name, java.lang.Class<?>[] types, java.lang.Object[] arguments) {\n")
                .append("      if(!methodTarget(context,base) || !hasMethod(name,base instanceof jakarta.el.ELClass)) return null;\n")
                .append("      int selected=io.vidocq.peano.spi.MethodSelection.select(context,METHODS,name,types,arguments,base instanceof jakarta.el.ELClass);\n")
                .append("      var descriptor=METHODS[selected];\n")
                .append("      return new jakarta.el.MethodReference(base,descriptor.methodInfo(),descriptor.annotations(),arguments==null?new java.lang.Object[0]:arguments.clone());\n")
                .append("    }\n")
                .append("    public java.lang.Object invoke(jakarta.el.ELContext context, java.lang.Object base, java.lang.Object method, java.lang.Class<?>[] types, java.lang.Object[] arguments) {\n")
                .append("      if(!methodTarget(context,base) || !(method instanceof java.lang.String name) || !hasMethod(name,base instanceof jakarta.el.ELClass)) return null;\n")
                .append("      context.setPropertyResolved(base,method);\n")
                .append("      java.lang.Object[] args=arguments==null?new java.lang.Object[0]:arguments;\n")
                .append("      int selected=io.vidocq.peano.spi.MethodSelection.select(context,METHODS,name,types,args,base instanceof jakarta.el.ELClass);\n")
                .append("      try { switch(selected) {\n");
    }

    /** One switch case: argument count check, conversions, then the direct call. */
    private void appendCase(StringBuilder text, int index, ExecutableElement method, TypeElement model) {
        text.append("        case ").append(index).append(": {\n");
        int fixed=method.getParameters().size()-(method.isVarArgs()?1:0);
        text.append(CASE_INDENT).append("if(args.length").append(method.isVarArgs()?"<":"!=").append(fixed)
                .append(") throw new jakarta.el.MethodNotFoundException(\"Invalid argument count\");\n");
        for(int j=0;j<fixed;j++) {
            String type=erased(method.getParameters().get(j).asType());
            text.append(CASE_INDENT).append(type).append(" a").append(j).append("=context.convertToType(args[")
                    .append(j).append("],").append(type).append(".class);\n");
        }
        if(method.isVarArgs()) appendVariableArguments(text, method, fixed);
        String receiver=method.getModifiers().contains(Modifier.STATIC)?model.getQualifiedName().toString()
                :"(("+model.getQualifiedName()+")base)";
        boolean returns=method.getReturnType().getKind()!=TypeKind.VOID;
        text.append(CASE_INDENT).append(returns?"return ":"").append(receiver).append('.').append(method.getSimpleName()).append('(');
        var args=new ArrayList<String>();
        for(int j=0;j<method.getParameters().size();j++) args.add("a"+j);
        text.append(String.join(",",args)).append(");").append(returns?"":" return null;").append("\n        }\n");
    }

    /** The trailing array: passed as is when given packed, otherwise built from the remaining arguments. */
    private void appendVariableArguments(StringBuilder text, ExecutableElement method, int fixed) {
        String array=erased(method.getParameters().getLast().asType());
        String component=array.substring(0,array.length()-2);
        text.append(CASE_INDENT).append(array).append(" a").append(fixed).append(";\n")
                .append(CASE_INDENT).append("if(args.length==").append(fixed+1).append(" && args[").append(fixed).append("] instanceof ")
                .append(array).append(") a").append(fixed).append("=context.convertToType(args[").append(fixed).append("],")
                .append(array).append(".class);\n")
                .append(CASE_INDENT).append("else { a").append(fixed).append("=").append(arrayAllocation(component,"args.length-"+fixed))
                .append("; for(int j=0;j<a").append(fixed).append(".length;j++) a").append(fixed)
                .append("[j]=context.convertToType(args[j+").append(fixed).append("],").append(component).append(".class); }\n");
    }

    private String signature(ExecutableElement method) {
        return method.getSimpleName()+"("+method.getParameters().stream().map(p->erased(p.asType()))
                .collect(java.util.stream.Collectors.joining(","))+")";
    }

    private String erased(TypeMirror type) { return environment.getTypeUtils().erasure(type).toString(); }

    private static String arrayAllocation(String component,String length) {
        int dimensions=0;
        while(component.endsWith("[]")) { component=component.substring(0,component.length()-2); dimensions++; }
        return "new "+component+"["+length+"]"+"[]".repeat(dimensions);
    }

    private boolean runtime(AnnotationMirror annotation) {
        for(var meta:annotation.getAnnotationType().asElement().getAnnotationMirrors()) {
            if(meta.getAnnotationType().toString().equals("java.lang.annotation.Retention")) {
                return meta.getElementValues().values().stream()
                        .anyMatch(v->v.getValue() instanceof VariableElement e && e.getSimpleName().contentEquals("RUNTIME"));
            }
        }
        return false;
    }

    private String annotation(AnnotationMirror mirror,TypeElement model) throws IOException {
        accessible(mirror.getAnnotationType(),model);
        var values=environment.getElementUtils().getElementValuesWithDefaults(mirror).entrySet().stream()
                .sorted(java.util.Comparator.comparing(e->e.getKey().getSimpleName().toString())).toList();
        String name="Annotation"+annotationClasses.size();
        // Reserve the index before recursively emitting nested annotation constants.
        int index=annotationClasses.size();
        annotationClasses.add("");
        String type=mirror.getAnnotationType().toString();
        var out=new StringBuilder("    private static final class "+name+" implements "+type+" {\n");
        for(var entry:values) {
            out.append("      public ").append(entry.getKey().getReturnType()).append(' ')
                    .append(entry.getKey().getSimpleName()).append("(){ return ")
                    .append(value(entry.getValue(),entry.getKey().getReturnType(),model)).append("; }\n");
        }
        out.append("      public java.lang.Class<? extends java.lang.annotation.Annotation> annotationType(){ return ").append(type).append(".class; }\n");
        out.append("      public boolean equals(java.lang.Object other){ return other instanceof ").append(type).append(" a");
        for(var entry:values) {
            String member=entry.getKey().getSimpleName().toString();
            TypeKind kind=entry.getKey().getReturnType().getKind();
            String equality=switch(kind) {
                case ARRAY -> "java.util.Arrays.equals("+member+"(),a."+member+"())";
                case FLOAT -> "java.lang.Float.floatToIntBits("+member+"())==java.lang.Float.floatToIntBits(a."+member+"())";
                case DOUBLE -> "java.lang.Double.doubleToLongBits("+member+"())==java.lang.Double.doubleToLongBits(a."+member+"())";
                case BOOLEAN,BYTE,SHORT,CHAR,INT,LONG -> member+"()==a."+member+"()";
                default -> member+"().equals(a."+member+"())";
            };
            out.append(" && (").append(equality).append(')');
        }
        out.append("; }\n      public int hashCode(){ return 0");
        for(var entry:values) {
            String member=entry.getKey().getSimpleName().toString();
            String hash=switch(entry.getKey().getReturnType().getKind()) {
                case ARRAY -> "java.util.Arrays.hashCode("+member+"())";
                case BOOLEAN -> "java.lang.Boolean.hashCode("+member+"())";
                case BYTE,SHORT,CHAR,INT -> "(int)"+member+"()";
                case LONG -> "java.lang.Long.hashCode("+member+"())";
                case FLOAT -> "java.lang.Float.hashCode("+member+"())";
                case DOUBLE -> "java.lang.Double.hashCode("+member+"())";
                default -> member+"().hashCode()";
            };
            out.append(" + ((127*").append(quote(member)).append(".hashCode()) ^ ").append(hash).append(')');
        }
        out.append("; }\n      public java.lang.String toString(){ return ").append(quote(mirror.toString())).append("; }\n    }\n");
        annotationClasses.set(index,out.toString());
        return "new "+name+"()";
    }

    private String value(AnnotationValue value,TypeMirror type,TypeElement model) throws IOException {
        Object constant=value.getValue();
        if(constant instanceof List<?> array) {
            String component=erased(((ArrayType)type).getComponentType());
            var members=new ArrayList<String>();
            for(Object element:array) members.add(value((AnnotationValue)element,((ArrayType)type).getComponentType(),model));
            return "new "+component+"[]{"+String.join(",",members)+"}";
        }
        if(constant instanceof AnnotationMirror nested) return annotation(nested,model);
        if(constant instanceof TypeMirror mirror) { accessible(mirror,model); return erased(mirror)+".class"; }
        if(constant instanceof VariableElement variable) {
            TypeElement owner=(TypeElement)variable.getEnclosingElement();
            accessible(owner.asType(),model);
            return owner.getQualifiedName()+"."+variable.getSimpleName();
        }
        if(constant instanceof String string) return quote(string);
        if(constant instanceof Character character) return "(char)"+(int)character.charValue();
        if(constant instanceof Long number) return number+"L";
        if(constant instanceof Float number) return floatLiteral(number);
        if(constant instanceof Double number) return doubleLiteral(number);
        return constant.toString();
    }

    private static String floatLiteral(Float number) {
        if(number.isNaN()) return "java.lang.Float.NaN";
        if(number==Float.POSITIVE_INFINITY) return "java.lang.Float.POSITIVE_INFINITY";
        if(number==Float.NEGATIVE_INFINITY) return "java.lang.Float.NEGATIVE_INFINITY";
        return Float.toHexString(number)+"f";
    }

    private static String doubleLiteral(Double number) {
        if(number.isNaN()) return "java.lang.Double.NaN";
        if(number==Double.POSITIVE_INFINITY) return "java.lang.Double.POSITIVE_INFINITY";
        if(number==Double.NEGATIVE_INFINITY) return "java.lang.Double.NEGATIVE_INFINITY";
        return Double.toHexString(number);
    }

    void accessible(TypeMirror type,TypeElement model) throws IOException {
        if(type instanceof ArrayType array) { accessible(array.getComponentType(),model); return; }
        if(!(type instanceof DeclaredType declared)) return;
        String pkg=environment.getElementUtils().getPackageOf(model).getQualifiedName().toString();
        ModuleElement owner=environment.getElementUtils().getModuleOf(declared.asElement());
        ModuleElement application=environment.getElementUtils().getModuleOf(model);
        String typePackage=environment.getElementUtils().getPackageOf(declared.asElement()).getQualifiedName().toString();
        if(owner!=null && !owner.isUnnamed() && !owner.equals(application)) {
            boolean exported=owner.getDirectives().stream()
                    .filter(d->d instanceof ModuleElement.ExportsDirective)
                    .map(d->(ModuleElement.ExportsDirective)d)
                    .anyMatch(d->d.getPackage().getQualifiedName().contentEquals(typePackage)
                            && (d.getTargetModules()==null || d.getTargetModules().contains(application)));
            if(!exported) throw new IOException("Method metadata type is not exported to the application module: "+type);
        }
        for(Element current=declared.asElement();current instanceof TypeElement;current=current.getEnclosingElement()) {
            if(current.getModifiers().contains(Modifier.PRIVATE)
                    || !current.getModifiers().contains(Modifier.PUBLIC)
                    && !environment.getElementUtils().getPackageOf(current).getQualifiedName().contentEquals(pkg)) {
                throw new IOException("Method metadata type is inaccessible from the generated table: "+type);
            }
        }
    }

    private static String quote(String value) {
        return "\""+value.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")
                .replace("\t","\\t").replace("\b","\\b").replace("\f","\\f")+"\"";
    }
}

