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
package io.vidocq.peano.beans.compat.internal;
import static org.junit.jupiter.api.Assertions.*;
import java.io.File;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import jakarta.el.ExpressionFactory;
import io.vidocq.peano.spi.TypeConversionProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class CompatibilityModuleTest {
    @TempDir Path temporary;
    @Test void discoversEditorsOnlyWhenInstalledAndRetainsConversionContracts() throws Exception {
        Path sources=temporary.resolve("sources"),classes=temporary.resolve("classes");
        Files.createDirectories(sources);
        for (var name:List.of("module-info.java","Main.java"))
            try(var input=getClass().getResourceAsStream("/editor-app/"+name)){
                assertNotNull(input); Files.copy(input,sources.resolve(name));
            }
        Path el=location(ExpressionFactory.class),api=location(TypeConversionProvider.class);
        Path core=location(ExpressionFactory.newInstance().getClass()),compat=location(PropertyEditorConversions.class);
        String base=String.join(File.pathSeparator,el.toString(),api.toString(),core.toString());
        run(List.of(javaBin("javac"),"--module-path",base,"-d",classes.toString(),
                sources.resolve("module-info.java").toString(),sources.resolve("Main.java").toString()));
        for (var mode:List.of("allow","deny")) for(boolean installed:List.of(false,true)) {
            String path=base+File.pathSeparator+classes+(installed ? File.pathSeparator+compat : "");
            String output=run(List.of(javaBin("java"),"-Dpeano.reflection="+mode,"--module-path",path,
                    "--module","peano.compatconsumer/demo.Main",Boolean.toString(installed)));
            assertTrue(output.contains(installed ? "PEANO_COMPAT_OK" : "PEANO_COMPAT_ABSENT"),output);
        }
    }
    private Path location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    }
    private String javaBin(String tool) { return Path.of(System.getProperty("java.home"),"bin",tool).toString(); }
    private String run(List<String> command) throws Exception {
        Path log=temporary.resolve("process.log");
        var process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        assertTrue(process.waitFor(60,TimeUnit.SECONDS));
        String output=Files.readString(log);
        assertEquals(0,process.exitValue(),output);
        return output;
    }
}

