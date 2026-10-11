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
        }
    }

    /**
     * Emits a public CompiledValueExpression subclass with a public Class constructor.
     * The constructor receives the expected result type; the caller owns output placement.
     * This slice accepts scalar literals and operators, including comparisons, concatenation and sequences.
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
                    builder.withField(field,STRING,ClassFile.ACC_PRIVATE|ClassFile.ACC_STATIC|ClassFile.ACC_FINAL);
                if (!compiler.strings.isEmpty()) {
                    builder.withMethodBody("<clinit>",MethodTypeDesc.of(VOID),ClassFile.ACC_STATIC,code-> {
                        for (var entry:compiler.strings.entrySet()) {
                            code.new_(STRING).dup().ldc(entry.getKey().token().value())
                                    .invokespecial(STRING,"<init>",MethodTypeDesc.of(VOID,STRING))
                                    .putstatic(owner,entry.getValue(),STRING);
                        }
                        code.return_();
                    });
                }
                builder.withMethodBody("<init>",MethodTypeDesc.of(VOID,CLASS),ClassFile.ACC_PUBLIC,code-> {
                    code.aload(0).ldc(source).ldc(identity.toString()).aload(1);
                    if (template.kind()==Template.Kind.LITERAL) code.iconst_1(); else code.iconst_0();
                    if (resultType==null) code.aconst_null(); else code.ldc(resultType);
                    code.invokespecial(BASE,"<init>",MethodTypeDesc.of(VOID,STRING,STRING,CLASS,BOOLEAN,CLASS)).return_();
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
            String key="peano-compiled-value-v2:"+identity;
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
        if (node instanceof Ast.Literal literal) {
            literalKey(identity,literal);
        } else if (node instanceof Ast.Unary unary) {
            key(identity,"unary",unary.operator().kind().name());
            if (signedInteger(unary)) {
                literalKey(identity,(Ast.Literal)unary.operand());
            } else validate(unary.operand(),identity,source);
        } else if (node instanceof Ast.Binary binary
                && (arithmetic(binary.operator().kind()) || logical(binary.operator().kind())
                    || comparison(binary.operator().kind()) || binary.operator().kind()==TokenKind.CONCAT
                    || binary.operator().kind()==TokenKind.SEMICOLON)) {
            key(identity,"binary",binary.operator().kind().name());
            validate(binary.left(),identity,source);
            validate(binary.right(),identity,source);
        } else if (node instanceof Ast.Conditional conditional) {
            key(identity,"conditional","");
            validate(conditional.condition(),identity,source);
            validate(conditional.whenTrue(),identity,source);
            validate(conditional.whenFalse(),identity,source);
        } else throw new ELException("Unsupported compiled expression node "+node.getClass().getSimpleName()
                +" in template: "+source);
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
        if (node instanceof Ast.Conditional conditional) {
            ClassDesc yes=rawType(conditional.whenTrue());
            return Objects.equals(yes,rawType(conditional.whenFalse())) ? yes : OBJECT;
        }
        if (node instanceof Ast.Binary binary) {
            if (logical(binary.operator().kind()) || comparison(binary.operator().kind()))
                return ClassDesc.of("java.lang.Boolean");
            if (binary.operator().kind()==TokenKind.CONCAT) return STRING;
            if (binary.operator().kind()==TokenKind.SEMICOLON) return rawType(binary.right());
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

    private void emit(CodeBuilder code,Ast node) {
        if (node instanceof Ast.Literal literal) { emit(code,literal); return; }
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
            if (logical(binary.operator().kind())) emitLogical(code,binary);
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
                code.dup().ifnull(absent);
                int value=code.allocateLocal(java.lang.classfile.TypeKind.REFERENCE);
                code.astore(value).ldc("").aload(value).invokevirtual(STRING,"equals",MethodTypeDesc.of(BOOLEAN,OBJECT))
                        .goto_(done).labelBinding(absent).pop().iconst_1().labelBinding(done);
                code.invokestatic(boxedBoolean,"valueOf",MethodTypeDesc.of(boxedBoolean,BOOLEAN));
            }
            default -> throw new ELException("Unsupported unary operator");
        }
    }

    private static void number(CodeBuilder code,long value) {
        var boxed=ClassDesc.of("java.lang.Long");
        code.loadConstant(value).invokestatic(boxed,"valueOf",MethodTypeDesc.of(boxed,LONG));
    }

    private static void floatingOperand(CodeBuilder code,int slot,java.lang.classfile.Label floating) {
        var next=code.newLabel();
        code.aload(slot).instanceOf(ClassDesc.of("java.lang.Double")).ifne(floating)
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
        var floating=code.newLabel();
        var text=code.newLabel();
        var floatingText=code.newLabel();
        var done=code.newLabel();
        code.astore(slot).aload(slot).ifnonnull(present);
        number(code,0L);
        code.goto_(done).labelBinding(present)
                .aload(slot).instanceOf(ClassDesc.of("java.lang.Double")).ifne(floating)
                .aload(slot).instanceOf(STRING).ifne(text);
        var valid=code.newLabel();
        code.aload(slot).instanceOf(ClassDesc.of("java.lang.Long")).ifne(valid);
        var exception=ClassDesc.of("jakarta.el.ELException");
        code.new_(exception).dup().ldc("Invalid operand for unary minus")
                .invokespecial(exception,"<init>",MethodTypeDesc.of(VOID,STRING)).athrow().labelBinding(valid);
        negate(code,slot,false,false);
        code.goto_(done).labelBinding(floating);
        negate(code,slot,true,false);
        code.goto_(done).labelBinding(text);
        floatingOperand(code,slot,floatingText);
        negate(code,slot,false,true);
        code.goto_(done).labelBinding(floatingText);
        negate(code,slot,true,true);
        code.labelBinding(done);
    }

    private static void negate(CodeBuilder code,int slot,boolean floating,boolean convert) {
        var boxed=ClassDesc.of(floating ? "java.lang.Double" : "java.lang.Long");
        if (convert) code.aload(1);
        code.aload(slot);
        if (convert) code.ldc(boxed).invokevirtual(CONTEXT,"convertToType",MethodTypeDesc.of(OBJECT,OBJECT,CLASS));
        var number=ClassDesc.of("java.lang.Number");
        code.checkcast(number).invokevirtual(number,floating ? "doubleValue" : "longValue",
                MethodTypeDesc.of(floating ? DOUBLE : LONG));
        if (floating) code.dneg(); else code.lneg();
        code.invokestatic(boxed,"valueOf",MethodTypeDesc.of(boxed,floating ? DOUBLE : LONG));
    }

    private static boolean comparison(TokenKind kind) {
        return switch (kind) {
            case EQUAL,NOT_EQUAL,LESS,GREATER,LESS_EQUAL,GREATER_EQUAL -> true;
            default -> false;
        };
    }

    private static void converted(CodeBuilder code,int slot,ClassDesc type) {
        code.aload(1).aload(slot).ldc(type).invokevirtual(CONTEXT,"convertToType",
                MethodTypeDesc.of(OBJECT,OBJECT,CLASS)).checkcast(type);
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
        var start=code.newLabel(); var end=code.newLabel(); var handler=code.newLabel(); var done=code.newLabel();
        code.labelBinding(start).aload(left).aload(right).if_acmpeq(equality
                || operator==TokenKind.LESS_EQUAL || operator==TokenKind.GREATER_EQUAL ? truth : falsity);
        code.aload(left).ifnull(falsity).aload(right).ifnull(falsity);
        var doubleType=ClassDesc.of("java.lang.Double");
        var longType=ClassDesc.of("java.lang.Long");
        var booleanType=ClassDesc.of("java.lang.Boolean");
        pairType(code,left,right,doubleType,floating);
        pairType(code,left,right,longType,integral);
        if (equality) pairType(code,left,right,booleanType,bool);
        pairType(code,left,right,STRING,text);
        code.goto_(bool).labelBinding(floating);
        converted(code,left,doubleType);
        code.invokevirtual(doubleType,"doubleValue",MethodTypeDesc.of(DOUBLE));
        converted(code,right,doubleType);
        code.invokevirtual(doubleType,"doubleValue",MethodTypeDesc.of(DOUBLE));
        if (operator==TokenKind.LESS || operator==TokenKind.LESS_EQUAL) code.dcmpg(); else code.dcmpl();
        orderedBranch(code,operator,truth);
        code.goto_(falsity).labelBinding(integral);
        if (equality) {
            converted(code,left,longType); converted(code,right,longType);
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
            converted(code,left,booleanType); converted(code,right,booleanType);
            code.invokevirtual(booleanType,"equals",MethodTypeDesc.of(BOOLEAN,OBJECT)).ifne(truth);
        } else {
            code.aload(left).checkcast(booleanType).aload(right).checkcast(booleanType)
                    .invokevirtual(booleanType,"compareTo",MethodTypeDesc.of(ClassDesc.ofDescriptor("I"),booleanType));
            orderedBranch(code,operator,truth);
        }
        code.goto_(falsity).labelBinding(text);
        converted(code,left,STRING); converted(code,right,STRING);
        if (equality) code.invokevirtual(STRING,"equals",MethodTypeDesc.of(BOOLEAN,OBJECT)).ifne(truth);
        else {
            code.invokevirtual(STRING,"compareTo",MethodTypeDesc.of(ClassDesc.ofDescriptor("I"),STRING));
            orderedBranch(code,operator,truth);
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
            var nonnull=code.newLabel();
            var floating=code.newLabel();
            var done=code.newLabel();
            code.aload(left).ifnonnull(nonnull).aload(right).ifnonnull(nonnull);
            number(code,0L);
            code.goto_(done).labelBinding(nonnull);
            floatingOperand(code,left,floating);
            floatingOperand(code,right,floating);
            emitMath(code,binary.operator().kind(),left,right,false);
            code.goto_(done).labelBinding(floating);
            emitMath(code,binary.operator().kind(),left,right,true);
            code.labelBinding(done);
            return;
        }
        if (rawType(binary.left())==null && rawType(binary.right())==null) {
            number(code,0L);
            return;
        }
        boolean floating=rawType(binary).equals(ClassDesc.of("java.lang.Double"));
        emitMath(code,binary.operator().kind(),left,right,floating);
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
            if (text.isEmpty()) code.ldc(text); else code.getstatic(owner,strings.get(literal),STRING);
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

