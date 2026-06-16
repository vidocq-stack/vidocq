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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the named datasources an application uses so the Mansart pool codegen can generate, at
 * compile time, a CDI {@code @Named} {@link javax.sql.DataSource} holder for each one.
 *
 * <p>Place it on any type (commonly the application/main class or a {@code package-info}). The
 * values are the datasource names referenced by {@code @Repository(dataStore="...")} and
 * {@code @Inject @Named("...") DataSource}. Connection coordinates are supplied at runtime through
 * {@code vidocq.pool.<name>.url/username/password/...} properties.
 *
 * <p>Why an annotation in addition to properties: following Quarkus, the <em>structure</em> (which
 * datasources exist) is fixed at build time so the beans can be generated AOT, while the
 * <em>values</em> stay fully runtime-dynamic via MicroProfile Config. Names already present in a
 * build-visible {@code vidocq.properties} / {@code application.properties} are picked up
 * automatically and need not be repeated here; names declared only through dynamic config sources
 * (env vars, system properties) are invisible to the build, so list them here to still get a bean.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.MODULE, ElementType.PACKAGE})
public @interface VidocqDataSources {

    /** The datasource names to generate {@code @Named} {@link javax.sql.DataSource} beans for. */
    String[] value();
}
