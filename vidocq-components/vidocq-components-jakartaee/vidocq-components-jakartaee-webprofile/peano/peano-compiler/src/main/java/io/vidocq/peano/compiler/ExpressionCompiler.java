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
    private ExpressionCompiler() {}

    /**
     * Emits a public CompiledValueExpression subclass with a public Class constructor.
     * The constructor receives the expected result type; the caller owns output placement.
     * This slice accepts literal text and templates containing only literal AST nodes.
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
                if (!(body instanceof Ast.Literal literal))
                    throw new ELException("Unsupported compiled expression node "+body.getClass().getSimpleName()
                            +" in template: "+source);
                var token=literal.token();
                key(identity,token.kind().name(),token.value()==null ? token.text() : token.value());
                // Parse numeric literals now, so invalid values fail before any file is emitted.
                constant(literal);
                if (template.parts().size()==1) rawType=rawType(literal);
            }
        }
        ClassDesc owner=ClassDesc.of(binaryName);
        ClassDesc resultType=rawType;
        try {
            return ClassFile.of().build(owner,builder-> {
                builder.withFlags(ClassFile.ACC_PUBLIC|ClassFile.ACC_FINAL|ClassFile.ACC_SUPER)
                        .withVersion(ClassFile.JAVA_25_VERSION,0).withSuperclass(BASE);
                builder.withField("serialVersionUID",LONG,
                        field->field.withFlags(ClassFile.ACC_PRIVATE|ClassFile.ACC_STATIC|ClassFile.ACC_FINAL)
                                .with(ConstantValueAttribute.of(1L)));
                builder.withMethodBody("<init>",MethodTypeDesc.of(VOID,CLASS),ClassFile.ACC_PUBLIC,code-> {
                    code.aload(0).ldc(source).ldc(identity.toString()).aload(1);
                    if (template.kind()==Template.Kind.LITERAL) code.iconst_1(); else code.iconst_0();
                    if (resultType==null) code.aconst_null(); else code.ldc(resultType);
                    code.invokespecial(BASE,"<init>",MethodTypeDesc.of(VOID,STRING,STRING,CLASS,BOOLEAN,CLASS)).return_();
                });
            builder.withMethodBody("evaluate",MethodTypeDesc.of(OBJECT,CONTEXT),ClassFile.ACC_PROTECTED,code-> {
                if (template.parts().size()==1 && template.parts().getFirst() instanceof Template.Evaluation evaluation) {
                    emit(code,(Ast.Literal)evaluation.body());
                } else {
                    var buffer=ClassDesc.of("java.lang.StringBuilder");
                    code.new_(buffer).dup().invokespecial(buffer,"<init>",MethodTypeDesc.of(VOID));
                    for (Template.Part part:template.parts()) {
                        if (part instanceof Template.Text text) code.ldc(text.value());
                        else {
                            code.aload(1);
                            emit(code,(Ast.Literal)((Template.Evaluation)part).body());
                            code.ldc(STRING).invokevirtual(CONTEXT,"convertToType",MethodTypeDesc.of(OBJECT,OBJECT,CLASS))
                                    .checkcast(STRING);
                        }
                        code.invokevirtual(buffer,"append",MethodTypeDesc.of(buffer,STRING));
                    }
                    code.invokevirtual(buffer,"toString",MethodTypeDesc.of(STRING));
                }
                code.areturn();
            });
        });
        } catch (IllegalArgumentException failure) {
            throw new ELException("Cannot emit compiled expression "+binaryName+": "+failure.getMessage(),failure);
        }
    }

    private static void key(StringBuilder key,String kind,String value) {
        key.append(kind).append(':').append(value.length()).append(':').append(value);
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

    private static void emit(CodeBuilder code,Ast.Literal literal) {
        Object value=constant(literal);
        if (value==null) code.aconst_null();
        else if (value instanceof String text) code.ldc(text);
        else if (value instanceof Long number) {
            var boxed=ClassDesc.of("java.lang.Long");
            code.loadConstant(number).invokestatic(boxed,"valueOf",MethodTypeDesc.of(boxed,LONG));
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

