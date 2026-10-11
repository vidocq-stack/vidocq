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
package io.vidocq.peano.compiler;

import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.attribute.ConstantValueAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.Objects;
import javax.lang.model.SourceVersion;
import jakarta.el.ELException;
import io.vidocq.peano.core.internal.syntax.Ast;
import io.vidocq.peano.core.internal.syntax.ExpressionParser;
import io.vidocq.peano.core.internal.syntax.Template;
import io.vidocq.peano.core.internal.syntax.TokenKind;

/** Build-time compilation of known EL templates. */
public final class ExpressionCompiler {
    private static final ClassDesc BASE=ClassDesc.of("io.vidocq.peano.spi.CompiledValueExpression");
    private static final ClassDesc CONTEXT=ClassDesc.of("jakarta.el.ELContext");
    private static final ClassDesc OBJECT=ClassDesc.of("java.lang.Object");
    private static final ClassDesc STRING=ClassDesc.of("java.lang.String");
    private static final ClassDesc CLASS=ClassDesc.of("java.lang.Class");
    private static final ClassDesc VOID=ClassDesc.ofDescriptor("V");
    private static final ClassDesc BOOLEAN=ClassDesc.ofDescriptor("Z");
    private static final ClassDesc LONG=ClassDesc.ofDescriptor("J");
    private static final ClassDesc DOUBLE=ClassDesc.ofDescriptor("D");
    private final ClassDesc owner;
    private final java.util.Map<Ast.Literal,String> strings=new java.util.LinkedHashMap<>();
    private final java.util.Set<String> functionKeys=new java.util.LinkedHashSet<>();
    private final java.util.Map<Ast.Lambda,Integer> lambdas=new java.util.LinkedHashMap<>();
    private java.util.Set<String> locals=java.util.Set.of();

    private ExpressionCompiler(ClassDesc owner,Template template) {
        this.owner=owner;
        for (var part:template.parts())
            if (part instanceof Template.Evaluation evaluation) collectStrings(evaluation.body());
    }

    private void collectStrings(Ast node) {
        if (node instanceof Ast.Literal literal) {
            if (literal.token().kind()==TokenKind.STRING && !literal.token().value().isEmpty())
                strings.putIfAbsent(literal,"_literal"+strings.size());
        } else if (node instanceof Ast.Unary unary) collectStrings(unary.operand());
        else if (node instanceof Ast.Binary binary) { collectStrings(binary.left()); collectStrings(binary.right()); }
        else if (node instanceof Ast.Conditional conditional) {
            collectStrings(conditional.condition()); collectStrings(conditional.whenTrue()); collectStrings(conditional.whenFalse());
        } else if (node instanceof Ast.Property property) {
            collectStrings(property.base()); collectStrings(property.key());
        } else if (node instanceof Ast.Call call) {
            if (call.target() instanceof Ast.Identifier identifier && !locals.contains(identifier.name()))
                functionKeys.add(":"+identifier.name());
            collectStrings(call.target()); call.arguments().forEach(this::collectStrings);
        } else if (node instanceof Ast.Function function) {
            functionKeys.add(function.prefix()+":"+function.name()); function.arguments().forEach(this::collectStrings);
        } else if (node instanceof Ast.Lambda lambda) {
            lambdas.putIfAbsent(lambda,lambdas.size());
            var previous=locals;
            var nested=new java.util.HashSet<>(locals); nested.addAll(lambda.parameters()); locals=nested;
            try { collectStrings(lambda.body()); } finally { locals=previous; }
        } else if (node instanceof Ast.ListLiteral list) list.elements().forEach(this::collectStrings);
        else if (node instanceof Ast.SetLiteral set) set.elements().forEach(this::collectStrings);
        else if (node instanceof Ast.MapLiteral map) {
            for (var entry:map.entries()) { collectStrings(entry.key()); collectStrings(entry.value()); }
        }
    }

    /**
     * Emits a public CompiledValueExpression subclass with a public Class constructor.
     * The constructor receives the expected result type; the caller owns output placement.
     * This slice accepts scalar literals and operators, including comparisons, concatenation and sequences.
     * Standalone root identifiers also support resolver metadata and creation-time variable capture.
     * Other nodes fail at build time, with no interpreter fallback.
     *
     * @param binaryName qualified binary name of the application-owned emitted class
     * @param source original EL template
     * @return a fresh Java 25 class file
     * @throws ELException for invalid syntax or unsupported nodes
     * @throws IllegalArgumentException for an invalid binary name
     */
    public static byte[] compile(String binaryName,String source) {
        Objects.requireNonNull(binaryName,"binaryName");
        Objects.requireNonNull(source,"source");
        if (!binaryName.contains(".") || !SourceVersion.isName(binaryName,SourceVersion.RELEASE_25))
            throw new IllegalArgumentException("Expected a qualified Java binary name: "+binaryName);
        Template template=ExpressionParser.parse(source);
        if (template.parts().size()==1 && template.parts().getFirst() instanceof Template.Evaluation evaluation
                && evaluation.body() instanceof Ast.Identifier identifier)
            return compileRoot(binaryName,source,identifier.name());
        if (template.parts().stream().anyMatch(part->part instanceof Template.Evaluation evaluation
                && containsResolved(evaluation.body()))) return compileResolved(binaryName,source,template);
        StringBuilder identity=new StringBuilder(template.kind()==Template.Kind.LITERAL ? "literal:" : "expression:");
        ClassDesc rawType=STRING;
        for (Template.Part part:template.parts()) {
            if (part instanceof Template.Text text) key(identity,"text",text.value());
            else {
                Ast body=((Template.Evaluation)part).body();
                validate(body,identity,source);
                if (template.parts().size()==1) rawType=rawType(body);
            }
        }
        ClassDesc owner=ClassDesc.of(binaryName);
        var compiler=new ExpressionCompiler(owner,template);
        ClassDesc resultType=rawType;
        try {
            return ClassFile.of().build(owner,builder-> {
                builder.withFlags(ClassFile.ACC_PUBLIC|ClassFile.ACC_FINAL|ClassFile.ACC_SUPER)
                        .withVersion(ClassFile.JAVA_25_VERSION,0).withSuperclass(BASE);
                builder.withField("serialVersionUID",LONG,
                        field->field.withFlags(ClassFile.ACC_PRIVATE|ClassFile.ACC_STATIC|ClassFile.ACC_FINAL)
                                .with(ConstantValueAttribute.of(serialVersion(identity.toString()))));
                for (String field:compiler.strings.values())
                    builder.withField(field,STRING,ClassFile.ACC_PRIVATE|ClassFile.ACC_FINAL);
                builder.withMethodBody("<init>",MethodTypeDesc.of(VOID,CLASS),ClassFile.ACC_PUBLIC,code-> {
                    code.aload(0).ldc(source).ldc(identity.toString()).aload(1);
                    if (template.kind()==Template.Kind.LITERAL) code.iconst_1(); else code.iconst_0();
                    if (resultType==null) code.aconst_null(); else code.ldc(resultType);
                    code.invokespecial(BASE,"<init>",MethodTypeDesc.of(VOID,STRING,STRING,CLASS,BOOLEAN,CLASS));
                    compiler.initializeStrings(code); code.return_();
                });
            builder.withMethodBody("evaluate",MethodTypeDesc.of(OBJECT,CONTEXT),ClassFile.ACC_PROTECTED,code-> {
                if (template.parts().size()==1 && template.parts().getFirst() instanceof Template.Evaluation evaluation) {
                        compiler.emit(code,evaluation.body());
                } else {
                    var buffer=ClassDesc.of("java.lang.StringBuilder");
                    code.new_(buffer).dup().invokespecial(buffer,"<init>",MethodTypeDesc.of(VOID));
                    for (Template.Part part:template.parts()) {
                        if (part instanceof Template.Text text) code.ldc(text.value());
                        else {
                            code.aload(1);
                                compiler.emit(code,((Template.Evaluation)part).body());
                            code.ldc(STRING).invokevirtual(CONTEXT,"convertToType",MethodTypeDesc.of(OBJECT,OBJECT,CLASS))
                                    .checkcast(STRING);
                        }
                        code.invokevirtual(buffer,"append",MethodTypeDesc.of(buffer,STRING));
                    }
                    code.invokevirtual(buffer,"toString",MethodTypeDesc.of(STRING));
                }
                code.areturn();
            });
                if (template.parts().size()==1 && template.parts().getFirst() instanceof Template.Evaluation part
                        && dynamicType(part.body())) {
                    builder.withMethodBody("getType",MethodTypeDesc.of(CLASS,CONTEXT),ClassFile.ACC_PUBLIC,code-> {
                        code.aload(1).ldc("context").invokestatic(ClassDesc.of("java.util.Objects"),"requireNonNull",
                                MethodTypeDesc.of(OBJECT,OBJECT,STRING)).pop();
                        var absent=code.newLabel();
                        code.aload(0).aload(1).invokevirtual(owner,"evaluate",MethodTypeDesc.of(OBJECT,CONTEXT))
                                .dup().ifnull(absent).invokevirtual(OBJECT,"getClass",MethodTypeDesc.of(CLASS))
                                .areturn().labelBinding(absent).pop().aconst_null().areturn();
                    });
                }
        });
        } catch (IllegalArgumentException failure) {
            throw new ELException("Cannot emit compiled expression "+binaryName+": "+failure.getMessage(),failure);
        }
    }

