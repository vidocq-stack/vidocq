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
                "${'x'}${'y'}", "\\${escaped}");
        Path classes=temporary.resolve("classes");
        Files.createDirectories(classes.resolve("demo"));
        var constructors=new ArrayList<String>();
        var factory=ExpressionFactory.newInstance();
        var oracle=new StandardELContext(factory);
        var expected=new ArrayList<String>();
        for (int i=0;i<sources.size();i++) {
            byte[] bytes=ExpressionCompiler.compile("demo.E"+i,sources.get(i));
            assertTrue(ClassFile.of().verify(bytes).isEmpty());
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
                    }
                }
                """.formatted(String.join(",",constructors));
        Path source=temporary.resolve("sources");
        Files.createDirectories(source);
        Files.writeString(source.resolve("Main.java"),main);
        Files.writeString(source.resolve("module-info.java"),"module peano.compiledconsumer { requires io.vidocq.peano.api; }");
        String modulePath=String.join(File.pathSeparator,location(ExpressionFactory.class).toString(),location(AccessorTable.class).toString());
        run(List.of(javaBin("javac"),"--module-path",modulePath,"--patch-module",
                "peano.compiledconsumer="+classes,"-d",classes.toString(),
                source.resolve("module-info.java").toString(),source.resolve("Main.java").toString()));
        String output=run(List.of(javaBin("java"),"--module-path",
                modulePath+File.pathSeparator+classes,"--module","peano.compiledconsumer/demo.Main"));
        assertEquals(expected,output.lines().toList(),output);
    }

    @Test
    void rejectsUnsupportedNodesAndInvalidBinaryNamesAtBuildTime() {
        assertThrows(ELException.class,()->ExpressionCompiler.compile("demo.Invalid","${bean.value}"));
        assertThrows(ELException.class,()->ExpressionCompiler.compile("demo.Invalid","${-7}"));
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

