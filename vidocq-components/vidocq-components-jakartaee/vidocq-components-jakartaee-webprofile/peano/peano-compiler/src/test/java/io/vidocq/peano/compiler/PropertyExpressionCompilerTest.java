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

class PropertyExpressionCompilerTest {
    @TempDir Path temporary;
    @Test
    void propertyProtocolRunsWithoutBuildModules() throws Exception {
        Path classes=temporary.resolve("classes"); Files.createDirectories(classes.resolve("demo"));
        for (var entry:Map.of("Property","${bean.value}","Nested","${bean.child.value}",
                "Indexed","${bean[key]}","NullBase","${nil[missing]}",
                "NullKey","${bean[nil]}","LiteralKey","${bean['value']}",
                "DuplicateRoots","${bean[bean.key]}","ComputedKey","${bean[true ? 'value' : 'child']}").entrySet()) {
            byte[] bytes=ExpressionCompiler.compile("demo."+entry.getKey(),entry.getValue());
            assertTrue(ClassFile.of().verify(bytes).isEmpty(),entry.getValue()+ClassFile.of().verify(bytes));
            for (var item:ClassFile.of().parse(bytes).constantPool())
                if (item instanceof java.lang.classfile.constantpool.ClassEntry type)
                    assertFalse(type.name().stringValue().startsWith("io/vidocq/peano/core/")
                            || type.name().stringValue().startsWith("io/vidocq/peano/compiler/"));
            Files.write(classes.resolve("demo/"+entry.getKey()+".class"),bytes);
        }
        for (var entry:Map.of("Composite","value=${bean.value};${key}",
                "Conditional","${true ? bean.value : missing}",
                "Logical","${true or missing}", "BoundList","${[bean.value,key]}",
                "BoundEmpty","${empty array}", "BoundConcat","${bean.value += key}",
                "BoundSequence","${bean.value;key}").entrySet()) {
            byte[] bytes=ExpressionCompiler.compile("demo."+entry.getKey(),entry.getValue());
            assertTrue(ClassFile.of().verify(bytes).isEmpty(),entry.getValue()+ClassFile.of().verify(bytes));
            Files.write(classes.resolve("demo/"+entry.getKey()+".class"),bytes);
        }
        Path source=temporary.resolve("sources"); Files.createDirectories(source);
        try (var resource=getClass().getResourceAsStream("/property-app/Main.java")) {
            Files.copy(resource,source.resolve("Main.java"));
        }
        Files.writeString(source.resolve("module-info.java"),"module peano.propertyconsumer { requires io.vidocq.peano.api; }");
        String modules=location(ExpressionFactory.class)+File.pathSeparator+location(AccessorTable.class);
        run(List.of(bin("javac"),"--module-path",modules,"--patch-module","peano.propertyconsumer="+classes,
                "-d",classes.toString(),source.resolve("module-info.java").toString(),source.resolve("Main.java").toString()));
        for (String mode:List.of("allow","deny"))
            assertTrue(run(List.of(bin("java"),"-Dpeano.reflection="+mode,"--module-path",
                    modules+File.pathSeparator+classes,"--module","peano.propertyconsumer/demo.Main"))
                    .contains("PEANO_COMPILED_PROPERTY_OK"));
    }
    private Path location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    }
    private String bin(String tool) { return Path.of(System.getProperty("java.home"),"bin",tool).toString(); }
    private String run(List<String> args) throws Exception {
        Path log=temporary.resolve("process.log");
        var process=new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        assertTrue(process.waitFor(30,TimeUnit.SECONDS));
        String output=Files.readString(log); assertEquals(0,process.exitValue(),output); return output;
    }
}