    private void initializeStrings(CodeBuilder code) {
        for (var entry:strings.entrySet()) {
            code.aload(0).new_(STRING).dup().ldc(entry.getKey().token().value())
                    .invokespecial(STRING,"<init>",MethodTypeDesc.of(VOID,STRING))
                    .putfield(owner,entry.getValue(),STRING);
        }
    }

    private static byte[] compileRoot(String binaryName,String source,String name) {
        var identity=new StringBuilder("expression:");
        key(identity,"identifier",name);
        var rootBase=ClassDesc.of("io.vidocq.peano.spi.CompiledRootValueExpression");
        try {
            return ClassFile.of().build(ClassDesc.of(binaryName),builder-> {
                builder.withFlags(ClassFile.ACC_PUBLIC|ClassFile.ACC_FINAL|ClassFile.ACC_SUPER)
                        .withVersion(ClassFile.JAVA_25_VERSION,0).withSuperclass(rootBase);
                builder.withField("serialVersionUID",LONG,
                        field->field.withFlags(ClassFile.ACC_PRIVATE|ClassFile.ACC_STATIC|ClassFile.ACC_FINAL)
                                .with(ConstantValueAttribute.of(serialVersion(identity.toString()))));
                builder.withMethodBody("<init>",MethodTypeDesc.of(VOID,CLASS),ClassFile.ACC_PUBLIC,code-> {
                    code.aload(0).ldc(source).ldc(identity.toString()).aload(1).ldc(name).aconst_null()
                            .invokespecial(rootBase,"<init>",MethodTypeDesc.of(VOID,STRING,STRING,CLASS,STRING,CONTEXT)).return_();
                });
                builder.withMethodBody("<init>",MethodTypeDesc.of(VOID,CONTEXT,CLASS),ClassFile.ACC_PUBLIC,code-> {
                    code.aload(0).ldc(source).ldc(identity.toString()).aload(2).ldc(name).aload(1)
                            .ldc("creationContext").invokestatic(ClassDesc.of("java.util.Objects"),"requireNonNull",
                                    MethodTypeDesc.of(OBJECT,OBJECT,STRING)).checkcast(CONTEXT)
                            .invokespecial(rootBase,"<init>",MethodTypeDesc.of(VOID,STRING,STRING,CLASS,STRING,CONTEXT)).return_();
                });
            });
        } catch (IllegalArgumentException failure) {
            throw new ELException("Cannot emit compiled expression "+binaryName+": "+failure.getMessage(),failure);
        }
    }

    private static boolean containsResolved(Ast node) {
        return switch (node) {
            case Ast.Identifier _, Ast.Property _, Ast.Call _, Ast.Function _, Ast.Lambda _ -> true;
            case Ast.Unary unary -> containsResolved(unary.operand());
            case Ast.Binary binary -> binary.operator().kind()==TokenKind.ASSIGN
                    || containsResolved(binary.left()) || containsResolved(binary.right());
            case Ast.Conditional conditional -> containsResolved(conditional.condition())
                    || containsResolved(conditional.whenTrue()) || containsResolved(conditional.whenFalse());
            case Ast.ListLiteral list -> list.elements().stream().anyMatch(ExpressionCompiler::containsResolved);
            case Ast.SetLiteral set -> set.elements().stream().anyMatch(ExpressionCompiler::containsResolved);
            case Ast.MapLiteral map -> map.entries().stream().anyMatch(entry->containsResolved(entry.key()) || containsResolved(entry.value()));
            default -> false;
        };
    }

    private static byte[] compileResolved(String binaryName,String source,Template template) {
        var identity=new StringBuilder("expression:");
        var names=new java.util.LinkedHashSet<String>();
        for (var part:template.parts()) {
            if (part instanceof Template.Text text) key(identity,"text",text.value());
            else validate(((Template.Evaluation)part).body(),identity,source,names);
        }
        Ast body=template.parts().size()==1 && template.parts().getFirst() instanceof Template.Evaluation evaluation
                ? evaluation.body() : null;
        var owner=ClassDesc.of(binaryName);
        var compiler=new ExpressionCompiler(owner,template);
        var pathBase=ClassDesc.of("io.vidocq.peano.spi.CompiledPropertyValueExpression");
        var parent=!compiler.lambdas.isEmpty() ? ClassDesc.of("io.vidocq.peano.spi.CompiledLambdaValueExpression")
                : compiler.functionKeys.isEmpty() ? pathBase : ClassDesc.of("io.vidocq.peano.spi.CompiledFunctionValueExpression");
        var reference=ClassDesc.of("jakarta.el.ValueReference");
        var namesType=ClassDesc.ofDescriptor("[Ljava/lang/String;");
        try {
            return ClassFile.of().build(owner,builder-> {
                builder.withFlags(ClassFile.ACC_PUBLIC|ClassFile.ACC_FINAL|ClassFile.ACC_SUPER)
                        .withVersion(ClassFile.JAVA_25_VERSION,0).withSuperclass(parent);
                builder.withField("serialVersionUID",LONG,
                        field->field.withFlags(ClassFile.ACC_PRIVATE|ClassFile.ACC_STATIC|ClassFile.ACC_FINAL)
                                .with(ConstantValueAttribute.of(serialVersion(identity.toString()))));
                for (String field:compiler.strings.values())
                    builder.withField(field,STRING,ClassFile.ACC_PRIVATE|ClassFile.ACC_FINAL);
                for (boolean capture:new boolean[]{false,true}) {
                    var constructor=capture ? MethodTypeDesc.of(VOID,CONTEXT,CLASS) : MethodTypeDesc.of(VOID,CLASS);
                    builder.withMethodBody("<init>",constructor,ClassFile.ACC_PUBLIC,code-> {
                        code.aload(0).ldc(source).ldc(identity.toString()).aload(capture ? 2 : 1);
                        if (capture) code.aload(1).ldc("creationContext")
                                .invokestatic(ClassDesc.of("java.util.Objects"),"requireNonNull",MethodTypeDesc.of(OBJECT,OBJECT,STRING))
                                .checkcast(CONTEXT);
                        else code.aconst_null();
                        code.loadConstant(names.size()).anewarray(STRING);
                        int index=0;
                        for (String name:names) code.dup().loadConstant(index++).ldc(name).aastore();
                        if (body==null) code.iconst_1(); else code.iconst_0();
                        if (parent.equals(pathBase))
                            code.invokespecial(parent,"<init>",MethodTypeDesc.of(VOID,STRING,STRING,CLASS,CONTEXT,namesType,BOOLEAN));
                        else {
                            code.loadConstant(compiler.functionKeys.size()).anewarray(STRING);
                            int functionIndex=0;
                            for (String key:compiler.functionKeys) code.dup().loadConstant(functionIndex++).ldc(key).aastore();
                            code.invokespecial(parent,"<init>",MethodTypeDesc.of(VOID,STRING,STRING,CLASS,CONTEXT,namesType,BOOLEAN,namesType));
                        }
                        compiler.initializeStrings(code); code.return_();
                    });
                }
                builder.withMethodBody("evaluate",MethodTypeDesc.of(OBJECT,CONTEXT),ClassFile.ACC_PROTECTED,code-> {
                    if (body!=null) compiler.emit(code,body);
                    else {
                        var buffer=ClassDesc.of("java.lang.StringBuilder");
                        code.new_(buffer).dup().invokespecial(buffer,"<init>",MethodTypeDesc.of(VOID));
                        for (var part:template.parts()) {
                            if (part instanceof Template.Text text) code.ldc(text.value());
                            else {
                                code.aload(1); compiler.emit(code,((Template.Evaluation)part).body());
                                code.ldc(STRING).invokevirtual(CONTEXT,"convertToType",MethodTypeDesc.of(OBJECT,OBJECT,CLASS)).checkcast(STRING);
                            }
                            code.invokevirtual(buffer,"append",MethodTypeDesc.of(buffer,STRING));
                        }
                        code.invokevirtual(buffer,"toString",MethodTypeDesc.of(STRING));
                    }
                    code.areturn();
                });
                if (!compiler.lambdas.isEmpty()) builder.withMethodBody("evaluateLambda",
                        MethodTypeDesc.of(OBJECT,CONTEXT,ClassDesc.ofDescriptor("I")),ClassFile.ACC_PROTECTED,code-> {
                    for (var entry:compiler.lambdas.entrySet()) {
                        var next=code.newLabel();
                        code.iload(2).loadConstant(entry.getValue()).if_icmpne(next);
                        compiler.emit(code,entry.getKey().body()); code.areturn().labelBinding(next);
                    }
                    var exception=ClassDesc.of("jakarta.el.ELException");
                    code.new_(exception).dup().ldc("Unknown compiled lambda body")
                            .invokespecial(exception,"<init>",MethodTypeDesc.of(VOID,STRING)).athrow();
                });
                if (body instanceof Ast.Property property) builder.withMethodBody("locate",MethodTypeDesc.of(reference,CONTEXT),ClassFile.ACC_PROTECTED,code-> {
                    int base=code.allocateLocal(java.lang.classfile.TypeKind.REFERENCE);
                    compiler.emit(code,property.base()); code.astore(base);
                    compiler.requirePresent(code,base,"null base");
                    int key=code.allocateLocal(java.lang.classfile.TypeKind.REFERENCE);
                    compiler.emit(code,property.key()); code.astore(key);
                    compiler.requirePresent(code,key,"null property");
                    code.new_(reference).dup().aload(base).aload(key)
                            .invokespecial(reference,"<init>",MethodTypeDesc.of(VOID,OBJECT,OBJECT)).areturn();
                });
            });
        } catch (IllegalArgumentException failure) {
            throw new ELException("Cannot emit compiled expression "+binaryName+": "+failure.getMessage(),failure);
        }
    }

