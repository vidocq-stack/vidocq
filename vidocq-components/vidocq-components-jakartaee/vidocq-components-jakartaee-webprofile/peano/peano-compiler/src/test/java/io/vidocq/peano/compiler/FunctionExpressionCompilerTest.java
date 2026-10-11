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
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import jakarta.el.ExpressionFactory;
import io.vidocq.peano.spi.AccessorTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FunctionExpressionCompilerTest {
    @TempDir Path temporary;
    @Test
    void mappedFunctionsUseDirectTablesWithoutCoreOrCompiler() throws Exception {
        Path classes=temporary.resolve("classes"); Files.createDirectories(classes.resolve("demo"));
        for (var entry:Map.of("Sum","${f:sum('7',2)}","Nested","${f:sum(f:sum(1,2),3)}",
                "None","${f:none()}","Boom","${f:boom()}","Original","${f:original()}",
                "Fallback","${f:abs(-7)}","Missing","${true or f:missing()}",
                "Join","${f:join('a','b')}").entrySet()) {
            byte[] bytes=ExpressionCompiler.compile("demo."+entry.getKey(),entry.getValue());
            assertTrue(ClassFile.of().verify(bytes).isEmpty(),entry.getValue()+ClassFile.of().verify(bytes));
            for (var item:ClassFile.of().parse(bytes).constantPool())
                if (item instanceof java.lang.classfile.constantpool.ClassEntry type)
                    assertFalse(type.name().stringValue().startsWith("io/vidocq/peano/core/")
                            || type.name().stringValue().startsWith("io/vidocq/peano/compiler/"));
            Files.write(classes.resolve("demo/"+entry.getKey()+".class"),bytes);
        }
        for (var entry:Map.of("Named","${callable(2)}", "Mapped","${mapped(1,2)}",
                "Construct","${Constructed(7)}", "Imported","${sum(1,2)}",
                "GeneralLambda","${factory()(3)}", "InvalidCallable","${callable(0)(1%0)}",
                "UnresolvedCallable","${missing(1%0)}", "NullCallable","${nil(1%0)}").entrySet()) {
            byte[] bytes=ExpressionCompiler.compile("demo."+entry.getKey(),entry.getValue());
            assertTrue(ClassFile.of().verify(bytes).isEmpty(),entry.getValue()+ClassFile.of().verify(bytes));
            Files.write(classes.resolve("demo/"+entry.getKey()+".class"),bytes);
        }
        Path source=temporary.resolve("sources"); Files.createDirectories(source);
        for (String file:List.of("Main.java","Provider.java","Constructed.java")) {
            try (var resource=getClass().getResourceAsStream("/function-app/"+file)) { Files.copy(resource,source.resolve(file)); }
        }
        Files.writeString(source.resolve("module-info.java"),"""
                module peano.functionconsumer {
                    requires io.vidocq.peano.api;
                    provides io.vidocq.peano.spi.AccessorProvider with demo.Provider;
                }
                """);
        String modules=location(ExpressionFactory.class)+File.pathSeparator+location(AccessorTable.class);
        run(List.of(bin("javac"),"--module-path",modules,"--patch-module","peano.functionconsumer="+classes,
                "-d",classes.toString(),source.resolve("module-info.java").toString(),
                source.resolve("Main.java").toString(),source.resolve("Provider.java").toString(),source.resolve("Constructed.java").toString()));
        for (String mode:List.of("allow","deny"))
            assertTrue(run(List.of(bin("java"),"-Dpeano.reflection="+mode,"--module-path",
                    modules+File.pathSeparator+classes,"--module","peano.functionconsumer/demo.Main"))
                    .contains("PEANO_COMPILED_FUNCTION_OK"));
        String coreModules=modules+File.pathSeparator+location(ExpressionFactory.newInstance().getClass());
        assertTrue(run(List.of(bin("java"),"-Dpeano.reflection=allow","--module-path",
                coreModules+File.pathSeparator+classes,"--module","peano.functionconsumer/demo.Main","core-fallback"))
                .contains("PEANO_COMPILED_FUNCTION_OK"));
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

