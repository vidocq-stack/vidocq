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

package io.vidocq.runtime.maven;

import java.nio.file.*;
import java.lang.module.ModuleDescriptor;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.*;
import java.lang.constant.*;
import java.util.*;
import java.io.File;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PeanoAccessorRegistrationTest {
    @TempDir Path classes;
    @Test void annotationGeneratedTablesResolveClosedApplicationPropertiesInNamedForks() throws Exception {
        Path source=classes.resolveSibling("sources"); Files.createDirectories(source);
        Files.writeString(source.resolve("module-info.java"),"""
                module consumer.peano {
                    requires io.vidocq.peano.api;
                    requires jakarta.el;
                }
                """);
        Files.writeString(source.resolve("Model.java"),"""
                package example;
                @io.vidocq.peano.annotation.ELAccessible
                public record Model(String name) {}
                """);
        Files.writeString(source.resolve("Main.java"),"""
                package example;
                public class Main {
                    public static void main(String[] args) {
                        var processor=new jakarta.el.ELProcessor();
                        processor.defineBean("model",new Model("direct"));
                        if (!"direct".equals(processor.eval("model.name"))) throw new AssertionError();
                        if (!Main.class.getModule().isNamed()) throw new AssertionError();
                        System.out.println("PEANO_GENERATED_RUNTIME_OK");
                    }
                }
                """);
        String api=location(io.vidocq.peano.annotation.ELAccessible.class)+File.pathSeparator+location(jakarta.el.ExpressionFactory.class);
        String apt=location(Class.forName("io.vidocq.peano.processor.internal.PeanoAccessorProcessor",false,getClass().getClassLoader()));
        run(List.of(binary("javac"),"--module-path",api,"--processor-module-path",apt+File.pathSeparator+api,
                "-d",classes.toString(),source.resolve("module-info.java").toString(),source.resolve("Model.java").toString(),source.resolve("Main.java").toString()));
        String core=location(Class.forName("io.vidocq.peano.core.internal.PeanoExpressionFactory",false,getClass().getClassLoader()));
        Path beforeLog=classes.resolveSibling("before-registration.log");
        var before=new ProcessBuilder(binary("java"),"-Dpeano.reflection=deny","--module-path",
                api+File.pathSeparator+core+File.pathSeparator+classes,"--add-modules","io.vidocq.peano.core",
                "--module","consumer.peano/example.Main").redirectErrorStream(true).redirectOutput(beforeLog.toFile()).start();
        assertTrue(before.waitFor(60,TimeUnit.SECONDS));
        assertNotEquals(0,before.exitValue(),"Unregistered generated table unexpectedly discovered");
        assertTrue(Files.readString(beforeLog).contains("Reflection fallback denied"),Files.readString(beforeLog));
        assertEquals(1,PeanoAccessorRegistration.apply(classes));
        for (String mode:List.of("allow","deny")) assertTrue(run(List.of(binary("java"),"-Dpeano.reflection="+mode,
                "--module-path",api+File.pathSeparator+core+File.pathSeparator+classes,"--add-modules","io.vidocq.peano.core",
                "--module","consumer.peano/example.Main")).contains("PEANO_GENERATED_RUNTIME_OK"));
    }
    private String location(Class<?> type) throws Exception { return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(); }
    private String binary(String name) { return Path.of(System.getProperty("java.home"),"bin",name).toString(); }
    private String run(List<String> command) throws Exception {
        Path log=classes.resolveSibling("process.log");
        var process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        assertTrue(process.waitFor(60,TimeUnit.SECONDS));
        String output=Files.readString(log); assertEquals(0,process.exitValue(),output); return output;
    }
    private void descriptor() throws Exception {
        Files.write(classes.resolve("module-info.class"),ClassFile.of().buildModule(ModuleAttribute.of(ModuleDesc.of("consumer"),b-> {
            b.requires(ModuleRequireInfo.of(ModuleDesc.of("java.base"),ClassFile.ACC_MANDATED,null));
            b.exports(ModuleExportInfo.of(PackageDesc.of("example"),0,List.of()));
            b.uses(ClassDesc.of("java.lang.Runnable"));
            b.provides(ModuleProvideInfo.of(ClassDesc.of("java.lang.Runnable"),List.of(ClassDesc.of("example.Other"))));
        })));
    }
    private void fragment(String provider) throws Exception {
        Path directory=classes.resolve("META-INF/peano/accessor-providers"); Files.createDirectories(directory);
        Files.writeString(directory.resolve(provider+".txt"),"provides io.vidocq.peano.spi.AccessorProvider with "+provider+";\n");
        Path file=classes.resolve(provider.replace('.','/')+".class"); Files.createDirectories(file.getParent());
        Files.write(file,ClassFile.of().build(ClassDesc.of(provider),b->{}));
    }
    @Test void installsGeneratedProvidersWithoutChangingApplicationAccess() throws Exception {
        descriptor(); fragment("example.Generated");
        assertEquals(1,PeanoAccessorRegistration.apply(classes));
        byte[] bytes=Files.readAllBytes(classes.resolve("module-info.class"));
        var module=ModuleDescriptor.read(java.nio.ByteBuffer.wrap(bytes));
        assertEquals(Set.of("example.Generated"),new HashSet<>(module.provides().stream()
                .filter(p->p.service().equals("io.vidocq.peano.spi.AccessorProvider")).findFirst().orElseThrow().providers()));
        assertTrue(module.requires().stream().anyMatch(r->r.name().equals("io.vidocq.peano.api")));
        assertTrue(module.uses().contains("java.lang.Runnable"));
        assertEquals(Set.of("example"),module.exports().stream().map(ModuleDescriptor.Exports::source).collect(java.util.stream.Collectors.toSet()));
        assertTrue(module.opens().isEmpty());
        assertEquals(0,PeanoAccessorRegistration.apply(classes));
        assertArrayEquals(bytes,Files.readAllBytes(classes.resolve("module-info.class")));
        fragment("example.Second"); assertEquals(1,PeanoAccessorRegistration.apply(classes));
    }
    @Test void rejectsFragmentsWithoutCompiledProvidersOrNamedModules() throws Exception {
        descriptor(); fragment("example.Generated");
        Files.delete(classes.resolve("example/Generated.class"));
        assertThrows(java.io.IOException.class,()->PeanoAccessorRegistration.apply(classes));
        fragment("example.Generated"); Files.delete(classes.resolve("module-info.class"));
        assertThrows(java.io.IOException.class,()->PeanoAccessorRegistration.apply(classes));
    }
    @Test void leavesOrdinaryBuildsUntouchedAndRejectsMalformedFragments() throws Exception {
        assertEquals(0,PeanoAccessorRegistration.apply(classes));
        descriptor(); fragment("example.Generated");
        Files.writeString(classes.resolve("META-INF/peano/accessor-providers/example.Generated.txt"),"provides another.Service with example.Generated;");
        assertThrows(java.io.IOException.class,()->PeanoAccessorRegistration.apply(classes));
    }
}