    private void requirePresent(CodeBuilder code,int slot,String property) {
        var present=code.newLabel(); var exception=ClassDesc.of("jakarta.el.PropertyNotFoundException");
        code.aload(slot).ifnonnull(present).new_(exception).dup().ldc("Unresolved property "+property)
                .invokespecial(exception,"<init>",MethodTypeDesc.of(VOID,STRING)).athrow().labelBinding(present);
    }

    private static boolean dynamicType(Ast body) {
        if (!(body instanceof Ast.Literal literal)) return true;
        try { constant(literal); return false; }
        catch (ELException failure) { return true; }
    }

    private static void key(StringBuilder key,String kind,String value) {
        key.append(kind).append(':').append(value.length()).append(':').append(value);
    }

    private static long serialVersion(String identity) {
        try {
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            String key="peano-compiled-value-v3:"+identity;
            // Hash UTF-16 code units directly, including unpaired surrogate values.
            for (int i=0;i<key.length();i++) {
                char unit=key.charAt(i);
                digest.update((byte)(unit>>>8));
                digest.update((byte)unit);
            }
            return java.nio.ByteBuffer.wrap(digest.digest()).getLong();
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new ELException("Cannot derive compiled expression serialization identity",failure);
        }
    }

    private static void validate(Ast node,StringBuilder identity,String source) {
        validate(node,identity,source,null);
    }

    private static void validate(Ast node,StringBuilder identity,String source,java.util.Set<String> names) {
        if (node instanceof Ast.Literal literal) {
            literalKey(identity,literal);
        } else if (names!=null && node instanceof Ast.Identifier identifier) {
            key(identity,"identifier",identifier.name()); names.add(identifier.name());
        } else if (names!=null && node instanceof Ast.Property property) {
            key(identity,"property","");
            validate(property.base(),identity,source,names); validate(property.key(),identity,source,names);
        } else if (names!=null && node instanceof Ast.Call call) {
            key(identity,"call",Integer.toString(call.arguments().size()));
            validate(call.target(),identity,source,names);
            for (Ast argument:call.arguments()) validate(argument,identity,source,names);
        } else if (names!=null && node instanceof Ast.Function function) {
            key(identity,"function",function.prefix()); key(identity,"name",function.name());
            key(identity,"arguments",Integer.toString(function.arguments().size()));
            for (Ast argument:function.arguments()) validate(argument,identity,source,names);
        } else if (names!=null && node instanceof Ast.Lambda lambda) {
            key(identity,"lambda",Integer.toString(lambda.parameters().size()));
            for (String parameter:lambda.parameters()) key(identity,"parameter",parameter);
            validate(lambda.body(),identity,source,excluding(names,lambda.parameters()));
        } else if (node instanceof Ast.Unary unary) {
            key(identity,"unary",unary.operator().kind().name());
            if (signedInteger(unary)) {
                literalKey(identity,(Ast.Literal)unary.operand());
            } else validate(unary.operand(),identity,source,names);
        } else if (node instanceof Ast.Binary binary
                && (arithmetic(binary.operator().kind()) || logical(binary.operator().kind())
                    || comparison(binary.operator().kind()) || binary.operator().kind()==TokenKind.CONCAT
                    || binary.operator().kind()==TokenKind.SEMICOLON || binary.operator().kind()==TokenKind.ASSIGN)) {
            key(identity,"binary",binary.operator().kind().name());
            validate(binary.left(),identity,source,names);
            validate(binary.right(),identity,source,names);
        } else if (node instanceof Ast.Conditional conditional) {
            key(identity,"conditional","");
            validate(conditional.condition(),identity,source,names);
            validate(conditional.whenTrue(),identity,source,names);
            validate(conditional.whenFalse(),identity,source,names);
        } else if (node instanceof Ast.ListLiteral list) {
            key(identity,"list",Integer.toString(list.elements().size()));
            for (Ast element:list.elements()) validate(element,identity,source,names);
        } else if (node instanceof Ast.SetLiteral set) {
            key(identity,"set",Integer.toString(set.elements().size()));
            for (Ast element:set.elements()) validate(element,identity,source,names);
        } else if (node instanceof Ast.MapLiteral map) {
            key(identity,"map",Integer.toString(map.entries().size()));
            for (var entry:map.entries()) { validate(entry.key(),identity,source,names); validate(entry.value(),identity,source,names); }
        } else throw new ELException("Unsupported compiled expression node "+node.getClass().getSimpleName()
                +" in template: "+source);
    }

    private static java.util.Set<String> excluding(java.util.Set<String> target,java.util.List<String> locals) {
        return new java.util.AbstractSet<>() {
            @Override public boolean add(String value) { return !locals.contains(value) && target.add(value); }
            @Override public java.util.Iterator<String> iterator() { return target.iterator(); }
            @Override public int size() { return target.size(); }
        };
    }

    private static void literalKey(StringBuilder identity,Ast.Literal literal) {
        var token=literal.token();
        key(identity,token.kind().name(),token.value()==null ? token.text() : token.value());
    }

    private static boolean signedInteger(Ast.Unary unary) {
        return unary.operator().kind()==TokenKind.MINUS && unary.operand() instanceof Ast.Literal literal
                && literal.token().kind()==TokenKind.INTEGER;
    }

    private static long signed(Ast.Literal literal) {
        try { return Long.parseLong("-"+literal.token().text()); }
        catch (NumberFormatException failure) {
            throw new ELException("Invalid numeric literal at offset "+literal.token().start(),failure);
        }
    }

    private static ClassDesc rawType(Ast node) {
        if (node instanceof Ast.Literal literal) return rawType(literal);
        if (node instanceof Ast.Identifier || node instanceof Ast.Property || node instanceof Ast.Call || node instanceof Ast.Function
                || node instanceof Ast.Lambda) return OBJECT;
        if (node instanceof Ast.ListLiteral) return ClassDesc.of("java.util.ArrayList");
        if (node instanceof Ast.SetLiteral) return ClassDesc.of("java.util.LinkedHashSet");
        if (node instanceof Ast.MapLiteral) return ClassDesc.of("java.util.LinkedHashMap");
        if (node instanceof Ast.Conditional conditional) {
            ClassDesc yes=rawType(conditional.whenTrue());
            return Objects.equals(yes,rawType(conditional.whenFalse())) ? yes : OBJECT;
        }
        if (node instanceof Ast.Binary binary) {
            if (logical(binary.operator().kind()) || comparison(binary.operator().kind()))
                return ClassDesc.of("java.lang.Boolean");
            if (binary.operator().kind()==TokenKind.CONCAT) return STRING;
            if (binary.operator().kind()==TokenKind.SEMICOLON) return rawType(binary.right());
            if (binary.operator().kind()==TokenKind.ASSIGN) return rawType(binary.right());
            if (dynamicNumber(binary.left()) || dynamicNumber(binary.right())) return OBJECT;
            if (rawType(binary.left())==null && rawType(binary.right())==null)
                return ClassDesc.of("java.lang.Long");
            return ClassDesc.of(binary.operator().kind()==TokenKind.DIVIDE
                    || floating(binary.left()) || floating(binary.right()) ? "java.lang.Double" : "java.lang.Long");
        }
        var unary=(Ast.Unary)node;
        if (unary.operator().kind()!=TokenKind.MINUS) return ClassDesc.of("java.lang.Boolean");
        ClassDesc operand=rawType(unary.operand());
        if (operand==null) return ClassDesc.of("java.lang.Long");
        if (dynamicNumber(unary.operand())) return OBJECT;
        if (operand.equals(STRING)) {
            String text=((Ast.Literal)unary.operand()).token().value();
            return ClassDesc.of(text.indexOf('.')>=0 || text.indexOf('e')>=0 || text.indexOf('E')>=0
                    ? "java.lang.Double" : "java.lang.Long");
        }
        return operand;
    }

