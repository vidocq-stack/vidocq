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

import static org.junit.jupiter.api.Assertions.*;
import java.io.File;
import java.lang.classfile.ClassFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import jakarta.el.*;
import io.vidocq.peano.spi.AccessorTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExpressionCompilerTest {
    @TempDir Path temporary;

    @Test
    void emitsConstantTemplatesThatRunWithoutCoreOrCompiler() throws Exception {
        var sources=List.of("", "plain text", "${42}", "#{ 42 }", "${true}",
                "${false}", "${null}", "${'hello'}", "${1.25}", "value=${7};${null}",
                "${'x'}${'y'}", "\\${escaped}", "${-7}", "#{-9223372036854775808}",
                "${-(-7)}", "${-1.25}", "${-0.0}", "${-'7'}", "${-'1.5'}", "${-null}",
                "${not false}", "#{! false}", "${empty ''}", "${empty null}", "${empty -7}",
                "${not empty ''}", "signed=${-7}", "${1+2*3}", "${7-3}", "${2*4}",
                "${7/2}", "${7 div 2}", "${7%3}", "${7 mod 3}", "${1.5+2}",
                "${null+null}", "${null/null}", "${null+7}", "${'1.5'+2}", "${'7'-2}",
                "${-7%3}", "${1 + -'2'}", "${(-7+3)*2}", "${empty (1+2)}",
                "${1/0}", "${0/0}", "${9223372036854775807+1}", "${'2e2'*3}",
                "${null%null}", "result=${1+2}", "${true and false}", "${true && true}",
                "${false or true}", "${false || false}", "${null and true}", "${'' or 'true'}",
                "${'TRUE' and not false}", "${true or (1%0)}", "${false and -true}",
                "${false or true and false}", "${not (false or true)}", "logic=${true and false}",
                "${true or 9223372036854775808}", "${false and -9223372036854775809}",
                "${true ? 7 : 1%0}", "${false ? -true : 'yes'}", "${null ? 1 : null}",
                "${true ? null : 7}", "${false ? 7 : 1.5}", "${'true' ? 'yes' : 'no'}",
                "${false ? 1 : true ? 2 : 3}", "${true ? false or true : false}",
                "${false ? 9223372036854775808 : 7}", "condition=${true ? null : 1}",
                "${empty (true ? '' : 'x')}", "${empty (false ? 1 : null)}",
                "${-(true ? '1.5' : '7')}", "${-(false ? '1.5' : '7')}",
                "${-(true ? null : 1)}", "${-(false ? 1 : 1.5)}",
                "${(true ? '1.5' : '7') + 2}", "${(false ? '1.5' : '7') + 2}",
                "${(true ? null : 1)/null}", "${(false ? 1 : 1.5)%2}",
                "${1 + (true ? null : 2)}", "${true or (false ? 1 : 7)}",
                "${1==1}", "${1 eq 1.0}", "${1!=2}", "${1 ne 1.0}", "${null==null}",
                "${null==0}", "${null<=null}", "${null<null}", "${null>=0}",
                "${'x'=='x'}", "${''==''}", "${'true'==true}", "${'7'==7}",
                "${'10'<'2'}", "${'7'<8}", "${2>1.5}", "${2 le 2}", "${2 ge 3}",
                "${false<true}", "${(0/0)==(0/0)}", "${(0/0)<1}", "${(0/0)>=1}",
                "${1>(0/0)}", "${1<=(0/0)}", "${-0.0==0.0}",
                "${(true ? '7' : 1.5)==7}", "${(false ? null : 1)<2}",
                "${'x'+=7}", "${null+=null}", "${'x'+=''=='x'}", "${empty (''+='')}",
                "${1;2}", "${1;null}", "${-(1;'1.5')}", "${(1;'1.5')+2}",
                "${[]}", "${[1,null,'x']}", "${{}}", "${{1,2,1}}", "${{'x':1,'x':2,null:3}}",
                "${[[1],{'x':[2,null]}]}", "${empty []}", "${empty {}}", "${empty {'x':1}}",
                "${empty (true ? [] : 7)}", "${[1,2]==[1,2]}", "${[1]!=[2]}",
                "${{'x':1}=={'x':1}}", "${{1,2}=={2,1}}", "${[]==null}",
                "${true ? [1] : [2]}", "collection=${[1,null]}");
        Path classes=temporary.resolve("classes");
        Files.createDirectories(classes.resolve("demo"));
        var constructors=new ArrayList<String>();
        var factory=ExpressionFactory.newInstance();
        var oracle=new StandardELContext(factory);
        var expected=new ArrayList<String>();
        int[] oracleConversions={0};
        var instanceOracle=new StandardELContext(factory) {
            public <T> T convertToType(Object value,Class<T> type) {
                if (type==String.class) return (T)(++oracleConversions[0]==1 ? "left" : "right");
                return super.convertToType(value,type);
            }
        };
        instanceOracle.getVariableMapper().setVariable("left",factory.createValueExpression(instanceOracle,"${'hello'}",Object.class));
        instanceOracle.getVariableMapper().setVariable("right",factory.createValueExpression(instanceOracle,"${'hello'}",Object.class));
        assertEquals(Boolean.FALSE,factory.createValueExpression(instanceOracle,"${left==right}",Object.class).getValue(instanceOracle));
        assertEquals(2,oracleConversions[0]);
        for (int i=0;i<sources.size();i++) {
            byte[] bytes=ExpressionCompiler.compile("demo.E"+i,sources.get(i));
            assertTrue(ClassFile.of().verify(bytes).isEmpty(),sources.get(i)+": "+ClassFile.of().verify(bytes));
            for (var entry:ClassFile.of().parse(bytes).constantPool()) {
                if (entry instanceof java.lang.classfile.constantpool.ClassEntry type) {
                    assertFalse(type.name().stringValue().startsWith("io/vidocq/peano/core/"));
                    assertFalse(type.name().stringValue().startsWith("io/vidocq/peano/compiler/"));
                }
            }
            Files.write(classes.resolve("demo/E"+i+".class"),bytes);
            constructors.add("new E"+i+"(Object.class)");
            var expression=factory.createValueExpression(oracle,sources.get(i),Object.class);
            Object value=expression.getValue(oracle);
            expected.add(render(value)+"|"+(expression.getType(oracle)==null ? "null" : expression.getType(oracle).getName())
                    +"|"+expression.isLiteralText());
        }
        String main="""
                package demo;
                import jakarta.el.*;
                public class Main {
                    static class Context extends ELContext {
                        public ELResolver getELResolver(){ return new CompositeELResolver(); }
                        public FunctionMapper getFunctionMapper(){ return null; }
                        public VariableMapper getVariableMapper(){ return null; }
                        @SuppressWarnings("unchecked")
                        public <T> T convertToType(Object value,Class<T> type) {
                            if (type==Object.class) return (T)value;
                            if (type==String.class) return (T)(value==null ? "" : value.toString());
                            if (type==boolean.class || type==Boolean.class) {
                                if (value==null || "".equals(value)) return (T)Boolean.FALSE;
                                if (value instanceof Boolean b) return (T)b;
                                if (value instanceof String text) return (T)Boolean.valueOf(text);
                                throw new ELException("Invalid boolean input");
                            }
                            if (type==Long.class) return (T)Long.valueOf(value==null || "".equals(value) ? 0L
                                    : value instanceof Number n ? n.longValue() : Long.parseLong(value.toString()));
                            if (type==Double.class) return (T)Double.valueOf(value==null || "".equals(value) ? 0.0
                                    : value instanceof Number n ? n.doubleValue() : Double.parseDouble(value.toString()));
                            throw new ELException("Unsupported test conversion");
                        }
                    }
                    public static void main(String[] args) throws Exception {
                        if (ModuleLayer.boot().findModule("io.vidocq.peano.core").isPresent()
                                || ModuleLayer.boot().findModule("io.vidocq.peano.compiler").isPresent())
                            throw new AssertionError("Build tooling was resolved at runtime");
                        var context=new Context();
                        ValueExpression[] expressions=new ValueExpression[]{%s};
                        for (var expression:expressions) {
                            Object value=expression.getValue(context);
                            System.out.println((value==null ? "null" : value.getClass().getName()+":"+value)
                                +"|"+(expression.getType(context)==null ? "null" : expression.getType(context).getName())
                                +"|"+expression.isLiteralText());
                            if (!expression.isReadOnly(context) || expression.getValueReference(context)!=null)
                                throw new AssertionError("Constant metadata mismatch");
                            try { expression.setValue(context,7); throw new AssertionError("Constant was writable"); }
                            catch (PropertyNotWritableException expected) {}
                            var bytes=new java.io.ByteArrayOutputStream();
                            try (var output=new java.io.ObjectOutputStream(bytes)) { output.writeObject(expression); }
                            try (var input=new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
                                var restored=(ValueExpression)input.readObject();
                                if (!restored.equals(expression) || restored.hashCode()!=expression.hashCode()
                                        || !java.util.Objects.equals(value,restored.getValue(context)))
                                    throw new AssertionError("Serialization mismatch");
                            }
                        }
                        if (!expressions[2].equals(expressions[3]) || expressions[2].hashCode()!=expressions[3].hashCode())
                            throw new AssertionError("Parsed identity was not preserved across emitted classes");
                        if (new E2(String.class).equals(expressions[2]))
                            throw new AssertionError("Expected type was ignored");
                        if (expressions[7].getValue(context)!=expressions[7].getValue(context))
                            throw new AssertionError("Parsed text literal changed identity across evaluations");
                        int[] instanceConversions={0};
                        var instanceContext=new Context() {
                            public VariableMapper getVariableMapper() {
                                return new VariableMapper() {
                                    final ValueExpression left=new E7(Object.class),right=new E7(Object.class);
                                    public ValueExpression resolveVariable(String name) { return "left".equals(name) ? left : right; }
                                    public ValueExpression setVariable(String name,ValueExpression value) { throw new UnsupportedOperationException(); }
                                };
                            }
                            public <T> T convertToType(Object value,Class<T> type) {
                                if (type==String.class) return (T)(++instanceConversions[0]==1 ? "left" : "right");
                                return super.convertToType(value,type);
                            }
                        };
                        var instanceEquality=new InstanceEquality(instanceContext,Object.class);
                        if (!Boolean.FALSE.equals(instanceEquality.getValue(instanceContext))
                                || instanceConversions[0]!=2)
                            throw new AssertionError("Distinct compiled instances shared a text literal: "+instanceConversions[0]);
                        var instanceBytes=new java.io.ByteArrayOutputStream();
                        try (var output=new java.io.ObjectOutputStream(instanceBytes)) { output.writeObject(instanceEquality); }
                        instanceConversions[0]=0;
                        try (var input=new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(instanceBytes.toByteArray()))) {
                            var restored=(ValueExpression)input.readObject();
                            if (!restored.equals(instanceEquality) || !Boolean.FALSE.equals(restored.getValue(instanceContext))
                                    || instanceConversions[0]!=2)
                                throw new AssertionError("Serialized expression instances lost distinct literal identities");
                        }
                        var events=new java.util.ArrayList<String>();
                        context.addEvaluationListener(new EvaluationListener() {
                            public void beforeEvaluation(ELContext c,String source){ events.add("before:"+source); }
                            public void afterEvaluation(ELContext c,String source){ events.add("after:"+source); }
                        });
                        new E2(Object.class).getValue(context);
                        if (!events.equals(java.util.List.of("before:${42}","after:${42}")))
                            throw new AssertionError("Evaluation events mismatch");
                        var custom=new Context() {
                            public <T> T convertToType(Object value,Class<T> type) { return type.cast("custom"); }
                        };
                        if (!"custom".equals(new E2(String.class).getValue(custom)))
                            throw new AssertionError("Caller conversion was ignored");
                        var failureEvents=new java.util.ArrayList<String>();
                        var failure=new Context() {
                            public <T> T convertToType(Object value,Class<T> type) { throw new ELException("conversion failure"); }
                        };
                        failure.addEvaluationListener(new EvaluationListener() {
                            public void beforeEvaluation(ELContext c,String source){ failureEvents.add("before"); }
                            public void afterEvaluation(ELContext c,String source){ failureEvents.add("after"); }
                        });
                        try { new E2(String.class).getValue(failure); throw new AssertionError("Failure was swallowed"); }
                        catch (ELException expected) {}
                        if (!failureEvents.equals(java.util.List.of("before")))
                            throw new AssertionError("Failed-evaluation events differ from interpreter behavior");
                        if (!expressions[20].equals(expressions[21]))
                            throw new AssertionError("Unary aliases changed parsed identity");
                        int[] conversions={0};
                        var counted=new Context() {
                            public <T> T convertToType(Object value,Class<T> type) {
                                if (type==Long.class) conversions[0]++;
                                return super.convertToType(value,type);
                            }
                        };
                        if (expressions[17].getType(counted)!=Long.class || conversions[0]!=1)
                            throw new AssertionError("Unary metadata skipped operand conversion");
                        try { new BadUnary(Object.class).getValue(context); throw new AssertionError("Invalid unary input was accepted"); }
                        catch (ELException expected) {}
                        var order=new java.util.ArrayList<String>();
                        var ordered=new Context() {
                            public <T> T convertToType(Object value,Class<T> type) {
                                if (type==Long.class) order.add(value.getClass().getSimpleName()+":"+value);
                                return super.convertToType(value,type);
                            }
                        };
                        var orderedExpression=java.util.Arrays.stream(expressions)
                                .filter(value->"${1 + -'2'}".equals(value.getExpressionString())).findFirst().orElseThrow();
                        if (!Long.valueOf(-1).equals(orderedExpression.getValue(ordered))
                                || !order.equals(java.util.List.of("String:2","Long:1","Long:-2")))
                            throw new AssertionError("Arithmetic operand/conversion order changed: "+order);
                        try { new BadArithmetic(Object.class).getValue(context); throw new AssertionError("Modulo zero was accepted"); }
                        catch (ELException expected) {
                            if (!(expected.getCause() instanceof ArithmeticException)) throw expected;
                        }
                        order.clear();
                        if (orderedExpression.getType(ordered)!=Long.class
                                || !order.equals(java.util.List.of("String:2","Long:1","Long:-2")))
                            throw new AssertionError("Arithmetic metadata changed conversion effects");
                        var operandFailure=new Context() {
                            public <T> T convertToType(Object value,Class<T> type) {
                                if (type==Long.class && value instanceof String) throw new IllegalArgumentException("operand failure");
                                return super.convertToType(value,type);
                            }
                        };
                        try { orderedExpression.getValue(operandFailure); throw new AssertionError("Operand failure was swallowed"); }
                        catch (IllegalArgumentException expected) {}
                        var arithmeticFailure=new Context() {
                            public <T> T convertToType(Object value,Class<T> type) {
                                if (type==Long.class && value instanceof Long) throw new IllegalArgumentException("arithmetic failure");
                                return super.convertToType(value,type);
                            }
                        };
                        try { orderedExpression.getValue(arithmeticFailure); throw new AssertionError("Arithmetic failure was swallowed"); }
                        catch (ELException expected) {
                            if (!(expected.getCause() instanceof IllegalArgumentException)) throw expected;
                        }
                        try { new BadOperand(Object.class).getValue(context); throw new AssertionError("Invalid operand was accepted"); }
                        catch (ELException expected) {}
                        var logicalOrder=new java.util.ArrayList<String>();
                        var logicalContext=new Context() {
                            public <T> T convertToType(Object value,Class<T> type) {
                                if (type==boolean.class) logicalOrder.add(String.valueOf(value));
                                return super.convertToType(value,type);
                            }
                        };
                        var lazy=new LazyLogical(Object.class);
                        if (!Boolean.TRUE.equals(lazy.getValue(logicalContext))
                                || !logicalOrder.equals(java.util.List.of("false","false","true")))
                            throw new AssertionError("Logical conversion order mismatch: "+logicalOrder);
                        logicalOrder.clear();
                        if (lazy.getType(logicalContext)!=Boolean.class
                                || !logicalOrder.equals(java.util.List.of("false","false","true")))
                            throw new AssertionError("Logical metadata skipped conversions");
                        var altered=new Context() {
                            public <T> T convertToType(Object value,Class<T> type) {
                                if (type==boolean.class) return (T)Boolean.FALSE;
                                return super.convertToType(value,type);
                            }
                        };
                        if (!Boolean.FALSE.equals(new AlteredLogical(Object.class).getValue(altered)))
                            throw new AssertionError("Logical branch ignored caller boolean conversion");
                        try { new BadLogical(Object.class).getValue(context); throw new AssertionError("Invalid boolean was accepted"); }
                        catch (ELException expected) {}
                        try { new OverflowLogical(Object.class).getValue(context); throw new AssertionError("Selected overflow was accepted"); }
                        catch (ELException expected) {
                            if (!(expected.getCause() instanceof NumberFormatException)) throw expected;
                        }
                        try { lazy.getValue(operandFailure); throw new AssertionError("Selected operand failure was swallowed"); }
                        catch (IllegalArgumentException expected) {}
                        try { new OverflowLiteral(Object.class).getType(context); throw new AssertionError("Overflow metadata was accepted"); }
                        catch (ELException expected) {
                            if (!(expected.getCause() instanceof NumberFormatException)) throw expected;
                        }
                        logicalOrder.clear();
                        var conditional=new AlteredConditional(Object.class);
                        if (!Long.valueOf(7).equals(conditional.getValue(altered)))
                            throw new AssertionError("Conditional ignored caller branch selection");
                        var numericOrder=new java.util.ArrayList<String>();
                        var numericContext=new Context() {
                            public <T> T convertToType(Object value,Class<T> type) {
                                if (type==boolean.class || type==Long.class || type==Double.class)
                                    numericOrder.add(type.getName()+":"+String.valueOf(value));
                                return super.convertToType(value,type);
                            }
                        };
                        var dynamic=new DynamicArithmetic(Object.class);
                        if (!Long.valueOf(2).equals(dynamic.getValue(numericContext))
                                || !numericOrder.equals(java.util.List.of("boolean:true","boolean:false",
                                    "java.lang.Long:0","java.lang.Long:2")))
                            throw new AssertionError("Dynamic arithmetic conversion order: "+numericOrder);
                        try { new BadConditional(Object.class).getValue(context); throw new AssertionError("Invalid condition accepted"); }
                        catch (ELException expected) {}
                        try { new BadDynamicArithmetic(Object.class).getValue(context); throw new AssertionError("Dynamic modulo zero accepted"); }
                        catch (ELException expected) {
                            if (!(expected.getCause() instanceof ArithmeticException)) throw expected;
                        }
                        try { new BadDynamicUnary(Object.class).getValue(context); throw new AssertionError("Dynamic boolean minus accepted"); }
                        catch (ELException expected) {}
                        var stringOrder=new java.util.ArrayList<String>();
                        var stringContext=new Context() {
                            public <T> T convertToType(Object value,Class<T> type) {
                                if (type==String.class) stringOrder.add(String.valueOf(value));
                                return super.convertToType(value,type);
                            }
                        };
                        if (!Boolean.TRUE.equals(new StringEquality(Object.class).getValue(stringContext))
                                || !stringOrder.equals(java.util.List.of("x","x")))
                            throw new AssertionError("Distinct text operands skipped conversion: "+stringOrder);
                        numericOrder.clear();
                        if (!Boolean.TRUE.equals(new OrderedComparison(Object.class).getValue(numericContext))
                                || !numericOrder.equals(java.util.List.of("java.lang.Long:2","java.lang.Long:3")))
                            throw new AssertionError("Comparison conversion order: "+numericOrder);
                        numericOrder.clear();
                        if (!Boolean.TRUE.equals(new CachedEquality(Object.class).getValue(numericContext)) || !numericOrder.isEmpty())
                            throw new AssertionError("Reference identity performed extra conversion");
                        var nullRight=new Context() {
                            public <T> T convertToType(Object value,Class<T> type) {
                                if (type==Long.class && Long.valueOf(2).equals(value)) return null;
                                return super.convertToType(value,type);
                            }
                        };
                        if (!Boolean.FALSE.equals(new NullEquality(Object.class).getValue(nullRight)))
                            throw new AssertionError("Right null equality conversion mismatch");
                        numericOrder.clear();
                        var nullLeft=new Context() {
                            public <T> T convertToType(Object value,Class<T> type) {
                                if (type==Long.class) { numericOrder.add(String.valueOf(value)); return null; }
                                return super.convertToType(value,type);
                            }
                        };
                        try { new NullEquality(Object.class).getValue(nullLeft); throw new AssertionError("Left null equality accepted"); }
                        catch (ELException expected) {
                            if (!(expected.getCause() instanceof NullPointerException)
                                    || !numericOrder.equals(java.util.List.of("1","2"))) throw expected;
                        }
                        try { new OrderedComparison(Object.class).getValue(arithmeticFailure); throw new AssertionError("Comparison failure swallowed"); }
                        catch (ELException expected) {
                            if (!(expected.getCause() instanceof IllegalArgumentException)) throw expected;
                        }
                        stringOrder.clear();
                        if (!"1x2".equals(new OrderedConcat(Object.class).getValue(stringContext))
                                || !stringOrder.equals(java.util.List.of("x","2","1","x2")))
                            throw new AssertionError("Concat operand/conversion order: "+stringOrder);
                        try { new FailingSequence(Object.class).getValue(context); throw new AssertionError("Sequence skipped left failure"); }
                        catch (ELException expected) {}
                        var collectionOrder=new java.util.ArrayList<String>();
                        var collectionContext=new Context() {
                            public <T> T convertToType(Object value,Class<T> type) {
                                if (type==Long.class) collectionOrder.add(String.valueOf(value));
                                return super.convertToType(value,type);
                            }
                        };
                        if (!new OrderedMap(Object.class).getValue(collectionContext).equals(java.util.Map.of(-1L,-2L,-3L,-4L))
                                || !collectionOrder.equals(java.util.List.of("1","2","3","4")))
                            throw new AssertionError("Map key/value evaluation order: "+collectionOrder);
                        var fresh=new FreshList(Object.class);
                        var first=(java.util.List)fresh.getValue(context); first.add(99);
                        if (!fresh.getValue(context).equals(java.util.List.of(1L)))
                            throw new AssertionError("Collection literal retained mutation");
                        try { new BadCollectionComparison(Object.class).getValue(context); throw new AssertionError("Noncomparable lists accepted"); }
                        catch (ELException expected) {}
                        try (var output=new java.io.ObjectOutputStream(java.nio.file.Files.newOutputStream(java.nio.file.Path.of(args[0])))) {
                            output.writeObject(new E2(Object.class));
                        }
                    }
                }
                """.formatted(String.join(",",constructors));
        byte[] invalidUnary=ExpressionCompiler.compile("demo.BadUnary","${-true}");
        assertTrue(ClassFile.of().verify(invalidUnary).isEmpty());
        Files.write(classes.resolve("demo/BadUnary.class"),invalidUnary);
        byte[] invalidArithmetic=ExpressionCompiler.compile("demo.BadArithmetic","${1%0}");
        assertTrue(ClassFile.of().verify(invalidArithmetic).isEmpty());
        Files.write(classes.resolve("demo/BadArithmetic.class"),invalidArithmetic);
        byte[] invalidOperand=ExpressionCompiler.compile("demo.BadOperand","${(-true)+1}");
        assertTrue(ClassFile.of().verify(invalidOperand).isEmpty());
        Files.write(classes.resolve("demo/BadOperand.class"),invalidOperand);
        for (var entry:java.util.Map.of("LazyLogical","${false or not empty -'2'}",
                "AlteredLogical","${true and (1%0)}", "BadLogical","${true and 7}",
                "OverflowLogical","${false or 9223372036854775808}",
                "OverflowLiteral","${9223372036854775808}").entrySet()) {
            byte[] bytes=ExpressionCompiler.compile("demo."+entry.getKey(),entry.getValue());
            assertTrue(ClassFile.of().verify(bytes).isEmpty());
            Files.write(classes.resolve("demo/"+entry.getKey()+".class"),bytes);
        }
        for (var entry:java.util.Map.of("AlteredConditional","${true ? 1%0 : 7}",
                "DynamicArithmetic","${(true ? null : 1)+(false ? 1.5 : 2)}",
                "BadConditional","${7 ? 1 : 2}", "BadDynamicArithmetic","${(true ? 1 : 1.5)%0}",
                "BadDynamicUnary","${-(true ? true : 1)}").entrySet()) {
            byte[] bytes=ExpressionCompiler.compile("demo."+entry.getKey(),entry.getValue());
            assertTrue(ClassFile.of().verify(bytes).isEmpty());
            Files.write(classes.resolve("demo/"+entry.getKey()+".class"),bytes);
        }
        for (var entry:java.util.Map.of("StringEquality","${'x'=='x'}", "CachedEquality","${1==1}",
                "OrderedComparison","${2<3}", "OrderedConcat","${1 += ('x'+=2)}",
                "FailingSequence","${1%0;7}", "NullEquality","${1==2}").entrySet()) {
            byte[] bytes=ExpressionCompiler.compile("demo."+entry.getKey(),entry.getValue());
            assertTrue(ClassFile.of().verify(bytes).isEmpty());
            Files.write(classes.resolve("demo/"+entry.getKey()+".class"),bytes);
        }
        for (var entry:java.util.Map.of("OrderedMap","${{-'1':-'2',-'3':-'4'}}",
                "FreshList","${[1]}", "BadCollectionComparison","${[1]<[2]}",
                "InstanceEquality","${left==right}").entrySet()) {
            byte[] bytes=ExpressionCompiler.compile("demo."+entry.getKey(),entry.getValue());
            assertTrue(ClassFile.of().verify(bytes).isEmpty());
            Files.write(classes.resolve("demo/"+entry.getKey()+".class"),bytes);
        }
        Path source=temporary.resolve("sources");
        Files.createDirectories(source);
        Files.writeString(source.resolve("Main.java"),main);
        Files.writeString(source.resolve("RestoreMain.java"),"""
                package demo;
                public class RestoreMain {
                    public static void main(String[] args) throws Exception {
                        try (var input=new java.io.ObjectInputStream(java.nio.file.Files.newInputStream(java.nio.file.Path.of(args[0])))) {
                            var expression=(jakarta.el.ValueExpression)input.readObject();
                            throw new AssertionError("A stale expression was restored against changed bytecode: "+expression.getExpressionString());
                        } catch (java.io.InvalidClassException expected) {
                            System.out.println("PEANO_STALE_ARTIFACT_REJECTED");
                        }
                    }
                }
                """);
        Files.writeString(source.resolve("CompatibleMain.java"),"""
                package demo;
                public class CompatibleMain {
                    public static void main(String[] args) throws Exception {
                        try (var input=new java.io.ObjectInputStream(java.nio.file.Files.newInputStream(java.nio.file.Path.of(args[0])))) {
                            var expression=(jakarta.el.ValueExpression)input.readObject();
                            if (!Long.valueOf(42).equals(expression.getValue(new Main.Context()))
                                    || !expression.equals(new E2(Object.class))
                                    || !"${42}".equals(expression.getExpressionString()))
                                throw new AssertionError("Equivalent bytecode changed serialized meaning");
                            System.out.println("PEANO_EQUIVALENT_ARTIFACT_ACCEPTED");
                        }
                    }
                }
                """);
        Files.writeString(source.resolve("LegacySnapshotMain.java"),"""
                package demo;
                public class LegacySnapshotMain {
                    public static void main(String[] args) throws Exception {
                        var expression=new E7(Object.class);
                        if (!"hello".equals(expression.getValue(new Main.Context())))
                            throw new AssertionError("Legacy fixture value changed");
                        try (var output=new java.io.ObjectOutputStream(java.nio.file.Files.newOutputStream(java.nio.file.Path.of(args[0])))) {
                            output.writeObject(expression);
                        }
                    }
                }
                """);
        Files.writeString(source.resolve("module-info.java"),"module peano.compiledconsumer { requires io.vidocq.peano.api; }");
        String modulePath=String.join(File.pathSeparator,location(ExpressionFactory.class).toString(),location(AccessorTable.class).toString());
        run(List.of(javaBin("javac"),"--module-path",modulePath,"--patch-module",
                "peano.compiledconsumer="+classes,"-d",classes.toString(),
                source.resolve("module-info.java").toString(),source.resolve("Main.java").toString(),source.resolve("RestoreMain.java").toString(),
                source.resolve("CompatibleMain.java").toString(),source.resolve("LegacySnapshotMain.java").toString()));
        Path snapshot=temporary.resolve("expression.bin");
        String output=run(List.of(javaBin("java"),"--module-path",
                modulePath+File.pathSeparator+classes,"--module","peano.compiledconsumer/demo.Main",snapshot.toString()));
        assertEquals(expected,output.lines().toList(),output);
        Files.write(classes.resolve("demo/E2.class"),ExpressionCompiler.compile("demo.E2","#{ 42 }"));
        String compatible=run(List.of(javaBin("java"),"--module-path",
                modulePath+File.pathSeparator+classes,"--module","peano.compiledconsumer/demo.CompatibleMain",snapshot.toString()));
        assertTrue(compatible.contains("PEANO_EQUIVALENT_ARTIFACT_ACCEPTED"),compatible);
        Files.write(classes.resolve("demo/E2.class"),ExpressionCompiler.compile("demo.E2","${43}"));
        String rejected=run(List.of(javaBin("java"),"--module-path",
                modulePath+File.pathSeparator+classes,"--module","peano.compiledconsumer/demo.RestoreMain",snapshot.toString()));
        assertTrue(rejected.contains("PEANO_STALE_ARTIFACT_REJECTED"),rejected);
        try (var resource=getClass().getResourceAsStream("/legacy-v2-literal.class.b64")) {
            byte[] legacy=java.util.Base64.getDecoder().decode(new String(resource.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).trim());
            assertTrue(ClassFile.of().verify(legacy).isEmpty());
            Files.write(classes.resolve("demo/E7.class"),legacy);
        }
        Path legacySnapshot=temporary.resolve("legacy.bin");
        run(List.of(javaBin("java"),"--module-path",modulePath+File.pathSeparator+classes,
                "--module","peano.compiledconsumer/demo.LegacySnapshotMain",legacySnapshot.toString()));
        Files.write(classes.resolve("demo/E7.class"),ExpressionCompiler.compile("demo.E7","${'hello'}"));
        String migration=run(List.of(javaBin("java"),"--module-path",modulePath+File.pathSeparator+classes,
                "--module","peano.compiledconsumer/demo.RestoreMain",legacySnapshot.toString()));
        assertTrue(migration.contains("PEANO_STALE_ARTIFACT_REJECTED"),migration);
    }

    @Test
    void rejectsUnsupportedNodesAndInvalidBinaryNamesAtBuildTime() {
        assertThrows(ELException.class,()->ExpressionCompiler.compile("demo.Invalid","${function()}"));
        assertThrows(ELException.class,()->ExpressionCompiler.compile("demo.Invalid","${x->x}"));
        assertThrows(IllegalArgumentException.class,()->ExpressionCompiler.compile("demo.bad-name","${7}"));
        assertThrows(NullPointerException.class,()->ExpressionCompiler.compile("demo.Missing",null));
    }

    private String render(Object value) {
        return value==null ? "null" : value.getClass().getName()+":"+value;
    }

    @Test
    void reportsOversizedClassFileConstantsAsCompilerDiagnostics() {
        var failure=assertThrows(ELException.class,
                ()->ExpressionCompiler.compile("demo.Large","x".repeat(70_000)));
        assertTrue(failure.getMessage().contains("demo.Large"),failure.getMessage());
        assertNotNull(failure.getCause());
    }
    private Path location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    }
    private String javaBin(String tool) {
        return Path.of(System.getProperty("java.home"),"bin",tool).toString();
    }
    private String run(List<String> args) throws Exception {
        Path log=temporary.resolve("process.log");
        var process=new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        assertTrue(process.waitFor(30,TimeUnit.SECONDS));
        String output=Files.readString(log);
        assertEquals(0,process.exitValue(),output);
        return output;
    }
}

