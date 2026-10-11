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
        for (String name : List.of("module-info.java","Bean.java","Derived.java","Callbacks.java","Item.java","Explicit.java","PublicBean.java","Main.java","DenialMain.java")) {
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
        for (String policy:List.of("allow","deny")) {
            String checked=run(List.of(javaBin("java"),"-Dpeano.reflection="+policy,"--module-path",
                    String.join(File.pathSeparator,el().toString(),api().toString(),core.toString(),compilation.classes().toString()),
                    "--module","peano.propertyconsumer/demo.DenialMain"),0);
            assertTrue(checked.contains("PEANO_DENIAL_OK"));
        }
        String invalid=run(List.of(javaBin("java"),"-Dpeano.reflection=denny","--module-path",
                String.join(File.pathSeparator,el().toString(),api().toString(),core.toString(),compilation.classes().toString()),
                "--module","peano.propertyconsumer/demo.DenialMain"),1);
        assertTrue(invalid.contains("Invalid peano.reflection value"),invalid);
        String minimal=run(List.of(javaBin("java"),"-Dpeano.reflection=deny",
                "--limit-modules","java.base,jakarta.el,io.vidocq.peano.api,io.vidocq.peano.core,peano.propertyconsumer",
                "--module-path",String.join(File.pathSeparator,el().toString(),api().toString(),core.toString(),compilation.classes().toString()),
                "--module","peano.propertyconsumer/demo.DenialMain","no-desktop"),0);
        assertTrue(minimal.contains("PEANO_DENIAL_OK"),minimal);
        for (String name : List.of("demo.Bean","demo.Derived","demo.Item","demo.Explicit","demo.exported.PublicBean")) {
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
    void integratesApplicationOwnedPropertyEditorsThroughTheExistingConversionHook() throws Exception {
        Path source=temporary.resolve("sources");
        Files.createDirectories(source);
        for (String name:List.of("module-info.java","Main.java")) {
            try (var input=getClass().getResourceAsStream("/property-editor/"+name)) {
                assertNotNull(input);
                Files.copy(input,source.resolve(name));
            }
        }
        var compilation=compile(source);
        assertEquals(0,compilation.status(),compilation.log());
        Path core=Path.of(ExpressionFactory.newInstance().getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
        String result=run(List.of(javaBin("java"),"-Dpeano.reflection=deny","--module-path",
                String.join(File.pathSeparator,el().toString(),api().toString(),core.toString(),compilation.classes().toString()),
                "--module","peano.editorconsumer/demo.Main"),0);
        assertTrue(result.contains("PEANO_EDITOR_OK"),result);
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
        Path generated=compileWithLaterModel(source,"demo.Later",
                "package demo; public class Later { public int getValue(){ return 7; } }");
        assertTrue(Files.exists(generated.resolve("demo/Later__PeanoAccessors.java")));
    }

    @Test
    void selectsSourceSubtypesForExplicitRootsAndSkipsUnrelatedPrivateTypes() throws Exception {
        Path source=sources("""
                package demo;
                public class Model {
                    public int getValue(){ return 1; }
                    public static class Nested extends Model {}
                    private static class Hidden extends Model {}
                }
                """);
        Files.writeString(source.resolve("Child.java"),"package demo; public class Child extends Model {}");
        Files.writeString(source.resolve("Sibling.java"),"package demo; public class Sibling {}");
        var result=compile(source,"-Apeano.accessorTypes=demo.Model");
        assertEquals(0,result.status(),result.log());
        for(String name:List.of("Model","Child","Model$Nested")) {
            assertTrue(Files.exists(result.generated().resolve("demo/"+name+"__PeanoAccessors.java")));
        }
        for(String name:List.of("Sibling","Model$Hidden")) {
            assertFalse(Files.exists(result.generated().resolve("demo/"+name+"__PeanoAccessors.java")));
        }
    }

    @Test
    void selectsSubtypesEmittedByOtherProcessorsInALaterRound() throws Exception {
        Path source=sources("package demo; public class Model { public int getValue(){ return 1; } }");
        Path generated=compileWithLaterModel(source,"demo.Model",
                "package demo; public class Later extends Model { public int getChild(){ return 7; } }");
        assertTrue(Files.exists(generated.resolve("demo/Later__PeanoAccessors.java")));
    }

    @Test
    void waitsForInheritedMembersFromParentsGeneratedInALaterRound() throws Exception {
        Path source=sources("package demo; public class Model extends Later {}");
        Files.writeString(source.resolve("Main.java"),"""
                package demo;
                public class Main {
                    public static void main(String[] args) {
                        var factory=jakarta.el.ExpressionFactory.newInstance();
                        var context=new jakarta.el.StandardELContext(factory);
                        var table=new Model__PeanoAccessors().accessors().iterator().next();
                        Object value=table.getValue(context,new Model(),"value");
                        if (!context.isPropertyResolved() || !Integer.valueOf(7).equals(value))
                            throw new AssertionError("Inherited property was not generated");
                    }
                }
                """);
        compileWithLaterModel(source,"demo.Model",
                "package demo; public class Later { public int getValue(){ return 7; } }");
        Path core=Path.of(ExpressionFactory.newInstance().getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
        run(List.of(javaBin("java"),"--module-path",String.join(File.pathSeparator,
                el().toString(),api().toString(),core.toString(),temporary.resolve("classes").toString()),
                "--add-modules","io.vidocq.peano.core","--module","peano.propertynegative/demo.Main"),0);
    }

    private Path compileWithLaterModel(Path source,String selected,String model) throws Exception {
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
                        writer.write(model);
                    } catch (java.io.IOException failure) {
                        throw new java.io.UncheckedIOException(failure);
                    }
                }
                return false;
            }
        };
        Path generated = temporary.resolve("generated");
        try (var files = compiler.getStandardFileManager(null,null,null); var paths=Files.list(source)) {
            var task = compiler.getTask(log,files,null,List.of("--module-path",
                    String.join(File.pathSeparator,el().toString(),api().toString()),"-proc:full",
                    "-Apeano.accessorTypes="+selected,"-d",temporary.resolve("classes").toString(),
                    "-s",generated.toString()),null,files.getJavaFileObjectsFromPaths(
                            paths.filter(path->path.toString().endsWith(".java")).toList()));
            task.setProcessors(List.of(new PeanoAccessorProcessor(),producer));
            assertTrue(task.call(),log.toString());
        }
        return generated;
    }

    @Test
    void waitsForFunctionalParentsGeneratedInALaterRound() throws Exception {
        Path source=sources("package demo; @FunctionalInterface public interface Model extends Later {}");
        Files.writeString(source.resolve("Main.java"),"""
                package demo;
                public class Main {
                    public static void main(String[] args) {
                        var factory=jakarta.el.ExpressionFactory.newInstance();
                        var context=new jakarta.el.StandardELContext(factory);
                        Object lambda=factory.createValueExpression(context,"${x->x+1}",Object.class).getValue(context);
                        var table=new Model__PeanoAccessors().accessors().iterator().next();
                        Model callback=table.convertToType(context,lambda,Model.class);
                        if (callback.apply(6)!=7) throw new AssertionError("Inherited callback failed");
                    }
                }
                """);
        compileWithLaterModel(source,"demo.Model","package demo; public interface Later { int apply(int value); }");
        Path core=Path.of(ExpressionFactory.newInstance().getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
        run(List.of(javaBin("java"),"--module-path",String.join(File.pathSeparator,
                el().toString(),api().toString(),core.toString(),temporary.resolve("classes").toString()),
                "--add-modules","io.vidocq.peano.core","--module","peano.propertynegative/demo.Main"),0);
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

    @Test
    void diagnosesInterfacesWithoutTheRequiredFunctionalAnnotation() throws Exception {
        var result=compile(sources("package demo; @io.vidocq.peano.annotation.ELAccessible public interface Model { int apply(int value); }"));
        assertNotEquals(0,result.status());
        assertTrue(result.log().contains("annotated with @FunctionalInterface"),result.log());
    }

    @Test
    void diagnosesSealedFunctionalTargets() throws Exception {
        var result=compile(sources("""
                package demo;
                @io.vidocq.peano.annotation.ELAccessible
                public sealed interface Model permits Child { int apply(int value); }
                final class Child implements Model { public int apply(int value) { return value; } }
                """));
        assertNotEquals(0,result.status());
        assertTrue(result.log().contains("non-sealed"),result.log());
    }

    @Test
    void diagnosesGenericFunctionalMethods() throws Exception {
        var result=compile(sources("""
                package demo;
                @io.vidocq.peano.annotation.ELAccessible @FunctionalInterface
                public interface Model { <T> T apply(T value); }
                """));
        assertNotEquals(0,result.status());
        assertTrue(result.log().contains("Generic functional methods"),result.log());
    }

    @Test
    void generatedJavaLangReferencesDoNotResolveToApplicationModelNames() throws Exception {
        Path source=sources("package demo; public class Model {}");
        for(String name:List.of("Object","Class","String","Float","Double","Boolean","Long","Throwable","SuppressWarnings")) {
            String body="package demo; @io.vidocq.peano.annotation.ELAccessible public class "+name
                    +" { @java.lang.Deprecated public int getValue(){ return 7; } }";
            if(name.equals("Float")) body="""
                    package demo;
                    @io.vidocq.peano.annotation.ELAccessible
                    public class Float {
                        @Tag public int getValue(){ return 7; }
                        @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                        public @interface Tag {
                            float decimal() default 0.0f/0.0f;
                            double precise() default -0.0;
                        }
                    }
                    """;
            Files.writeString(source.resolve(name+".java"),body);
        }
        var result=compile(source);
        assertEquals(0,result.status(),result.log());
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