    private static ClassDesc rawType(Ast.Literal literal) {
        return switch (literal.token().kind()) {
            case INTEGER -> ClassDesc.of("java.lang.Long");
            case FLOATING_POINT -> ClassDesc.of("java.lang.Double");
            case TRUE,FALSE -> ClassDesc.of("java.lang.Boolean");
            case STRING -> STRING;
            case NULL -> null;
            default -> throw new ELException("Invalid literal token");
        };
    }

    private static Object constant(Ast.Literal literal) {
        var token=literal.token();
        try {
            return switch (token.kind()) {
                case INTEGER -> Long.valueOf(token.text());
                case FLOATING_POINT -> Double.valueOf(token.text());
                case STRING -> token.value();
                case TRUE -> true;
                case FALSE -> false;
                case NULL -> null;
                default -> throw new ELException("Invalid literal token");
            };
        } catch (NumberFormatException failure) {
            throw new ELException("Invalid numeric literal at offset "+token.start(),failure);
        }
    }

    private void emitNames(CodeBuilder code,java.util.Collection<String> names) {
        code.loadConstant(names.size()).anewarray(STRING);
        int index=0;
        for (String name:names) code.dup().loadConstant(index++).ldc(name).aastore();
    }

    private void emit(CodeBuilder code,Ast node) {
        if (node instanceof Ast.Literal literal) { emit(code,literal); return; }
        if (node instanceof Ast.Lambda lambda) {
            var names=new java.util.LinkedHashSet<String>();
            validate(lambda.body(),new StringBuilder(),"lambda",excluding(names,lambda.parameters()));
            code.aload(0).aload(1).loadConstant(lambdas.get(lambda));
            emitNames(code,lambda.parameters()); emitNames(code,names);
            code.invokespecial(ClassDesc.of("io.vidocq.peano.spi.CompiledLambdaValueExpression"),"createLambda",
                    MethodTypeDesc.of(ClassDesc.of("jakarta.el.LambdaExpression"),CONTEXT,ClassDesc.ofDescriptor("I"),
                            ClassDesc.ofDescriptor("[Ljava/lang/String;"),ClassDesc.ofDescriptor("[Ljava/lang/String;")));
            return;
        }
        if (node instanceof Ast.ListLiteral list) { emitList(code,list.elements()); return; }
        if (node instanceof Ast.SetLiteral set) {
            emitList(code,set.elements());
            int values=code.allocateLocal(java.lang.classfile.TypeKind.REFERENCE);
            var type=ClassDesc.of("java.util.LinkedHashSet");
            code.astore(values).new_(type).dup().aload(values)
                    .invokespecial(type,"<init>",MethodTypeDesc.of(VOID,ClassDesc.of("java.util.Collection")));
            return;
        }
        if (node instanceof Ast.MapLiteral map) {
            var type=ClassDesc.of("java.util.LinkedHashMap");
            int slot=code.allocateLocal(java.lang.classfile.TypeKind.REFERENCE);
            code.new_(type).dup().invokespecial(type,"<init>",MethodTypeDesc.of(VOID)).astore(slot);
            for (var entry:map.entries()) {
                code.aload(slot); emit(code,entry.key()); emit(code,entry.value());
                code.invokevirtual(type,"put",MethodTypeDesc.of(OBJECT,OBJECT,OBJECT)).pop();
            }
            code.aload(slot); return;
        }
        var pathBase=ClassDesc.of(lambdas.isEmpty() ? "io.vidocq.peano.spi.CompiledPropertyValueExpression"
                : "io.vidocq.peano.spi.CompiledLambdaValueExpression");
        var functionBase=ClassDesc.of(lambdas.isEmpty() ? "io.vidocq.peano.spi.CompiledFunctionValueExpression"
                : "io.vidocq.peano.spi.CompiledLambdaValueExpression");
        if (node instanceof Ast.Function function) {
            int arguments=code.allocateLocal(java.lang.classfile.TypeKind.REFERENCE);
            code.loadConstant(function.arguments().size()).anewarray(OBJECT);
            for (int i=0;i<function.arguments().size();i++) {
                code.dup().loadConstant(i); emit(code,function.arguments().get(i)); code.aastore();
            }
            code.astore(arguments).aload(0).aload(1).ldc(function.prefix()+":"+function.name()).aload(arguments)
                    .invokespecial(functionBase,"invokeFunction",
                            MethodTypeDesc.of(OBJECT,CONTEXT,STRING,ClassDesc.ofDescriptor("[Ljava/lang/Object;")));
            return;
        }
        if (node instanceof Ast.Call call && call.target() instanceof Ast.Property property) {
            int base=code.allocateLocal(java.lang.classfile.TypeKind.REFERENCE);
            int key=code.allocateLocal(java.lang.classfile.TypeKind.REFERENCE);
            int arguments=code.allocateLocal(java.lang.classfile.TypeKind.REFERENCE);
            var absent=code.newLabel(); var done=code.newLabel();
            emit(code,property.base()); code.astore(base).aload(base).ifnull(absent);
            emit(code,property.key()); code.astore(key).aload(key).ifnull(absent);
            code.aload(1).aload(key).ldc(STRING)
                    .invokevirtual(CONTEXT,"convertToType",MethodTypeDesc.of(OBJECT,OBJECT,CLASS)).checkcast(STRING).astore(key);
            code.loadConstant(call.arguments().size()).anewarray(OBJECT);
            for (int i=0;i<call.arguments().size();i++) {
                code.dup().loadConstant(i); emit(code,call.arguments().get(i)); code.aastore();
            }
            code.astore(arguments).aload(0).aload(1).aload(base).aload(key).aload(arguments)
                    .invokespecial(pathBase,"invokeProperty",MethodTypeDesc.of(OBJECT,CONTEXT,OBJECT,OBJECT,ClassDesc.ofDescriptor("[Ljava/lang/Object;")))
                    .goto_(done).labelBinding(absent).aconst_null().labelBinding(done);
            return;
        }
        if (node instanceof Ast.Call call) {
            int target=code.allocateLocal(java.lang.classfile.TypeKind.REFERENCE);
            int arguments=code.allocateLocal(java.lang.classfile.TypeKind.REFERENCE);
            var lambdaType=ClassDesc.of("jakarta.el.LambdaExpression");
            if (call.target() instanceof Ast.Identifier identifier) {
                code.aload(0).aload(1).ldc(identifier.name())
                        .invokespecial(functionBase,"resolveNamedCall",MethodTypeDesc.of(OBJECT,CONTEXT,STRING)).astore(target);
            } else {
                emit(code,call.target()); code.astore(target);
                var callable=code.newLabel(); var failure=ClassDesc.of("jakarta.el.MethodNotFoundException");
                code.aload(target).instanceOf(lambdaType).ifne(callable).new_(failure).dup()
                        .ldc("Expression does not resolve to a callable value")
                        .invokespecial(failure,"<init>",MethodTypeDesc.of(VOID,STRING)).athrow().labelBinding(callable);
            }
            code.loadConstant(call.arguments().size()).anewarray(OBJECT);
            for (int i=0;i<call.arguments().size();i++) {
                code.dup().loadConstant(i); emit(code,call.arguments().get(i)); code.aastore();
            }
            var argumentType=ClassDesc.ofDescriptor("[Ljava/lang/Object;");
            code.astore(arguments);
            if (call.target() instanceof Ast.Identifier)
                code.aload(0).aload(1).aload(target).aload(arguments)
                        .invokespecial(functionBase,"invokeResolvedCall",MethodTypeDesc.of(OBJECT,CONTEXT,OBJECT,argumentType));
            else code.aload(target).checkcast(lambdaType).aload(1).aload(arguments)
                    .invokevirtual(lambdaType,"invoke",MethodTypeDesc.of(OBJECT,CONTEXT,argumentType));
            return;
        }
        if (node instanceof Ast.Identifier identifier) {
            code.aload(0).aload(1).ldc(identifier.name())
                    .invokespecial(pathBase,"readIdentifier",MethodTypeDesc.of(OBJECT,CONTEXT,STRING));
            return;
        }
        if (node instanceof Ast.Property property) {
            int base=code.allocateLocal(java.lang.classfile.TypeKind.REFERENCE);
            int key=code.allocateLocal(java.lang.classfile.TypeKind.REFERENCE);
            var absent=code.newLabel(); var done=code.newLabel();
            emit(code,property.base()); code.astore(base).aload(base).ifnull(absent);
            emit(code,property.key()); code.astore(key).aload(key).ifnull(absent);
            code.aload(0).aload(1).aload(base).aload(key)
                    .invokespecial(pathBase,"readProperty",MethodTypeDesc.of(OBJECT,CONTEXT,OBJECT,OBJECT))
                    .goto_(done).labelBinding(absent).aconst_null().labelBinding(done);
            return;
        }
        if (node instanceof Ast.Conditional conditional) {
            var otherwise=code.newLabel();
            var done=code.newLabel();
            booleanOperand(code,conditional.condition());
            code.ifeq(otherwise);
            emit(code,conditional.whenTrue());
            code.goto_(done).labelBinding(otherwise);
            emit(code,conditional.whenFalse());
            code.labelBinding(done);
            return;
        }
        if (node instanceof Ast.Binary binary) {
            if (binary.operator().kind()==TokenKind.ASSIGN) emitAssignment(code,binary,pathBase);
            else if (logical(binary.operator().kind())) emitLogical(code,binary);
            else if (comparison(binary.operator().kind())) emitComparison(code,binary);
            else if (binary.operator().kind()==TokenKind.CONCAT) emitConcat(code,binary);
            else if (binary.operator().kind()==TokenKind.SEMICOLON) {
                emit(code,binary.left()); code.pop(); emit(code,binary.right());
            }
            else emitArithmetic(code,binary);
            return;
        }
        var unary=(Ast.Unary)node;
        var boxedBoolean=ClassDesc.of("java.lang.Boolean");
        switch (unary.operator().kind()) {
            case MINUS -> {
                if (signedInteger(unary)) {
                    try { number(code,signed((Ast.Literal)unary.operand())); }
                    catch (ELException failure) { emitNumericFailure(code,failure); }
                    return;
                }
                ClassDesc operand=rawType(unary.operand());
                if (operand==null) { emit(code,unary.operand()); code.pop(); number(code,0L); return; }
                ClassDesc result=rawType(unary);
                if (result.equals(OBJECT)) { emitDynamicMinus(code,unary.operand()); return; }
                boolean floating=result.equals(ClassDesc.of("java.lang.Double"));
                if (operand.equals(STRING)) {
                    code.aload(1);
                    emit(code,unary.operand());
                    code.ldc(result).invokevirtual(CONTEXT,"convertToType",MethodTypeDesc.of(OBJECT,OBJECT,CLASS));
                } else {
                    emit(code,unary.operand());
                    if (!operand.equals(ClassDesc.of("java.lang.Long"))
                            && !operand.equals(ClassDesc.of("java.lang.Double"))) {
                        code.pop().new_(ClassDesc.of("jakarta.el.ELException")).dup()
                                .ldc("Invalid operand for unary minus").invokespecial(ClassDesc.of("jakarta.el.ELException"),
                                        "<init>",MethodTypeDesc.of(VOID,STRING)).athrow();
                        return;
                    }
                }
                var number=ClassDesc.of("java.lang.Number");
                code.checkcast(number).invokevirtual(number,floating ? "doubleValue" : "longValue",
                        MethodTypeDesc.of(floating ? DOUBLE : LONG));
                if (floating) code.dneg(); else code.lneg();
                code.invokestatic(result,"valueOf",MethodTypeDesc.of(result,floating ? DOUBLE : LONG));
            }
            case NOT -> {
                code.aload(1);
                emit(code,unary.operand());
                code.getstatic(boxedBoolean,"TYPE",CLASS).invokevirtual(CONTEXT,"convertToType",
                        MethodTypeDesc.of(OBJECT,OBJECT,CLASS)).checkcast(boxedBoolean)
                        .invokevirtual(boxedBoolean,"booleanValue",MethodTypeDesc.of(BOOLEAN))
                        .iconst_1().ixor().invokestatic(boxedBoolean,"valueOf",MethodTypeDesc.of(boxedBoolean,BOOLEAN));
            }
            case EMPTY -> {
                emit(code,unary.operand());
                var absent=code.newLabel();
                var done=code.newLabel();
                var collection=code.newLabel(); var map=code.newLabel(); var array=code.newLabel();
                code.dup().ifnull(absent);
                int value=code.allocateLocal(java.lang.classfile.TypeKind.REFERENCE);
                var collectionType=ClassDesc.of("java.util.Collection"); var mapType=ClassDesc.of("java.util.Map");
                code.astore(value).aload(value).instanceOf(collectionType).ifne(collection)
                        .aload(value).instanceOf(mapType).ifne(map)
                        .aload(value).invokevirtual(OBJECT,"getClass",MethodTypeDesc.of(CLASS))
                        .invokevirtual(CLASS,"isArray",MethodTypeDesc.of(BOOLEAN)).ifne(array)
                        .ldc("").aload(value).invokevirtual(STRING,"equals",MethodTypeDesc.of(BOOLEAN,OBJECT))
                        .goto_(done).labelBinding(collection).aload(value).checkcast(collectionType)
                        .invokeinterface(collectionType,"isEmpty",MethodTypeDesc.of(BOOLEAN)).goto_(done)
                        .labelBinding(map).aload(value).checkcast(mapType)
                        .invokeinterface(mapType,"isEmpty",MethodTypeDesc.of(BOOLEAN)).goto_(done)
                        .labelBinding(array).aload(value).invokestatic(ClassDesc.of("java.lang.reflect.Array"),"getLength",
                                MethodTypeDesc.of(ClassDesc.ofDescriptor("I"),OBJECT));
                var emptyArray=code.newLabel();
                code.ifeq(emptyArray).iconst_0().goto_(done).labelBinding(emptyArray).iconst_1().goto_(done)
                        .labelBinding(absent).pop().iconst_1().labelBinding(done);
                code.invokestatic(boxedBoolean,"valueOf",MethodTypeDesc.of(boxedBoolean,BOOLEAN));
            }
            default -> throw new ELException("Unsupported unary operator");
        }
    }

