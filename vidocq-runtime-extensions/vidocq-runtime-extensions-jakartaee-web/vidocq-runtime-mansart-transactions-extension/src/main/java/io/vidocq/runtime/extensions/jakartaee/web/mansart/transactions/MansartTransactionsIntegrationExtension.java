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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.transactions;

import io.vidocq.mansart.transactions.core.MansartTransactionManager;
import io.vidocq.mansart.transactions.core.RecoveryReport;
import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.VidocqExtension;
import io.vidocq.runtime.spi.VidocqConfiguration;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.transaction.TransactionManager;

import javax.sql.DataSource;
import javax.sql.XAConnection;
import javax.sql.XADataSource;
import javax.transaction.xa.XAResource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Glue between Vidocq lifecycle and the Mansart Transactions stack.
 *
 * <p>This extension does <b>not</b> register the {@code mansart-transactions-cdi} BCE — Vauban
 * picks it up via the standard CDI 4.1 ServiceLoader contract
 * ({@code META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension}).
 * It does three things:
 *
 * <ol>
 *   <li>{@code configure} — when {@code vidocq.tx.recovery.log} names a journal path, publish it
 *       as the {@code mansart.tx.recovery.log} system property <i>before</i> the CDI container
 *       boots, so {@code MansartTransactionsProducer} builds a durable
 *       ({@code FileRecoveryLog}-backed) transaction manager (MANSART-007 phase 2).</li>
 *   <li>{@code onStart} — fail-fast sanity check: the {@link TransactionManager} bean must be
 *       visible to CDI, otherwise abort with a clear message rather than failing later on the
 *       first {@code @Transactional} call site.</li>
 *   <li>{@code onStart}, durable TM only — boot-time recovery scan: collect the XAResource of
 *       every XA-capable {@code DataSource} bean and run
 *       {@link MansartTransactionManager#recover(XAResource...)} to complete or roll back
 *       in-doubt branches left by a crash, logging the {@link RecoveryReport}.</li>
 * </ol>
 *
 * <p>Priority {@code 250} — runs after the pool extension (200) which publishes the
 * {@code DataSource}, but before mansart-data (300) so the {@code @Transactional} interceptors
 * are guaranteed to be visible by the time repositories start accepting calls.
 */
public final class MansartTransactionsIntegrationExtension implements VidocqExtension {

    private static final System.Logger LOG =
            System.getLogger(MansartTransactionsIntegrationExtension.class.getName());

    /** Vidocq-side key; forwarded to mansart's {@code mansart.tx.recovery.log}. */
    static final String RECOVERY_LOG_KEY = "vidocq.tx.recovery.log";

    @Override
    public String name() {
        return "mansart-transactions";
    }

    @Override
    public int priority() {
        return 250;
    }

    @Override
    public void configure(VidocqConfiguration configuration) {
        configuration.property(RECOVERY_LOG_KEY).ifPresent(path -> {
            System.setProperty("mansart.tx.recovery.log", path);
            LOG.log(System.Logger.Level.INFO,
                    "Mansart Transactions: durable recovery journal at " + path);
        });
    }

    @Override
    public void onStart(ExtensionContext context) {
        BeanManager bm = context.beanManager();
        Set<Bean<?>> tmBeans = bm.getBeans(TransactionManager.class);
        if (tmBeans.isEmpty()) {
            throw new IllegalStateException(
                    "Mansart Transactions extension is enabled but no TransactionManager bean is "
                            + "exposed by CDI. Did mansart-transactions-cdi reach the runtime "
                            + "classpath, and was its BuildCompatibleExtension picked up by the "
                            + "container?");
        }
        LOG.log(System.Logger.Level.INFO, "Mansart Transactions: TransactionManager wired");

        TransactionManager tm = resolve(bm, TransactionManager.class, tmBeans.iterator().next());
        if (tm instanceof MansartTransactionManager mansartTm && mansartTm.durable()) {
            recoverInDoubtBranches(bm, mansartTm);
        }
    }

    /**
     * Boot-time recovery scan (MANSART-007 phase 2): every XA-capable {@code DataSource} bean
     * contributes its driver XAResource; the TM reconciles them with the recovery journal and
     * completes (or rolls back) the in-doubt branches a crash left behind.
     */
    private void recoverInDoubtBranches(BeanManager bm, MansartTransactionManager tm) {
        List<XAConnection> xaConnections = new ArrayList<>();
        try {
            List<XAResource> resources = new ArrayList<>();
            for (Bean<?> bean : bm.getBeans(DataSource.class, Any.Literal.INSTANCE)) {
                DataSource ds = resolve(bm, DataSource.class, bean);
                XADataSource xaDs = xaCapable(ds);
                if (xaDs == null) continue;
                try {
                    XAConnection xaConnection = xaDs.getXAConnection();
                    xaConnections.add(xaConnection);
                    resources.add(xaConnection.getXAResource());
                } catch (SQLException e) {
                    LOG.log(System.Logger.Level.WARNING,
                            "Recovery scan: cannot open an XAConnection on " + bean.getName()
                                    + " — its branches stay in doubt", e);
                }
            }
            RecoveryReport report = tm.recover(resources.toArray(XAResource[]::new));
            if (report.committed().isEmpty() && report.rolledBack().isEmpty()
                    && report.stillInDoubt().isEmpty()) {
                LOG.log(System.Logger.Level.INFO,
                        "Mansart Transactions: recovery scan clean (no in-doubt branch)");
            } else {
                LOG.log(System.Logger.Level.WARNING,
                        "Mansart Transactions: recovery scan — committed=" + report.committed()
                                + " rolledBack=" + report.rolledBack()
                                + " stillInDoubt=" + report.stillInDoubt());
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Transaction recovery scan failed", e);
        } finally {
            for (XAConnection xaConnection : xaConnections) {
                try { xaConnection.close(); } catch (SQLException ignored) { /* best effort */ }
            }
        }
    }

    private static XADataSource xaCapable(DataSource ds) {
        if (ds instanceof XADataSource xa) return xa;
        try {
            if (ds.isWrapperFor(XADataSource.class)) return ds.unwrap(XADataSource.class);
        } catch (SQLException notAWrapper) {
            // JDBC allows isWrapperFor/unwrap to throw — treat as not XA-capable.
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> T resolve(BeanManager bm, Class<T> type, Bean<?> bean) {
        return (T) bm.getReference(bean, type, bm.createCreationalContext(bean));
    }
}
