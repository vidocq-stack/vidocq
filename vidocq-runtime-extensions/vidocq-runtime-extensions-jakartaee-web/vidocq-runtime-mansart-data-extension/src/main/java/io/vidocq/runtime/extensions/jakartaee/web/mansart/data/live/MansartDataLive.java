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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * What the Mansart Data extension found, for its {@code -dev} panel only: the catalogue it built and the repository
 * interfaces of the boot. Both are published at the end of {@code onStart} and cleared first thing in
 * {@code onStop}, so that a dev reload never shows the previous boot's catalogue nor lets the panel run the previous
 * boot's classes. The catalogue holds names and texts only; the repository interfaces are the application's classes,
 * which only the panel's actions hold, for one boot.
 */
public final class MansartDataLive {

    private static final AtomicReference<MansartDataCatalogue> CATALOGUE = new AtomicReference<>();
    private static final AtomicReference<List<Class<?>>> REPOSITORIES = new AtomicReference<>(List.of());
    /** Builds the catalogue the first time the panel asks for it, then publishes it; {@code null} without. */
    private static final AtomicReference<Supplier<MansartDataCatalogue>> SOURCE = new AtomicReference<>();

    private MansartDataLive() {}

    /** The catalogue of the running boot, empty before it is built and once the extension stopped. */
    public static Optional<MansartDataCatalogue> catalogue() {
        MansartDataCatalogue built = CATALOGUE.get();
        Supplier<MansartDataCatalogue> source = SOURCE.get();
        return Optional.ofNullable(built != null || source == null ? built : source.get());
    }

    public static void publish(MansartDataCatalogue built) {
        CATALOGUE.set(Objects.requireNonNull(built, "catalogue"));
    }

    /** The {@code @Repository} interfaces of the running boot, by name; empty before and after it. */
    public static List<Class<?>> repositories() {
        return REPOSITORIES.get();
    }

    public static void publishRepositories(List<Class<?>> found) {
        REPOSITORIES.set(List.copyOf(found));
    }

    /**
     * Where to build the catalogue from when it is first asked for: the extension does not read it at startup, it
     * costs reflection over every repository and entity.
     */
    public static void publishSource(Supplier<MansartDataCatalogue> source) {
        SOURCE.set(Objects.requireNonNull(source, "source"));
    }

    /** Forgets the catalogue and the repository interfaces. */
    public static void clear() {
        SOURCE.set(null);
        CATALOGUE.set(null);
        REPOSITORIES.set(List.of());
    }
}