    private static void number(CodeBuilder code,long value) {
        var boxed=ClassDesc.of("java.lang.Long");
        code.loadConstant(value).invokestatic(boxed,"valueOf",MethodTypeDesc.of(boxed,LONG));
    }

    private void emitAssignment(CodeBuilder code,Ast.Binary binary,ClassDesc pathBase) {
        var consumer=ClassDesc.of("java.util.function.Consumer");
        if (binary.left() instanceof Ast.Identifier identifier) {
            code.aload(0).aload(1).ldc(identifier.name())
                    .invokespecial(pathBase,"prepareRootWrite",MethodTypeDesc.of(consumer,CONTEXT,STRING));
        } else if (binary.left() instanceof Ast.Property property) {
            int base=code.allocateLocal(java.lang.classfile.TypeKind.REFERENCE);
            emit(code,property.base()); code.astore(base); requirePresent(code,base,"null base");
            int key=code.allocateLocal(java.lang.classfile.TypeKind.REFERENCE);
            emit(code,property.key()); code.astore(key); requirePresent(code,key,"null property");
            code.aload(0).aload(1).aload(base).aload(key)
                    .invokespecial(pathBase,"preparePropertyWrite",MethodTypeDesc.of(consumer,CONTEXT,OBJECT,OBJECT));
        } else {
            var exception=ClassDesc.of("jakarta.el.PropertyNotWritableException");
            code.new_(exception).dup().ldc("Assignment target is not an lvalue")
                    .invokespecial(exception,"<init>",MethodTypeDesc.of(VOID,STRING)).athrow();
            return;
        }
        int writer=code.allocateLocal(java.lang.classfile.TypeKind.REFERENCE);
        int value=code.allocateLocal(java.lang.classfile.TypeKind.REFERENCE);
        code.astore(writer); emit(code,binary.right()); code.astore(value);
        code.aload(writer).aload(value).invokeinterface(consumer,"accept",MethodTypeDesc.of(VOID,OBJECT)).aload(value);
    }

