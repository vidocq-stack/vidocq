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

import static org.junit.jupiter.api.Assertions.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import jakarta.el.ExpressionFactory;
import io.vidocq.peano.spi.AccessorProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PropertyProcessorTest {
    @TempDir Path temporary;

    private record Compilation(int status, Path classes, Path generated, String log) {}

    private Path api() throws Exception {
        return Path.of(AccessorProvider.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }
    private Path el() throws Exception {
        return Path.of(ExpressionFactory.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }
    private Path processor() throws Exception {
        return Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI()).getParent().resolve("classes");
    }

    @Test
    void generatesModularPropertyProvidersAndRunsWithoutTheProcessor() throws Exception {
        Path sources = temporary.resolve("sources");
        Files.createDirectories(sources);
        for (String name : List.of("module-info.java","Bean.java","Item.java","Explicit.java","PublicBean.java","Main.java")) {
            try (var input = getClass().getResourceAsStream("/property-bootstrap/"+name)) {
                assertNotNull(input);
                Files.copy(input,sources.resolve(name));
            }
        }
        var compilation = compile(sources,"-Apeano.accessorTypes=demo.Explicit");
        assertEquals(0,compilation.status(),compilation.log());
        Path core = Path.of(ExpressionFactory.newInstance().getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
        String result = run(List.of(javaBin("java"),"--module-path",
                String.join(File.pathSeparator,el().toString(),api().toString(),core.toString(),compilation.classes().toString()),
                "--module","peano.propertyconsumer/demo.Main"),0);
        assertTrue(result.contains("PEANO_PROPERTIES_OK"));
        for (String name : List.of("demo.Bean","demo.Item","demo.Explicit","demo.exported.PublicBean")) {
            Path fragment = compilation.classes().resolve("META-INF/peano/accessor-providers/"+name+"__PeanoAccessors.txt");
            assertEquals("provides io.vidocq.peano.spi.AccessorProvider with "+name+"__PeanoAccessors;\n",Files.readString(fragment));
        }
    }

    @Test
    void discoversExplicitTypesEvenWithoutAnyAnnotations() throws Exception {
        Path sources = sources("package demo; public class Model { public int getValue(){ return 7; } }");
        var compilation = compile(sources,"-Apeano.accessorTypes=demo.Model");
        assertEquals(0,compilation.status(),compilation.log());
        assertTrue(Files.exists(compilation.generated().resolve("demo/Model__PeanoAccessors.java")));
    }

    @Test
    void diagnosesTypesOutsideTheCurrentModule() throws Exception {
        var result = compile(sources("package demo; public class Model {}"),"-Apeano.accessorTypes=java.lang.String");
        assertNotEquals(0,result.status());
        assertTrue(result.log().contains("current application module"),result.log());
    }

    @Test
    void diagnosesUnknownExplicitTypes() throws Exception {
        var result = compile(sources("package demo; public class Model {}"),"-Apeano.accessorTypes=demo.Missing");
        assertNotEquals(0,result.status());
        assertTrue(result.log().contains("Unknown accessor type"),result.log());
    }

    @Test
    void diagnosesAmbiguousWriteOnlySetters() throws Exception {
        var result = compile(sources("""
                package demo;
                @io.vidocq.peano.annotation.ELAccessible
                public class Model {
                    public void setValue(int value) {}
                    public void setValue(String value) {}
                }
                """));
        assertNotEquals(0,result.status());
        assertTrue(result.log().contains("Ambiguous setter"),result.log());
    }

    @Test
    void diagnosesInaccessibleNestedModels() throws Exception {
        var result = compile(sources("""
                package demo;
                public class Model {
                    @io.vidocq.peano.annotation.ELAccessible
                    private static class Hidden { public String getValue(){ return ""; } }
                }
                """));
        assertNotEquals(0,result.status());
        assertTrue(result.log().contains("private enclosing"),result.log());
    }

    @Test
    void waitsForOptionSelectedModelsGeneratedInALaterRound() throws Exception {
        Path source = sources("package demo; public class Model {}");
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        var log = new java.io.StringWriter();
        var producer = new javax.annotation.processing.AbstractProcessor() {
            private boolean emitted;
            @Override public java.util.Set<String> getSupportedAnnotationTypes() { return java.util.Set.of("*"); }
            @Override public javax.lang.model.SourceVersion getSupportedSourceVersion() {
                return javax.lang.model.SourceVersion.RELEASE_25;
            }
            @Override public boolean process(java.util.Set<? extends javax.lang.model.element.TypeElement> annotations,
                    javax.annotation.processing.RoundEnvironment round) {
                if (!emitted && !round.processingOver()) {
                    emitted = true;
                    try (var writer = processingEnv.getFiler()
                            .createSourceFile("peano.propertynegative/demo.Later").openWriter()) {
                        writer.write("package demo; public class Later { public int getValue(){ return 7; } }");
                    } catch (java.io.IOException failure) {
                        throw new java.io.UncheckedIOException(failure);
                    }
                }
                return false;
            }
        };
        Path generated = temporary.resolve("generated");
        try (var files = compiler.getStandardFileManager(null,null,null)) {
            var task = compiler.getTask(log,files,null,List.of("--module-path",
                    String.join(File.pathSeparator,el().toString(),api().toString()),"-proc:full",
                    "-Apeano.accessorTypes=demo.Later","-d",temporary.resolve("classes").toString(),
                    "-s",generated.toString()),null,files.getJavaFileObjectsFromPaths(
                            List.of(source.resolve("module-info.java"),source.resolve("Model.java"))));
            task.setProcessors(List.of(new PeanoAccessorProcessor(),producer));
            assertTrue(task.call(),log.toString());
        }
        assertTrue(Files.exists(generated.resolve("demo/Later__PeanoAccessors.java")));
    }

    @Test
    void diagnosesTypeVariableBeanProperties() throws Exception {
        var result = compile(sources("""
                package demo;
                @io.vidocq.peano.annotation.ELAccessible
                public class Model<T> {
                    public T getValue(){ return null; }
                    public void setValue(T value) {}
                }
                """));
        assertNotEquals(0,result.status());
        assertTrue(result.log().contains("Type-variable property"),result.log());
    }

    private Path sources(String model) throws Exception {
        Path source = temporary.resolve("sources");
        Files.createDirectories(source);
        Files.writeString(source.resolve("module-info.java"),"module peano.propertynegative { requires io.vidocq.peano.api; }");
        Files.writeString(source.resolve("Model.java"),model);
        return source;
    }

    private Compilation compile(Path source,String... options) throws Exception {
        Path classes = temporary.resolve("classes");
        Path generated = temporary.resolve("generated");
        var args = new java.util.ArrayList<String>(List.of(javaBin("javac"),"--module-path",
                String.join(File.pathSeparator,el().toString(),api().toString()),
                "--processor-module-path",String.join(File.pathSeparator,processor().toString(),api().toString(),el().toString()),
                "-proc:full","-d",classes.toString(),"-s",generated.toString()));
        args.addAll(List.of(options));
        try (var paths = Files.list(source)) {
            paths.filter(p->p.toString().endsWith(".java")).sorted().forEach(p->args.add(p.toString()));
        }
        Path log = temporary.resolve("compile.log");
        var process = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        assertTrue(process.waitFor(30,TimeUnit.SECONDS));
        return new Compilation(process.exitValue(),classes,generated,Files.readString(log));
    }

    private String run(List<String> args,int expected) throws Exception {
        Path log = temporary.resolve("run.log");
        var process = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        assertTrue(process.waitFor(30,TimeUnit.SECONDS));
        String output = Files.readString(log);
        assertEquals(expected,process.exitValue(),output);
        return output;
    }

    private String javaBin(String tool) {
        return Path.of(System.getProperty("java.home"),"bin",tool).toString();
    }
}

