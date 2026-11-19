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

package io.vidocq.runtime.extensions.jakartaee.core.peano;

import java.util.*;
import java.nio.file.*;
import java.io.File;
import java.util.concurrent.TimeUnit;
import jakarta.el.*;
import io.vidocq.runtime.spi.VidocqExtension;
import io.vidocq.runtime.spi.report.StartupReportSection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PeanoElExtensionTest {
    @TempDir Path temporary;

    @Test void namedApplicationDiscoversTheExtensionInBothReflectionModes() throws Exception {
        Path source=temporary.resolve("sources"),classes=temporary.resolve("classes");
        Files.createDirectories(source); Files.createDirectories(classes);
        Files.writeString(source.resolve("module-info.java"),"""
                module peano.runtimeconsumer {
                    requires io.vidocq.runtime.extensions.jakartaee.core.peano;
                    requires io.vidocq.runtime.spi;
                    uses io.vidocq.runtime.spi.VidocqExtension;
                }
                """);
        Files.writeString(source.resolve("Main.java"),"""
                package consumer;
                import jakarta.el.*;
                import io.vidocq.runtime.spi.VidocqExtension;
                import java.util.ServiceLoader;
                public class Main {
                    public static void main(String[] args) {
                        var extension=ServiceLoader.load(VidocqExtension.class).stream()
                            .map(ServiceLoader.Provider::get).filter(value->value.name().equals("peano-el"))
                            .findFirst().orElseThrow();
                        var factory=ExpressionFactory.newInstance();
                        if (!factory.getClass().getModule().getName().equals("io.vidocq.peano.core")) throw new AssertionError();
                        if (!Long.valueOf(27).equals(new ELProcessor().eval("(7+2)*3"))) throw new AssertionError();
                        if (!Main.class.getModule().isNamed()) throw new AssertionError();
                        extension.onStart(null); extension.onStop();
                        System.out.println("PEANO_RUNTIME_CONSUMER_OK");
                    }
                }
                """);
        String modulePath=ModuleLayer.boot().configuration().modules().stream()
                .map(module->module.reference().location()).flatMap(Optional::stream)
                .filter(uri->uri.getScheme().equals("file")).map(uri->Path.of(uri).toString())
                .collect(java.util.stream.Collectors.joining(File.pathSeparator));
        run(List.of(binary("javac"),"--module-path",modulePath,"-d",classes.toString(),
                source.resolve("module-info.java").toString(),source.resolve("Main.java").toString()));
        for (String mode:List.of("allow","deny")) {
            assertTrue(run(List.of(binary("java"),"-Dpeano.reflection="+mode,"--module-path",
                    modulePath+File.pathSeparator+classes,"--module","peano.runtimeconsumer/consumer.Main"))
                    .contains("PEANO_RUNTIME_CONSUMER_OK"));
        }
    }
    private String binary(String name) { return Path.of(System.getProperty("java.home"),"bin",name).toString(); }
    private String run(List<String> command) throws Exception {
        Path log=temporary.resolve("process.log");
        var process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        assertTrue(process.waitFor(60,TimeUnit.SECONDS));
        String output=Files.readString(log); assertEquals(0,process.exitValue(),output); return output;
    }
    @Test void extensionRegistersItsServiceAndActivatesTheStandardProvider() {
        var extension=new PeanoElExtension();
        assertEquals("peano-el",extension.name());
        var registration=extension.getClass().getModule().getDescriptor().provides().stream()
                .filter(service->service.service().equals(VidocqExtension.class.getName())).findFirst().orElseThrow();
        assertEquals(List.of(PeanoElExtension.class.getName()),registration.providers());
        var factory=ExpressionFactory.newInstance();
        assertEquals("io.vidocq.peano.core",factory.getClass().getModule().getName());
        assertEquals((Object)27L,new ELProcessor().eval("(7+2)*3"));
    }
    @Test void startupReportUsesOnlyLifecycleFactsAndClearsOnStop() {
        var extension=new PeanoElExtension();
        extension.onStart(null);
        var section=new Section(); extension.contribute(null,section);
        assertEquals("el",extension.id());
        assertEquals("Expression Language (Peano)",extension.title());
        assertEquals("Jakarta EL 6.0",section.summary);
        assertEquals("io.vidocq.peano.core",section.rows.get("provider module"));
        extension.onStop();
        section=new Section(); extension.contribute(null,section);
        assertEquals("not initialized",section.rows.get("provider module"));
    }
    private static final class Section implements StartupReportSection {
        String summary;
        final Map<String,Object> rows=new LinkedHashMap<>();
        public StartupReportSection summary(String value) { summary=value; return this; }
        public StartupReportSection row(String key,Object value) { rows.put(key,value); return this; }
        public StartupReportSection list(String key,Collection<String> values) { return this; }
        public StartupReportSection secret(String key,boolean value) { return this; }
        public StartupReportSection listener(String name,String uri) { return this; }
        public StartupReportSection route(String listener,String method,String path,String handler) { return this; }
        public StartupReportSection anomaly(String code,String message,String hint) { throw new AssertionError(message); }
    }
}