    private void emitList(CodeBuilder code,java.util.List<Ast> elements) {
        var type=ClassDesc.of("java.util.ArrayList");
        int slot=code.allocateLocal(java.lang.classfile.TypeKind.REFERENCE);
        code.new_(type).dup().loadConstant(elements.size())
                .invokespecial(type,"<init>",MethodTypeDesc.of(VOID,ClassDesc.ofDescriptor("I"))).astore(slot);
        for (Ast element:elements) {
            code.aload(slot); emit(code,element);
            code.invokevirtual(type,"add",MethodTypeDesc.of(BOOLEAN,OBJECT)).pop();
        }
        code.aload(slot);
    }

    private static void floatingOperand(CodeBuilder code,int slot,java.lang.classfile.Label floating) {
        var next=code.newLabel();
        code.aload(slot).instanceOf(ClassDesc.of("java.lang.Double")).ifne(floating)
                .aload(slot).instanceOf(ClassDesc.of("java.lang.Float")).ifne(floating)
                .aload(slot).instanceOf(STRING).ifeq(next);
        for (char marker:new char[]{'.','e','E'}) {
            code.aload(slot).checkcast(STRING).loadConstant((int)marker)
                    .invokevirtual(STRING,"indexOf",MethodTypeDesc.of(ClassDesc.ofDescriptor("I"),ClassDesc.ofDescriptor("I")))
                    .ifge(floating);
        }
        code.labelBinding(next);
    }

    private void emitDynamicMinus(CodeBuilder code,Ast operand) {
        int slot=code.allocateLocal(java.lang.classfile.TypeKind.REFERENCE);
        emit(code,operand);
        var present=code.newLabel();
        var floatingText=code.newLabel();
        var converted=code.newLabel(); var dispatch=code.newLabel();
        var done=code.newLabel();
        code.astore(slot).aload(slot).ifnonnull(present);
        number(code,0L);
        code.goto_(done).labelBinding(present)
                .aload(slot).instanceOf(STRING).ifeq(dispatch);
        floatingOperand(code,slot,floatingText);
        converted(code,slot,ClassDesc.of("java.lang.Number"),ClassDesc.of("java.lang.Long"));
        code.goto_(converted).labelBinding(floatingText);
        converted(code,slot,ClassDesc.of("java.lang.Number"),ClassDesc.of("java.lang.Double"));
        code.labelBinding(converted).astore(slot).aload(slot).ifnonnull(dispatch);
        var nullFailure=ClassDesc.of("java.lang.NullPointerException");
        code.new_(nullFailure).dup().invokespecial(nullFailure,"<init>",MethodTypeDesc.of(VOID)).athrow()
                .labelBinding(dispatch);
        var types=java.util.List.of("BigDecimal","BigInteger","Byte","Short","Integer","Long","Float","Double");
        var matches=new java.util.LinkedHashMap<String,java.lang.classfile.Label>();
        for (String name:types) {
            var match=code.newLabel(); matches.put(name,match);
            code.aload(slot).instanceOf(ClassDesc.of((name.startsWith("Big") ? "java.math." : "java.lang.")+name)).ifne(match);
        }
        var exception=ClassDesc.of("jakarta.el.ELException");
        code.new_(exception).dup().ldc("Invalid operand for unary minus")
                .invokespecial(exception,"<init>",MethodTypeDesc.of(VOID,STRING)).athrow();
        for (var entry:matches.entrySet()) {
            String name=entry.getKey();
            var type=ClassDesc.of((name.startsWith("Big") ? "java.math." : "java.lang.")+name);
            code.labelBinding(entry.getValue()).aload(slot).checkcast(type);
            if (name.startsWith("Big")) code.invokevirtual(type,"negate",MethodTypeDesc.of(type));
            else {
                String descriptor=switch (name) {
                    case "Byte"->"B"; case "Short"->"S"; case "Integer"->"I"; case "Long"->"J"; case "Float"->"F"; default->"D";
                };
                var primitive=ClassDesc.ofDescriptor(descriptor);
                String method=name.equals("Integer") ? "intValue" : name.toLowerCase(java.util.Locale.ROOT)+"Value";
                code.invokevirtual(type,method,MethodTypeDesc.of(primitive));
                switch (descriptor) {
                    case "J"->code.lneg(); case "F"->code.fneg(); case "D"->code.dneg();
                    case "B"->code.ineg().i2b(); case "S"->code.ineg().i2s(); default->code.ineg();
                }
                code.invokestatic(type,"valueOf",MethodTypeDesc.of(type,primitive));
            }
            code.goto_(done);
        }
        code.labelBinding(done);
    }

    private static boolean comparison(TokenKind kind) {
        return switch (kind) {
            case EQUAL,NOT_EQUAL,LESS,GREATER,LESS_EQUAL,GREATER_EQUAL -> true;
            default -> false;
        };
    }

    private static void converted(CodeBuilder code,int slot,ClassDesc type) {
        converted(code,slot,type,type);
    }

    private static void converted(CodeBuilder code,int slot,ClassDesc cast,ClassDesc target) {
        code.aload(1).aload(slot).ldc(target).invokevirtual(CONTEXT,"convertToType",
                MethodTypeDesc.of(OBJECT,OBJECT,CLASS));
        if (cast!=null) code.checkcast(cast);
    }

    private static void orderedBranch(CodeBuilder code,TokenKind operator,java.lang.classfile.Label truth) {
        switch (operator) {
            case EQUAL,NOT_EQUAL -> code.ifeq(truth);
            case LESS -> code.iflt(truth);
            case GREATER -> code.ifgt(truth);
            case LESS_EQUAL -> code.ifle(truth);
            case GREATER_EQUAL -> code.ifge(truth);
            default -> throw new ELException("Invalid comparison operator");
        }
    }

    private static void pairType(CodeBuilder code,int left,int right,ClassDesc type,java.lang.classfile.Label match) {
        code.aload(left).instanceOf(type).ifne(match).aload(right).instanceOf(type).ifne(match);
    }

