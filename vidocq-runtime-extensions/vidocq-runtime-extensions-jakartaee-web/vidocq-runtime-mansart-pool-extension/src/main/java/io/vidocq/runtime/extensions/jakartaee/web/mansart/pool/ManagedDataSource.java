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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.pool;

import jakarta.inject.Qualifier;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Marker qualifier carried by every compile-time generated {@code @Named} {@link javax.sql.DataSource}
 * holder, so a <b>named</b> datasource does not silently become a {@code @Default} candidate.
 *
 * <p>CDI assumes {@code @Default} for a bean whose only qualifier is {@code @Named} (CDI 4.1
 * §2.5.2). Without this marker, every {@code @Named("X")} holder would also answer an unqualified
 * {@code @Inject DataSource}, making it <em>ambiguous</em> with the {@code @Default} pool. Adding a
 * second, non-{@code @Named} qualifier suppresses that assumption, so:
 * <ul>
 *   <li>{@code @Inject DataSource} (unqualified) resolves to the {@code @Default} pool <b>alone</b>;</li>
 *   <li>{@code @Inject @Named("X") DataSource} still selects the named holder (it carries
 *       {@code @Named("X")}), and {@code @Repository(dataStore = "X")} routes to it the same way.</li>
 * </ul>
 *
 * <p>Applied automatically by the codegen — applications inject by {@code @Named}, not by this
 * marker (though {@code @Inject @ManagedDataSource @Named("X") DataSource} is equally valid).
 */
@Qualifier
@Retention(RUNTIME)
@Target({TYPE, FIELD, METHOD, PARAMETER})
public @interface ManagedDataSource {
}
