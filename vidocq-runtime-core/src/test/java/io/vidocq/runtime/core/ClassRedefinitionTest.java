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
package io.vidocq.runtime.core;

import io.vidocq.runtime.dev.agent.HotSwap;
import io.vidocq.runtime.dev.agent.RedefinitionRefused;
import io.vidocq.vauban.classloader.VaubanClassLoader;
import io.vidocq.vauban.classloader.spi.PluginContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedMap;
import java.util.SequencedSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassRedefinitionTest {

    private static final String SERVICE = "app.Service";

    @TempDir
    Path dir;

    /** Review Focus 4: a woven bean is redefined with the bytes the loader defines, never the file on disk. */
    @Test
    void theBytesAreTheLoadersDefinitionBytes() throws Exception {
        Path root = wovenBeanArchive();
        byte[] file = Files.readAllBytes(root.resolve("app/Service.class"));
        List<byte[]> handed = new ArrayList<>();
        try (var loader = VaubanClassLoader.of(List.of(root), getClass().getClassLoader(), PluginContext.empty())) {
            var result = ClassRedefinition.apply(request(SERVICE), loader, (l, names, bytes) -> {
                names.forEach(name -> handed.add(bytes.apply(name)));
                return new ClassRedefinition.Done(List.of(), List.copyOf(names));
            });

            assertInstanceOf(ClassRedefinition.Done.class, result);
            assertArrayEquals(loader.definitionBytes(SERVICE).orElseThrow(), handed.getFirst());
            assertFalse(Arrays.equals(file, handed.getFirst()), "the bytes are the file's, not woven");
        }
    }

    @Test
    void aClassWhoseBytesCannotBeReadIsRefusedAndNothingIsRedefined() throws Exception {
        Path root = wovenBeanArchive();
        List<String> asked = new ArrayList<>();
        try (var loader = VaubanClassLoader.of(List.of(root), getClass().getClassLoader(), PluginContext.empty())) {
            Files.delete(root.resolve("app/Service.class"));

            var result = ClassRedefinition.apply(request(SERVICE), loader, (l, names, bytes) -> {
                asked.addAll(names);
                return new ClassRedefinition.Done(List.of(), List.of());
            });

            var refused = assertInstanceOf(ClassRedefinition.Refused.class, result);
            assertTrue(refused.reason().startsWith("cannot read the new bytes of app.Service"), refused.reason());
            assertEquals(List.of(), asked, "the JVM was asked nothing");
        }
    }

    @Test
    void aRefusalNamesTheJvmReason() {
        var agent = new AgentRedefiner((loader, names, bytes) -> {
            throw new RedefinitionRefused("class redefinition failed: attempted to add a method", null);
        });

        var result = agent.redefine(getClass().getClassLoader(), List.of(SERVICE), _ -> new byte[0]);

        assertEquals(new ClassRedefinition.Refused("class redefinition failed: attempted to add a method"), result);
    }

    @Test
    void aRedefinitionNamesTheClassesItRedefinedAndThoseNotLoaded() {
        var agent = new AgentRedefiner((loader, names, bytes) ->
                new HotSwap.Outcome(List.of(String.class), List.of("p.NotLoaded")));

        var result = agent.redefine(getClass().getClassLoader(), List.of("java.lang.String", "p.NotLoaded"),
                _ -> new byte[0]);

        assertEquals(new ClassRedefinition.Done(List.of(String.class), List.of("p.NotLoaded")), result);
    }

    /** The default redefiner asks this JVM's agent, which a JVM started without -javaagent does not have. */
    @Test
    void theDefaultRedefinerAsksTheAgentOfThisJvm() {
        var agent = new AgentRedefiner();
        var loader = getClass().getClassLoader();
        List<String> names = List.of(SERVICE);

        var failure = assertThrows(IllegalStateException.class, () -> agent.redefine(loader, names, _ -> new byte[0]));

        assertTrue(failure.getMessage().contains("-javaagent"), failure.getMessage());
    }

    /** This JVM started without -javaagent: whether the agent module is resolved or not, there is no agent. */
    @Test
    void withoutPremainThereIsNoAgent() {
        assertTrue(ClassRedefinition.agent().isEmpty());
    }

    @Test
    void withoutTheAgentARedefinitionIsRefusedSayingWhy() {
        var result = ClassRedefinition.NO_AGENT.redefine(getClass().getClassLoader(), List.of(SERVICE), _ -> new byte[0]);

        assertEquals(new ClassRedefinition.Refused("the hot swap agent is not installed"), result);
    }

    private static ReloadRequest.Redefine request(String... names) {
        SequencedMap<String, SequencedSet<String>> methods = new LinkedHashMap<>();
        for (String name : names) {
            methods.put(name, new LinkedHashSet<>(List.of("get")));
        }
        return new ReloadRequest.Redefine(methods);
    }

    /**
     * {@code @ApplicationScoped public class Service { public Service(String v) { throw ...; } }}, its client proxy
     * and the bean list: the class the proxifier weaves a {@code (ProxyLink)} constructor into, as Vauban's own test.
     */
    private Path wovenBeanArchive() throws Exception {
        Path root = dir.resolve("app-archive");
        Path app = Files.createDirectories(root.resolve("app"));
        ClassDesc service = ClassDesc.of(SERVICE);
        MethodTypeDesc takesString = MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String);
        Files.write(app.resolve("Service.class"), ClassFile.of().build(service, type -> {
            type.withSuperclass(ConstantDescs.CD_Object);
            type.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            type.with(RuntimeVisibleAnnotationsAttribute.of(java.lang.classfile.Annotation.of(
                    ClassDesc.ofDescriptor("Ljakarta/enterprise/context/ApplicationScoped;"))));
            type.withMethodBody(ConstantDescs.INIT_NAME, takesString, ClassFile.ACC_PUBLIC, code -> {
                ClassDesc failure = ClassDesc.of("java.lang.IllegalStateException");
                code.aload(0).invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
                        .new_(failure).dup()
                        .invokespecial(failure, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
                        .athrow();
            });
        }));
        Files.write(app.resolve("Service_ClientProxy.class"), ClassFile.of().build(ClassDesc.of(SERVICE + "_ClientProxy"),
                type -> type.withSuperclass(service).withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER)
                        .withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC, code -> code
                                .aload(0).aconst_null().invokespecial(service, ConstantDescs.INIT_NAME, takesString)
                                .return_())));
        Path list = Files.createDirectories(root.resolve("META-INF")).resolve("vauban-beans.list");
        Files.writeString(list, SERVICE + "\n");
        return root;
    }
}
