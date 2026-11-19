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
package io.vidocq.runtime.spi.report;

import java.util.List;
import java.util.Optional;

/**
 * What a {@link StartupReportContributor} may read while it writes its section, provided by Vidocq.
 *
 * <p>Every method is cheap and never throws: what cannot be answered reads as absent.
 */
public interface StartupReportContext {

    /**
     * How much this report shows. Below {@link Verbosity#DETAILED} a contributor can skip computing
     * rows and lists that would not be printed.
     *
     * @return the level of this boot, never {@code null}
     */
    Verbosity verbosity();

    /**
     * How this JVM was launched. The reason Vidocq read it from is already in the report's header:
     * a section never repeats it.
     *
     * @return the launch mode of this boot, never {@code null}
     */
    LaunchMode launchMode();

    /**
     * Whether an enabled bean has a bean type with this binary name, its bean class or one of its
     * interfaces. It compares names, so a class loaded twice by the application layer still
     * matches, and it never creates the bean.
     *
     * @param typeName the binary name of a class or an interface, such as {@code com.acme.Tools}
     * @return {@code true} when such a bean exists; {@code false} otherwise, or when it cannot be told
     */
    boolean hasBeanOfType(String typeName);

    /**
     * The bean of {@code type} with the default qualifier.
     *
     * @param type the bean type
     * @param <T>  the bean type
     * @return the bean; empty when no bean or more than one resolves, or when obtaining it fails
     */
    <T> Optional<T> lookup(Class<T> type);

    /**
     * The absolute URLs of the routes whose handler class has this binary name, as the other
     * sections of the report declare them with {@link StartupReportSection#route} and
     * {@link StartupReportSection#listener}. Only the sections written so far, this one included, are
     * known: the contributors called last see every route. A route whose listener no section declared has no
     * absolute URL and is left out.
     *
     * @param handlerClassName the binary name of a resource or handler class
     * @return the URLs, an empty immutable list when there are none
     */
    List<String> routeUrls(String handlerClassName);
}
