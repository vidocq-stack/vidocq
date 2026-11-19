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
import java.io.IOException;
import java.lang.classfile.*;
import java.lang.classfile.attribute.*;
import java.lang.constant.*;
import java.util.*;
import javax.lang.model.SourceVersion;

/** Registers Peano APT fragments in application descriptors at process-classes. */
final class PeanoAccessorRegistration {
    private static final String SERVICE="io.vidocq.peano.spi.AccessorProvider";
    private static final String API="io.vidocq.peano.api";
    private static final String PREFIX="provides "+SERVICE+" with ";
    private PeanoAccessorRegistration() {}

    static int apply(Path classes) throws IOException {
        Path fragments=classes.resolve("META-INF/peano/accessor-providers");
        if (!Files.isDirectory(fragments)) return 0;
        var generated=readProviders(classes,fragments);
        if (generated.isEmpty()) return 0;
        Path descriptor=classes.resolve("module-info.class");
        if (!Files.isRegularFile(descriptor)) throw new IOException("Peano accessor providers require a named application module");
        try {
            return register(descriptor,generated);
        } catch (IllegalArgumentException failure) {
            throw new IOException("Cannot register Peano accessor providers in "+descriptor,failure);
        }
    }

    /** The providers the fragments of {@code fragments} name, each checked against the compiled classes. */
    private static TreeSet<String> readProviders(Path classes,Path fragments) throws IOException {
        var generated=new TreeSet<String>();
        try (var files=Files.list(fragments)) {
            for (Path file:files.filter(Files::isRegularFile).filter(p->p.toString().endsWith(".txt")).sorted().toList()) {
                generated.add(providerOf(classes,file));
            }
        }
        return generated;
    }

    /** The provider one fragment names, once its syntax and its compiled class are checked. */
    private static String providerOf(Path classes,Path file) throws IOException {
        String text=Files.readString(file).strip();
        if (!text.startsWith(PREFIX) || !text.endsWith(";"))
            throw new IOException("Invalid Peano accessor fragment "+file);
        String provider=text.substring(PREFIX.length(),text.length()-1).strip();
        if (!SourceVersion.isName(provider,SourceVersion.RELEASE_25))
            throw new IOException("Invalid Peano accessor provider "+provider);
        if (!Files.isRegularFile(classes.resolve(provider.replace('.','/')+".class")))
            throw new IOException("Missing compiled Peano accessor provider "+provider);
        return provider;
    }

    /** Rewrites {@code descriptor} to provide the generated accessors and read the Peano API; the count added. */
    private static int register(Path descriptor,Set<String> generated) throws IOException {
        var cf=ClassFile.of();
        var model=cf.parse(Files.readAllBytes(descriptor));
        var old=model.findAttribute(Attributes.module()).orElseThrow(()->new IllegalArgumentException("Missing Module attribute"));
        var service=ClassDesc.of(SERVICE);
        var providers=new LinkedHashSet<ClassDesc>();
        for (var entry:old.provides())
            if (entry.provides().asSymbol().equals(service))
                for (var provider:entry.providesWith()) providers.add(provider.asSymbol());
        int before=providers.size();
        generated.stream().map(ClassDesc::of).forEach(providers::add);
        boolean readsApi=old.requires().stream().anyMatch(r->r.requires().name().stringValue().equals(API));
        if (providers.size()==before && readsApi) return 0;
        var replacement=ModuleAttribute.of(old.moduleName(),builder->rebuild(builder,old,service,providers,readsApi));
        byte[] rewritten=cf.transformClass(model,ClassTransform.dropping(ModuleAttribute.class::isInstance)
                .andThen(ClassTransform.endHandler(builder->builder.with(replacement))));
        Files.write(descriptor,rewritten);
        return providers.size()-before;
    }

    /** {@code old} with {@code providers} as the only providers of the service, reading the API when it did not. */
    private static void rebuild(ModuleAttribute.ModuleAttributeBuilder builder,ModuleAttribute old,ClassDesc service,
                                Set<ClassDesc> providers,boolean readsApi) {
        builder.moduleFlags(old.moduleFlagsMask());
        old.moduleVersion().ifPresent(version->builder.moduleVersion(version.stringValue()));
        old.requires().forEach(builder::requires);
        if (!readsApi) builder.requires(ModuleRequireInfo.of(ModuleDesc.of(API),0,null));
        old.exports().forEach(builder::exports);
        old.opens().forEach(builder::opens);
        old.uses().forEach(builder::uses);
        for (var entry:old.provides())
            if (!entry.provides().asSymbol().equals(service)) builder.provides(entry);
        builder.provides(ModuleProvideInfo.of(service,new ArrayList<>(providers)));
    }
}
