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
package io.vidocq.mansart.transactions.cdi;

import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.transaction.Status;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.TransactionScoped;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The {@code AlterableContext} contract of {@link TransactionScopedContext}, driven without a container. */
class TransactionScopedContextContractTest {

    private final TransactionManager tm = MansartTransactionsProducer.tm();
    private final TransactionScopedContext context = new TransactionScopedContext();
    private final List<String> destroyed = new ArrayList<>();

    /** A bean that creates a fresh {@code StringBuilder} named after it, and records its destruction. */
    private final class Bean implements Contextual<StringBuilder> {
        private final String name;
        private final boolean failsOnDestroy;

        Bean(String name, boolean failsOnDestroy) {
            this.name = name;
            this.failsOnDestroy = failsOnDestroy;
        }

        @Override
        public StringBuilder create(CreationalContext<StringBuilder> creationalContext) {
            return new StringBuilder(name);
        }

        @Override
        public void destroy(StringBuilder instance, CreationalContext<StringBuilder> creationalContext) {
            destroyed.add(instance.toString());
            if (failsOnDestroy) throw new IllegalStateException("destroy failed");
        }
    }

    private static final CreationalContext<StringBuilder> CREATION = new CreationalContext<>() {
        @Override
        public void push(StringBuilder incompleteInstance) {
            // nothing to track
        }

        @Override
        public void release() {
            // nothing to release
        }
    };

    @AfterEach
    void endTransaction() throws Exception {
        if (tm.getStatus() != Status.STATUS_NO_TRANSACTION) tm.rollback();
    }

    @Test
    void isInactiveOutsideATransaction() {
        Bean bean = new Bean("a", false);

        assertThat(context.getScope()).isEqualTo(TransactionScoped.class);
        assertThat(context.isActive()).isFalse();
        assertThatThrownBy(() -> context.get(bean)).isInstanceOf(ContextNotActiveException.class);
    }

    @Test
    void keepsOneInstancePerBeanForTheTransaction() throws Exception {
        Bean bean = new Bean("a", false);
        Bean other = new Bean("b", false);
        tm.begin();

        assertThat(context.isActive()).isTrue();
        assertThat(context.get(bean)).isNull();
        StringBuilder instance = context.get(bean, CREATION);
        assertThat(context.get(bean, CREATION)).isSameAs(instance);
        assertThat(context.get(bean)).isSameAs(instance);
        assertThat(context.get(other)).isNull();
        assertThat(context.get(other, null)).isNull();

        context.destroy(bean);
        context.destroy(bean);
        assertThat(destroyed).containsExactly("a");
        assertThat(context.get(bean)).isNull();
    }

    @Test
    void destroysEveryInstanceWhenTheTransactionEnds() throws Exception {
        tm.begin();
        context.get(new Bean("x", true), CREATION);
        context.get(new Bean("y", false), CREATION);
        tm.commit();

        assertThat(destroyed).containsExactlyInAnyOrder("x", "y");

        tm.begin();
        context.destroy(new Bean("z", false));
        assertThat(destroyed).hasSize(2);
    }
}
