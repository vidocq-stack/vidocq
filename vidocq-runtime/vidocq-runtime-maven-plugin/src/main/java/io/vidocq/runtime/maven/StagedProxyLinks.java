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

import io.vidocq.vauban.weaver.ProxyLinkWeaver;
import io.vidocq.vauban.weaver.ProxyLinkWeaver.SuperChain;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Gives the staged copy of a library what Vauban's class loader gives it under the application layer: the class of a
 * placed client proxy, and each superclass of its chain the stage holds, carries the synthetic
 * {@code protected (ProxyLink)} constructor, and the proxy chains to it. No constructor of the library runs for the
 * proxy, in the image as in dev and in the distribution (BUG-20261118-91). The rules are those of Vauban's
 * {@code CdiProxifierTransformer}, with the same {@link ProxyLinkWeaver}: the chain ends at {@code Object}, at a
 * superclass that carries the marker, or at one with a non-private no-arg constructor.
 *
 * <p>Where the loader warns, the build fails: a chain that reaches a class outside the stage, a class of the JDK
 * without a no-arg constructor, cannot be free of side effects, and the proxy would run a constructor with made-up
 * arguments in the image alone.
 *
 * <p>A {@link java.io.Serializable} class that declares no {@code serialVersionUID} gets the one the JVM computed for
 * it before the weaving: the woven constructor is not private, and would change the computed one. The weaver does it,
 * {@link ProxyLinkWeaver#addMarkerConstructorKeepingSerialVersionUid}, as Vauban's loader does for a class of a jar
 * (Vauban BUG-20261118-95), so both give the same value.
 */
final class StagedProxyLinks {

    /** The build cannot give a placed proxy a constructor chain free of side effects. */
    static final class Refused extends Exception {
        private static final long serialVersionUID = 1L;

        Refused(String message) {
            super(message);
        }
    }

    private static final String PROXY_SUFFIX = "_ClientProxy";
    private static final String CLASS_SUFFIX = ".class";
    private static final String VERSIONS = "META-INF/versions/";
    private static final String OBJECT = "java.lang.Object";
    private static final List<String> SERIALIZABLE = List.of("java.io.Serializable", "java.io.Externalizable");

    private final List<Path> jars;
    /** The entries of each staged jar. */
    private final Map<Path, List<String>> entries = new HashMap<>();
    /** The rewritten entries of each staged jar, written once every decision is taken. */
    private final Map<Path, Map<String, byte[]>> rewritten = new LinkedHashMap<>();
    /** Whether a class carries the marker once woven; {@code false} while it is being decided (a cycle). */
    private final Map<String, Boolean> decided = new HashMap<>();
    /** Whether a class is {@code Serializable}, by its internal name, through its superclasses and interfaces. */
    private final Map<String, Boolean> serializableByName = new HashMap<>();
    private final List<String> refusals = new ArrayList<>();

    private StagedProxyLinks(List<Path> jars) {
        this.jars = jars;
    }

    /**
     * Weaves the marker into the class of each placed proxy and the superclasses its chain needs, and retargets the
     * proxies onto it.
     *
     * @param jars the staged jars, those of the whole image
     * @param placed the proxies the stage now holds
     * @throws Refused when a placed proxy cannot have a chain free of side effects; nothing is rewritten then
     */
    static void weave(List<Path> jars, List<StagedProxies.Placed> placed) throws IOException, Refused {
        StagedProxyLinks links = new StagedProxyLinks(jars);
        for (StagedProxies.Placed proxy : placed) {
            if (links.ensureMarker(proxy.type())) {
                links.retarget(proxy.type());
            }
        }
        if (!links.refusals.isEmpty()) {
            throw new Refused(refusalMessage(links.refusals));
        }
        links.write();
    }

    private static String refusalMessage(List<String> refusals) {
        return "Vidocq jlink — a client proxy of the image would run a constructor of its library with made-up"
                + " arguments, which the application layer never does (BUG-20261118-91):\n  "
                + String.join("\n  ", refusals)
                + "\nProduce an interface type instead of the class, or give the producer the scope @Singleton or"
                + " @Dependent, which need no client proxy.";
    }

    /** Whether {@code name} carries the marker once woven; weaves it, and its chain, when the stage holds them. */
    private boolean ensureMarker(String name) throws IOException {
        Boolean known = decided.get(name);
        if (known != null) {
            return known;
        }
        decided.put(name, false);
        Path jar = holder(rootEntry(name));
        if (jar == null) {
            return false;
        }
        byte[] bytes = read(jar, rootEntry(name));
        if (ProxyLinkWeaver.hasMarkerConstructor(bytes)) {
            decided.put(name, true);
            return true;
        }
        SuperChain chain = chain(name, bytes);
        if (chain == null) {
            return false;
        }
        boolean pin = isSerializable(name);
        for (String entry : entriesOf(jar, rootEntry(name))) {
            byte[] original = read(jar, entry);
            byte[] woven = pin ? ProxyLinkWeaver.addMarkerConstructorKeepingSerialVersionUid(original, chain)
                    : ProxyLinkWeaver.addMarkerConstructor(original, chain);
            if (woven != null) {
                rewrite(jar, entry, woven);
            }
        }
        decided.put(name, true);
        return true;
    }

    /** How the marker of {@code name} chains to its superclass; {@code null}, with a refusal, when it cannot. */
    private SuperChain chain(String name, byte[] bytes) throws IOException {
        String superName = superName(bytes);
        if (superName == null || OBJECT.equals(superName)) {
            return SuperChain.NO_ARG;
        }
        Path jar = holder(rootEntry(superName));
        byte[] superBytes = jar != null ? read(jar, rootEntry(superName)) : jdkClass(superName);
        if (superBytes != null && ProxyLinkWeaver.hasMarkerConstructor(superBytes)) {
            return SuperChain.MARKER;
        }
        if (superBytes != null && ProxyLinkWeaver.hasNonPrivateNoArgConstructor(superBytes)) {
            return SuperChain.NO_ARG;
        }
        if (jar != null && ensureMarker(superName)) {
            return SuperChain.MARKER;
        }
        if (jar == null) {
            refusals.add(name + ": its superclass " + superName + " is not in a jar of the image, and offers no"
                    + " no-arg constructor");
        }
        return null;
    }

    private void retarget(String type) throws IOException {
        String proxy = rootEntry(type + PROXY_SUFFIX);
        Path jar = holder(proxy);
        if (jar == null) {
            return;
        }
        for (String entry : entriesOf(jar, proxy)) {
            rewrite(jar, entry, ProxyLinkWeaver.retargetProxyConstructor(read(jar, entry)));
        }
    }

    /** Whether {@code name} implements {@code Serializable}, through its superclasses and interfaces. */
    private boolean isSerializable(String name) throws IOException {
        if (SERIALIZABLE.contains(name)) {
            return true;
        }
        Boolean known = serializableByName.get(name);
        if (known != null) {
            return known;
        }
        serializableByName.put(name, false);
        Path jar = holder(rootEntry(name));
        byte[] bytes = jar != null ? read(jar, rootEntry(name)) : jdkClass(name);
        boolean result = false;
        if (bytes != null) {
            ClassModel model = ClassFile.of().parse(bytes);
            List<String> supers = new ArrayList<>();
            model.superclass().ifPresent(entry -> supers.add(binaryName(entry)));
            model.interfaces().forEach(entry -> supers.add(binaryName(entry)));
            for (String parent : supers) {
                if (isSerializable(parent)) {
                    result = true;
                    break;
                }
            }
        }
        serializableByName.put(name, result);
        return result;
    }

    /** The bytes of a class of the JDK; {@code null} when the JDK has no such class. */
    private static byte[] jdkClass(String name) throws IOException {
        try (InputStream in = ClassLoader.getPlatformClassLoader().getResourceAsStream(rootEntry(name))) {
            return in == null ? null : in.readAllBytes();
        }
    }

    private static String superName(byte[] bytes) {
        return ClassFile.of().parse(bytes).superclass().map(StagedProxyLinks::binaryName).orElse(null);
    }

    private static String binaryName(ClassEntry entry) {
        return entry.asInternalName().replace('/', '.');
    }

    private static String rootEntry(String name) {
        return name.replace('.', '/') + CLASS_SUFFIX;
    }

    /** The staged jar that holds {@code entry}; {@code null} when none does. */
    private Path holder(String entry) throws IOException {
        for (Path jar : jars) {
            if (entries(jar).contains(entry)) {
                return jar;
            }
        }
        return null;
    }

    /** {@code root} and the versions of it a multi-release {@code jar} holds. */
    private List<String> entriesOf(Path jar, String root) throws IOException {
        List<String> found = new ArrayList<>();
        for (String entry : entries(jar)) {
            if (entry.equals(root) || (entry.startsWith(VERSIONS) && entry.endsWith("/" + root)
                    && entry.indexOf('/', VERSIONS.length()) == entry.length() - root.length() - 1)) {
                found.add(entry);
            }
        }
        return found;
    }

    private List<String> entries(Path jar) throws IOException {
        List<String> known = entries.get(jar);
        if (known == null) {
            try (JarFile archive = new JarFile(jar.toFile())) {
                known = archive.stream().map(JarEntry::getName).toList();
            }
            entries.put(jar, known);
        }
        return known;
    }

    /** The bytes of {@code entry}, as rewritten when it was. */
    private byte[] read(Path jar, String entry) throws IOException {
        byte[] bytes = rewritten.getOrDefault(jar, Map.of()).get(entry);
        if (bytes != null) {
            return bytes;
        }
        try (JarFile archive = new JarFile(jar.toFile()); InputStream in = archive.getInputStream(
                archive.getEntry(entry))) {
            return in.readAllBytes();
        }
    }

    private void rewrite(Path jar, String entry, byte[] bytes) {
        rewritten.computeIfAbsent(jar, _ -> new LinkedHashMap<>()).put(entry, bytes);
    }

    private void write() throws IOException {
        for (Map.Entry<Path, Map<String, byte[]>> jar : rewritten.entrySet()) {
            try (FileSystem archive = FileSystems.newFileSystem(jar.getKey())) {
                for (Map.Entry<String, byte[]> entry : jar.getValue().entrySet()) {
                    Files.write(archive.getPath(entry.getKey()), entry.getValue());
                }
            }
        }
    }
}