    private void emitComparison(CodeBuilder code,Ast.Binary binary) {
        int left=code.allocateLocal(java.lang.classfile.TypeKind.REFERENCE);
        emit(code,binary.left()); code.astore(left);
        int right=code.allocateLocal(java.lang.classfile.TypeKind.REFERENCE);
        emit(code,binary.right()); code.astore(right);
        TokenKind operator=binary.operator().kind();
        boolean equality=operator==TokenKind.EQUAL || operator==TokenKind.NOT_EQUAL;
        var truth=code.newLabel(); var falsity=code.newLabel(); var boxed=code.newLabel();
        var floating=code.newLabel(); var integral=code.newLabel(); var bool=code.newLabel(); var text=code.newLabel();
        var decimal=code.newLabel(); var bigInteger=code.newLabel(); var enumLeft=code.newLabel(); var enumRight=code.newLabel();
        var fallback=code.newLabel();
        var start=code.newLabel(); var end=code.newLabel(); var handler=code.newLabel(); var done=code.newLabel();
        code.labelBinding(start).aload(left).aload(right).if_acmpeq(equality
                || operator==TokenKind.LESS_EQUAL || operator==TokenKind.GREATER_EQUAL ? truth : falsity);
        code.aload(left).ifnull(falsity).aload(right).ifnull(falsity);
        var doubleType=ClassDesc.of("java.lang.Double");
        var longType=ClassDesc.of("java.lang.Long");
        var booleanType=ClassDesc.of("java.lang.Boolean");
        var decimalType=ClassDesc.of("java.math.BigDecimal"); var integerType=ClassDesc.of("java.math.BigInteger");
        var enumType=ClassDesc.of("java.lang.Enum");
        pairType(code,left,right,decimalType,decimal);
        pairType(code,left,right,doubleType,floating);
        pairType(code,left,right,ClassDesc.of("java.lang.Float"),floating);
        pairType(code,left,right,integerType,bigInteger);
        pairType(code,left,right,longType,integral);
        for (String name:java.util.List.of("Byte","Short","Integer","Character"))
            pairType(code,left,right,ClassDesc.of("java.lang."+name),integral);
        if (equality) {
            pairType(code,left,right,booleanType,bool);
            code.aload(left).instanceOf(enumType).ifne(enumLeft).aload(right).instanceOf(enumType).ifne(enumRight);
        }
        pairType(code,left,right,STRING,text);
        code.goto_(fallback);
        for (var entry:java.util.List.of(java.util.Map.entry(decimalType,decimal),java.util.Map.entry(integerType,bigInteger))) {
            var type=entry.getKey(); code.labelBinding(entry.getValue());
            converted(code,left,type);
            converted(code,right,equality ? null : type,type);
            if (equality) code.invokevirtual(type,"equals",MethodTypeDesc.of(BOOLEAN,OBJECT)).ifne(truth);
            else {
                code.invokevirtual(type,"compareTo",MethodTypeDesc.of(ClassDesc.ofDescriptor("I"),type));
                orderedBranch(code,operator,truth);
            }
            code.goto_(falsity);
        }
        code.labelBinding(floating);
        converted(code,left,doubleType);
        code.invokevirtual(doubleType,"doubleValue",MethodTypeDesc.of(DOUBLE));
        converted(code,right,doubleType);
        code.invokevirtual(doubleType,"doubleValue",MethodTypeDesc.of(DOUBLE));
        if (operator==TokenKind.LESS || operator==TokenKind.LESS_EQUAL) code.dcmpg(); else code.dcmpl();
        orderedBranch(code,operator,truth);
        code.goto_(falsity).labelBinding(integral);
        if (equality) {
            converted(code,left,longType); converted(code,right,null,longType);
            code.invokevirtual(longType,"equals",MethodTypeDesc.of(BOOLEAN,OBJECT)).ifne(truth);
        } else {
            converted(code,left,longType);
            code.invokevirtual(longType,"longValue",MethodTypeDesc.of(LONG));
            converted(code,right,longType);
            code.invokevirtual(longType,"longValue",MethodTypeDesc.of(LONG)).lcmp();
            orderedBranch(code,operator,truth);
        }
        code.goto_(falsity).labelBinding(bool);
        if (equality) {
            converted(code,left,booleanType); converted(code,right,null,booleanType);
            code.invokevirtual(booleanType,"equals",MethodTypeDesc.of(BOOLEAN,OBJECT)).ifne(truth);
        } else {
            code.aload(left).checkcast(booleanType).aload(right).checkcast(booleanType)
                    .invokevirtual(booleanType,"compareTo",MethodTypeDesc.of(ClassDesc.ofDescriptor("I"),booleanType));
            orderedBranch(code,operator,truth);
        }
        code.goto_(falsity);
        if (equality) {
            for (boolean leftEnum:new boolean[]{true,false}) {
                int owner=leftEnum ? left : right, other=leftEnum ? right : left;
                code.labelBinding(leftEnum ? enumLeft : enumRight)
                        .aload(owner).checkcast(enumType).aload(1).aload(other).aload(owner).checkcast(enumType)
                        .invokevirtual(enumType,"getDeclaringClass",MethodTypeDesc.of(CLASS))
                        .invokevirtual(CONTEXT,"convertToType",MethodTypeDesc.of(OBJECT,OBJECT,CLASS))
                        .invokevirtual(enumType,"equals",MethodTypeDesc.of(BOOLEAN,OBJECT)).ifne(truth).goto_(falsity);
            }
        }
        code.labelBinding(text);
        converted(code,left,STRING); converted(code,right,equality ? null : STRING,STRING);
        if (equality) code.invokevirtual(STRING,"equals",MethodTypeDesc.of(BOOLEAN,OBJECT)).ifne(truth);
        else {
            code.invokevirtual(STRING,"compareTo",MethodTypeDesc.of(ClassDesc.ofDescriptor("I"),STRING));
            orderedBranch(code,operator,truth);
        }
        code.goto_(falsity).labelBinding(fallback);
        if (equality) code.aload(left).aload(right).invokevirtual(OBJECT,"equals",MethodTypeDesc.of(BOOLEAN,OBJECT)).ifne(truth);
        else {
            var comparable=ClassDesc.of("java.lang.Comparable");
            var rightComparable=code.newLabel();
            code.aload(left).instanceOf(comparable).ifeq(rightComparable)
                    .aload(left).checkcast(comparable).aload(right)
                    .invokeinterface(comparable,"compareTo",MethodTypeDesc.of(ClassDesc.ofDescriptor("I"),OBJECT));
            orderedBranch(code,operator,truth);
            code.goto_(falsity).labelBinding(rightComparable);
            var noncomparable=code.newLabel();
            code.aload(right).instanceOf(comparable).ifeq(noncomparable)
                    .aload(right).checkcast(comparable).aload(left)
                    .invokeinterface(comparable,"compareTo",MethodTypeDesc.of(ClassDesc.ofDescriptor("I"),OBJECT))
                    .invokestatic(ClassDesc.of("java.lang.Integer"),"signum",MethodTypeDesc.of(ClassDesc.ofDescriptor("I"),ClassDesc.ofDescriptor("I")))
                    .ineg();
            orderedBranch(code,operator,truth);
            var exception=ClassDesc.of("jakarta.el.ELException");
            code.goto_(falsity).labelBinding(noncomparable).new_(exception).dup().ldc("Operands are not comparable")
                    .invokespecial(exception,"<init>",MethodTypeDesc.of(VOID,STRING)).athrow();
        }
        code.goto_(falsity).labelBinding(truth).iconst_1().goto_(boxed)
                .labelBinding(falsity).iconst_0().labelBinding(boxed);
        if (operator==TokenKind.NOT_EQUAL) code.iconst_1().ixor();
        code.invokestatic(booleanType,"valueOf",MethodTypeDesc.of(booleanType,BOOLEAN))
                .labelBinding(end).goto_(done).labelBinding(handler);
        int failure=code.allocateLocal(java.lang.classfile.TypeKind.REFERENCE);
        var exception=ClassDesc.of("jakarta.el.ELException"); var rethrow=code.newLabel();
        code.astore(failure).aload(failure).instanceOf(exception).ifne(rethrow)
                .new_(exception).dup().ldc(equality ? "Equality comparison failed" : "Ordered comparison failed")
                .aload(failure).invokespecial(exception,"<init>",MethodTypeDesc.of(VOID,STRING,ClassDesc.of("java.lang.Throwable")))
                .athrow().labelBinding(rethrow).aload(failure).athrow().labelBinding(done);
        code.exceptionCatch(start,end,handler,ClassDesc.of("java.lang.RuntimeException"));
    }

    private void emitConcat(CodeBuilder code,Ast.Binary binary) {
        int left=code.allocateLocal(java.lang.classfile.TypeKind.REFERENCE);
        emit(code,binary.left()); code.astore(left);
        int right=code.allocateLocal(java.lang.classfile.TypeKind.REFERENCE);
        emit(code,binary.right()); code.astore(right);
        var buffer=ClassDesc.of("java.lang.StringBuilder");
        code.new_(buffer).dup().invokespecial(buffer,"<init>",MethodTypeDesc.of(VOID));
        converted(code,left,STRING);
        code.invokevirtual(buffer,"append",MethodTypeDesc.of(buffer,STRING));
        converted(code,right,STRING);
        code.invokevirtual(buffer,"append",MethodTypeDesc.of(buffer,STRING))
                .invokevirtual(buffer,"toString",MethodTypeDesc.of(STRING));
    }

    private static boolean logical(TokenKind kind) {
        return kind==TokenKind.AND || kind==TokenKind.OR;
    }

    private void booleanOperand(CodeBuilder code,Ast operand) {
        var boxed=ClassDesc.of("java.lang.Boolean");
        code.aload(1);
        emit(code,operand);
        code.getstatic(boxed,"TYPE",CLASS).invokevirtual(CONTEXT,"convertToType",
                MethodTypeDesc.of(OBJECT,OBJECT,CLASS)).checkcast(boxed)
                .invokevirtual(boxed,"booleanValue",MethodTypeDesc.of(BOOLEAN));
    }

    private void emitLogical(CodeBuilder code,Ast.Binary binary) {
        var shortCircuit=code.newLabel();
        var done=code.newLabel();
        boolean and=binary.operator().kind()==TokenKind.AND;
        booleanOperand(code,binary.left());
        if (and) code.ifeq(shortCircuit); else code.ifne(shortCircuit);
        booleanOperand(code,binary.right());
        code.goto_(done).labelBinding(shortCircuit);
        if (and) code.iconst_0(); else code.iconst_1();
        var boxed=ClassDesc.of("java.lang.Boolean");
        code.labelBinding(done).invokestatic(boxed,"valueOf",MethodTypeDesc.of(boxed,BOOLEAN));
    }

    private static boolean arithmetic(TokenKind kind) {
        return switch (kind) {
            case PLUS,MINUS,MULTIPLY,DIVIDE,MODULO -> true;
            default -> false;
        };
    }

    private static boolean dynamicNumber(Ast node) {
        ClassDesc type=rawType(node);
        return OBJECT.equals(type) || STRING.equals(type) && !(node instanceof Ast.Literal);
    }

    private static boolean floating(Ast node) {
        if (ClassDesc.of("java.lang.Double").equals(rawType(node))) return true;
        if (node instanceof Ast.Literal literal && literal.token().kind()==TokenKind.STRING) {
            String value=literal.token().value();
            return value.indexOf('.')>=0 || value.indexOf('e')>=0 || value.indexOf('E')>=0;
        }
        return false;
    }

