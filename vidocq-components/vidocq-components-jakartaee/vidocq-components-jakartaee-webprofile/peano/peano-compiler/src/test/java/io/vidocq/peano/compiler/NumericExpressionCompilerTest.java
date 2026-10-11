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
import java.math.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import jakarta.el.*;
import io.vidocq.peano.spi.AccessorTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NumericExpressionCompilerTest {
    @TempDir Path temporary;
    @Test
    void runtimeNumericDomainsMatchInterpreterWithoutBuildModules() throws Exception {
        var values=Arrays.<Object>asList(null,0L,7L,-2L,Long.MAX_VALUE,1.5,-0.0,Double.NaN,
                Double.POSITIVE_INFINITY,new BigInteger("7"),new BigInteger("123456789012345678901234567890"),
                new BigDecimal("7.0"),new BigDecimal("1.25"),(byte)7,(short)-3,7,1.5f,
                Character.valueOf('7'),Character.valueOf('\uffff'),"7","1.5","2e2","","invalid",true,
                Byte.MIN_VALUE,Short.MIN_VALUE,Integer.MIN_VALUE,-0.0f);
        var bodies=List.of("${a+b}","${a-b}","${a*b}","${a/b}","${a%b}","${-a}");
        Path classes=temporary.resolve("classes"); Files.createDirectories(classes.resolve("demo"));
        for (int i=0;i<bodies.size();i++) {
            byte[] bytes=ExpressionCompiler.compile("demo.N"+i,bodies.get(i));
            assertTrue(ClassFile.of().verify(bytes).isEmpty(),bodies.get(i)+ClassFile.of().verify(bytes));
            Files.write(classes.resolve("demo/N"+i+".class"),bytes);
        }
        var factory=ExpressionFactory.newInstance();
        var oracle=new StandardELContext(factory);
        var roots=new HashMap<String,Object>();
        oracle.addELResolver(new ELResolver() {
            public Object getValue(ELContext c,Object base,Object property) {
                if (base==null && roots.containsKey(property)) { c.setPropertyResolved(base,property); return roots.get(property); }
                return null;
            }
            public Class<?> getType(ELContext c,Object b,Object p) { return Object.class; }
            public boolean isReadOnly(ELContext c,Object b,Object p) { return true; }
            public void setValue(ELContext c,Object b,Object p,Object v) {}
            public Class<?> getCommonPropertyType(ELContext c,Object b) { return String.class; }
        });
        var cases=new ArrayList<String>();
        for (int op=0;op<bodies.size();op++) {
            var expression=factory.createValueExpression(oracle,bodies.get(op),Object.class);
            for (int a=0;a<values.size();a++) for (int b=0;b<(op==5 ? 1 : values.size());b++) {
                roots.put("a",values.get(a)); roots.put("b",values.get(b));
                cases.add(op+"\t"+a+"\t"+b+"\t"+outcome(()->expression.getValue(oracle))+"\t"+outcome(()->expression.getType(oracle)));
            }
        }
        Files.write(temporary.resolve("values.txt"),values.stream().map(this::wire).toList());
        Files.write(temporary.resolve("cases.txt"),cases);
        Path source=temporary.resolve("sources"); Files.createDirectories(source);
        try (var resource=getClass().getResourceAsStream("/numeric-app/Main.java")) { Files.copy(resource,source.resolve("Main.java")); }
        Files.writeString(source.resolve("module-info.java"),"module peano.numericconsumer { requires io.vidocq.peano.api; }");
        String modules=location(ExpressionFactory.class)+File.pathSeparator+location(AccessorTable.class);
        run(List.of(bin("javac"),"--module-path",modules,"--patch-module","peano.numericconsumer="+classes,
                "-d",classes.toString(),source.resolve("module-info.java").toString(),source.resolve("Main.java").toString()));
        for (String mode:List.of("allow","deny"))
            assertTrue(run(List.of(bin("java"),"-Dpeano.reflection="+mode,"--module-path",modules+File.pathSeparator+classes,
                    "--module","peano.numericconsumer/demo.Main",temporary.toString())).contains("PEANO_NUMERIC_CASES:"+cases.size()));
    }
    private String outcome(java.util.function.Supplier<Object> operation) {
        try {
            Object value=operation.get();
            return value==null ? "null" : value instanceof Class<?> c ? c.getName() : value.getClass().getName()+":"+value;
        } catch (RuntimeException failure) {
            return "error:"+failure.getClass().getName()+":"+(failure.getCause()==null ? "none" : failure.getCause().getClass().getName());
        }
    }
    private String wire(Object value) {
        if (value==null) return "null:";
        String text=value instanceof Character c ? Integer.toString(c) : value.toString();
        if (value instanceof String) text=Base64.getEncoder().encodeToString(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return value.getClass().getSimpleName()+":"+text;
    }
    private Path location(Class<?> type) throws Exception { return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()); }
    private String bin(String tool) { return Path.of(System.getProperty("java.home"),"bin",tool).toString(); }
    private String run(List<String> args) throws Exception {
        Path log=temporary.resolve("process.log");
        var process=new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        assertTrue(process.waitFor(60,TimeUnit.SECONDS));
        String output=Files.readString(log); assertEquals(0,process.exitValue(),output); return output;
    }
}

