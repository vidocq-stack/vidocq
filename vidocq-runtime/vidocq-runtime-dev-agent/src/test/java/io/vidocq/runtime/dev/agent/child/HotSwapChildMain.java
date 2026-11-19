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
package io.vidocq.runtime.dev.agent.child;

import io.vidocq.runtime.dev.agent.HotSwap;
import io.vidocq.runtime.dev.agent.RedefinitionRefused;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The child JVM of {@code HotSwapProcessTest}: started with the agent on the module path and as {@code -javaagent}, it
 * loads the module {@code greeter} of its first argument in a child layer, as the application layer does, then
 * redefines its classes with the bytes of the other two. Each step prints one line the test reads.
 */
public final class HotSwapChildMain {

    private HotSwapChildMain() {
    }

    public static void main(String[] args) throws Exception {
        Path v1 = Path.of(args[0]);
        Path v2 = Path.of(args[1]);
        Path refused = Path.of(args[2]);
        System.out.println("INSTALLED " + HotSwap.installed() + " IN " + HotSwap.class.getModule().getName());

        ModuleLayer boot = ModuleLayer.boot();
        Configuration configuration = boot.configuration()
                .resolve(ModuleFinder.of(v1), ModuleFinder.of(), Set.of("greeter"));
        ModuleLayer layer = boot.defineModulesWithOneLoader(configuration, ClassLoader.getSystemClassLoader());
        ClassLoader loader = layer.findLoader("greeter");
        @SuppressWarnings("unchecked")
        Supplier<String> greeter = ServiceLoader.load(layer, Supplier.class).stream()
                .filter(provider -> layer.modules().contains(provider.type().getModule()))
                .map(provider -> (Supplier<String>) provider.get())
                .findFirst().orElseThrow();
        System.out.println("BEFORE " + greeter.get());

        try {
            HotSwap.redefine(loader, List.of("p.Greeter", "p.Other"), bytesOf(refused));
            System.out.println("REFUSED none");
        } catch (RedefinitionRefused e) {
            System.out.println("REFUSED " + e.getMessage());
        }
        System.out.println("AFTER-REFUSAL " + greeter.get());

        HotSwap.Outcome outcome = HotSwap.redefine(loader, List.of("p.Greeter", "p.Other", "p.NeverLoaded"),
                bytesOf(v2));
        System.out.println("REDEFINED " + outcome.redefined().stream().map(Class::getName).toList()
                + " NOT-LOADED " + outcome.notLoaded());
        System.out.println("AFTER " + greeter.get());

        Class.forName("p.Unlinkable", false, loader);
        try {
            HotSwap.redefine(loader, List.of("p.Unlinkable"), bytesOf(v1));
            System.out.println("UNLINKABLE redefined");
        } catch (RedefinitionRefused e) {
            System.out.println("UNLINKABLE refused: " + e.getMessage());
        }
    }

    private static Function<String, byte[]> bytesOf(Path root) {
        return name -> {
            try {
                return Files.readAllBytes(root.resolve(name.replace('.', '/') + ".class"));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }
}