    private static void numericOperand(CodeBuilder code,int slot,boolean floating) {
        ClassDesc boxed=ClassDesc.of(floating ? "java.lang.Double" : "java.lang.Long");
        code.aload(1);
        var present=code.newLabel();
        code.aload(slot).dup().ifnonnull(present).pop();
        number(code,0L);
        code.labelBinding(present);
        code.ldc(boxed).invokevirtual(CONTEXT,"convertToType",MethodTypeDesc.of(OBJECT,OBJECT,CLASS))
                .checkcast(boxed).invokevirtual(boxed,floating ? "doubleValue" : "longValue",
                        MethodTypeDesc.of(floating ? DOUBLE : LONG));
    }

    private void emitArithmetic(CodeBuilder code,Ast.Binary binary) {
        // Evaluation precedes either numeric conversion, matching the interpreter's effects.
        int left=code.allocateLocal(java.lang.classfile.TypeKind.REFERENCE);
        emit(code,binary.left());
        code.astore(left);
        int right=code.allocateLocal(java.lang.classfile.TypeKind.REFERENCE);
        emit(code,binary.right());
        code.astore(right);
        if (OBJECT.equals(rawType(binary))) {
            emitDynamicMath(code,binary.operator().kind(),left,right);
            return;
        }
        if (rawType(binary.left())==null && rawType(binary.right())==null) {
            number(code,0L);
            return;
        }
        boolean floating=rawType(binary).equals(ClassDesc.of("java.lang.Double"));
        emitMath(code,binary.operator().kind(),left,right,floating);
    }

    private static void emitDynamicMath(CodeBuilder code,TokenKind operator,int left,int right) {
        var nonnull=code.newLabel(); var floating=code.newLabel(); var decimal=code.newLabel();
        var integer=code.newLabel(); var maybeDecimal=code.newLabel(); var done=code.newLabel();
        var decimalType=ClassDesc.of("java.math.BigDecimal"); var integerType=ClassDesc.of("java.math.BigInteger");
        code.aload(left).ifnonnull(nonnull).aload(right).ifnonnull(nonnull);
        number(code,0L);
        code.goto_(done).labelBinding(nonnull);
        if (operator==TokenKind.DIVIDE) {
            pairType(code,left,right,decimalType,decimal); pairType(code,left,right,integerType,decimal);
            code.goto_(floating);
        } else if (operator==TokenKind.MODULO) {
            pairType(code,left,right,decimalType,floating);
            floatingOperand(code,left,floating); floatingOperand(code,right,floating);
            pairType(code,left,right,integerType,integer);
            emitMath(code,operator,left,right,false); code.goto_(done);
        } else {
            pairType(code,left,right,decimalType,decimal); pairType(code,left,right,integerType,maybeDecimal);
            floatingOperand(code,left,floating); floatingOperand(code,right,floating);
            emitMath(code,operator,left,right,false); code.goto_(done).labelBinding(maybeDecimal);
            floatingOperand(code,left,decimal); floatingOperand(code,right,decimal); code.goto_(integer);
        }
        code.labelBinding(floating); emitMath(code,operator,left,right,true); code.goto_(done);
        if (operator!=TokenKind.MODULO) {
            code.labelBinding(decimal); emitBigMath(code,operator,left,right,decimalType); code.goto_(done);
        }
        if (operator!=TokenKind.DIVIDE) {
            code.labelBinding(integer); emitBigMath(code,operator,left,right,integerType);
        }
        code.labelBinding(done);
    }

    private static void bigOperand(CodeBuilder code,int slot,ClassDesc target) {
        var present=code.newLabel();
        code.aload(1).aload(slot).dup().ifnonnull(present).pop();
        number(code,0L);
        code.labelBinding(present).ldc(target)
                .invokevirtual(CONTEXT,"convertToType",MethodTypeDesc.of(OBJECT,OBJECT,CLASS)).checkcast(target);
    }

    private static void emitBigMath(CodeBuilder code,TokenKind operator,int left,int right,ClassDesc target) {
        var start=code.newLabel(); var end=code.newLabel(); var handler=code.newLabel(); var done=code.newLabel();
        code.labelBinding(start); bigOperand(code,left,target); bigOperand(code,right,target);
        if (operator==TokenKind.DIVIDE) {
            var rounding=ClassDesc.of("java.math.RoundingMode");
            code.getstatic(rounding,"HALF_UP",rounding).invokevirtual(target,"divide",MethodTypeDesc.of(target,target,rounding));
        } else {
            String method=switch (operator) {
                case PLUS->"add"; case MINUS->"subtract"; case MULTIPLY->"multiply"; case MODULO->"remainder";
                default->throw new ELException("Invalid big-number operator");
            };
            code.invokevirtual(target,method,MethodTypeDesc.of(target,target));
        }
        code.labelBinding(end).goto_(done).labelBinding(handler);
        int failure=code.allocateLocal(java.lang.classfile.TypeKind.REFERENCE);
        var exception=ClassDesc.of("jakarta.el.ELException"); var rethrow=code.newLabel();
        code.astore(failure).aload(failure).instanceOf(exception).ifne(rethrow)
                .new_(exception).dup().ldc("Arithmetic error for "+operator).aload(failure)
                .invokespecial(exception,"<init>",MethodTypeDesc.of(VOID,STRING,ClassDesc.of("java.lang.Throwable")))
                .athrow().labelBinding(rethrow).aload(failure).athrow().labelBinding(done);
        code.exceptionCatch(start,end,handler,ClassDesc.of("java.lang.RuntimeException"));
    }

    private static void emitMath(CodeBuilder code,TokenKind operator,int left,int right,boolean floating) {
        // Division promotes any non-null pair to Double.
        floating=floating || operator==TokenKind.DIVIDE;
        var start=code.newLabel();
        var end=code.newLabel();
        var handler=code.newLabel();
        var rethrow=code.newLabel();
        var done=code.newLabel();
        code.labelBinding(start);
        numericOperand(code,left,floating);
        numericOperand(code,right,floating);
        switch (operator) {
            case PLUS -> { if (floating) code.dadd(); else code.ladd(); }
            case MINUS -> { if (floating) code.dsub(); else code.lsub(); }
            case MULTIPLY -> { if (floating) code.dmul(); else code.lmul(); }
            case DIVIDE -> code.ddiv();
            case MODULO -> { if (floating) code.drem(); else code.lrem(); }
            default -> throw new ELException("Unsupported arithmetic operator");
        }
        var boxed=ClassDesc.of(floating ? "java.lang.Double" : "java.lang.Long");
        code.invokestatic(boxed,"valueOf",MethodTypeDesc.of(boxed,floating ? DOUBLE : LONG))
                .labelBinding(end).goto_(done).labelBinding(handler);
        int failure=code.allocateLocal(java.lang.classfile.TypeKind.REFERENCE);
        var exception=ClassDesc.of("jakarta.el.ELException");
        code.astore(failure).aload(failure).instanceOf(exception).ifne(rethrow)
                .new_(exception).dup().ldc("Arithmetic error for "+operator).aload(failure)
                .invokespecial(exception,"<init>",MethodTypeDesc.of(VOID,STRING,ClassDesc.of("java.lang.Throwable")))
                .athrow().labelBinding(rethrow).aload(failure).athrow().labelBinding(done);
        code.exceptionCatch(start,end,handler,ClassDesc.of("java.lang.RuntimeException"));
    }

    private static void emitNumericFailure(CodeBuilder code,ELException failure) {
        var exception=ClassDesc.of("jakarta.el.ELException");
        var cause=ClassDesc.of("java.lang.NumberFormatException");
        code.new_(exception).dup().ldc(failure.getMessage())
                .new_(cause).dup().ldc(failure.getCause().getMessage())
                .invokespecial(cause,"<init>",MethodTypeDesc.of(VOID,STRING))
                .invokespecial(exception,"<init>",MethodTypeDesc.of(VOID,STRING,ClassDesc.of("java.lang.Throwable")))
                .athrow();
    }

    private void emit(CodeBuilder code,Ast.Literal literal) {
        Object value;
        try { value=constant(literal); }
        catch (ELException failure) { emitNumericFailure(code,failure); return; }
        if (value==null) code.aconst_null();
        else if (value instanceof String text) {
            if (text.isEmpty()) code.ldc(text); else code.aload(0).getfield(owner,strings.get(literal),STRING);
        }
        else if (value instanceof Long number) {
            number(code,number);
        } else if (value instanceof Double number) {
            var boxed=ClassDesc.of("java.lang.Double");
            code.loadConstant(number).invokestatic(boxed,"valueOf",MethodTypeDesc.of(boxed,DOUBLE));
        } else {
            var boxed=ClassDesc.of("java.lang.Boolean");
            if ((Boolean)value) code.iconst_1(); else code.iconst_0();
            code.invokestatic(boxed,"valueOf",MethodTypeDesc.of(boxed,BOOLEAN));
        }
    }
}

