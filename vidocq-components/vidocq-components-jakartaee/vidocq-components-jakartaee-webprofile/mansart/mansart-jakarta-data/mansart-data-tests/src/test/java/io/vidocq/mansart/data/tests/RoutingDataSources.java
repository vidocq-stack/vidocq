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
package io.vidocq.mansart.data.tests;

import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import jakarta.inject.Qualifier;
import jakarta.inject.Singleton;
import org.h2.jdbcx.JdbcDataSource;

import javax.sql.DataSource;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE;

/**
 * Two distinct H2 in-memory databases for the multi-dataStore routing test: the {@code @Default}
 * one and a {@code @Named("auditds")} one. The named producer carries an extra {@link RoutedStore}
 * qualifier so it does NOT also get the implicit {@code @Default} (a bean whose only qualifier is
 * {@code @Named} keeps {@code @Default} per CDI 4.1 §2.6.1, which would make the default
 * {@code DataSource} ambiguous — same reason the Vidocq pool codegen marks its generated holders
 * with a dedicated marker qualifier).
 */
@Singleton
public class RoutingDataSources {

    public static final String DEFAULT_URL = "jdbc:h2:mem:mansart-routing-default;DB_CLOSE_DELAY=-1";
    public static final String AUDIT_URL   = "jdbc:h2:mem:mansart-routing-audit;DB_CLOSE_DELAY=-1";

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({TYPE, METHOD, FIELD, PARAMETER})
    public @interface RoutedStore {
    }

    @Produces
    @Singleton
    public DataSource defaultDataSource() {
        return h2(DEFAULT_URL);
    }

    @Produces
    @Singleton
    @RoutedStore
    @Named("auditds")
    public DataSource auditDataSource() {
        return h2(AUDIT_URL);
    }

    static DataSource h2(String url) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL(url);
        ds.setUser("sa");
        return ds;
    }
}
