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

import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@code RepositoryWriter} propagates {@code @Transactional} from the
 * {@code @Repository} interface to the generated {@code *RepositoryImpl} class, and that
 * the generated class is no longer {@code final} (required for CDI normal-scope proxy).
 *
 * <p>Two cases are exercised:
 * <ol>
 *   <li>Class-level {@code @Transactional} on the interface → reproduced on {@code TransactionalArticleRepositoryImpl}.</li>
 *   <li>Method-level {@code @Transactional(REQUIRES_NEW)} → reproduced on the corresponding override.</li>
 * </ol>
 *
 * <p>A third case verifies that a plain {@code @Repository} without {@code @Transactional}
 * (here {@code ArticleRepository}) yields a class that has no {@code @Transactional} and is
 * not {@code final}.
 */
class TransactionalPropagationTest {

    // -----------------------------------------------------------------------
    // 1. Generated class is NOT final (enables CDI normal-scope proxy)
    // -----------------------------------------------------------------------

    @Test
    void generatedClassIsNotFinal() throws ClassNotFoundException {
        Class<?> impl = implClass("TransactionalArticleRepositoryImpl");
        assertThat(Modifier.isFinal(impl.getModifiers()))
                .as("TransactionalArticleRepositoryImpl must not be final")
                .isFalse();
    }

    @Test
    void plainRepositoryImplIsNotFinal() throws ClassNotFoundException {
        Class<?> impl = implClass("ArticleRepositoryImpl");
        assertThat(Modifier.isFinal(impl.getModifiers()))
                .as("ArticleRepositoryImpl must not be final")
                .isFalse();
    }

    // -----------------------------------------------------------------------
    // 2. Class-level @Transactional is inherited from interface
    // -----------------------------------------------------------------------

    @Test
    void classLevelTransactionalIsPropagatedToImpl() throws ClassNotFoundException {
        Class<?> impl = implClass("TransactionalArticleRepositoryImpl");
        assertThat(impl.isAnnotationPresent(Transactional.class))
                .as("TransactionalArticleRepositoryImpl must carry @Transactional")
                .isTrue();
    }

    @Test
    void plainRepositoryImplHasNoTransactional() throws ClassNotFoundException {
        Class<?> impl = implClass("ArticleRepositoryImpl");
        assertThat(impl.isAnnotationPresent(Transactional.class))
                .as("ArticleRepositoryImpl must NOT carry @Transactional (interface has none)")
                .isFalse();
    }

    // -----------------------------------------------------------------------
    // 3. Method-level @Transactional(REQUIRES_NEW) is propagated
    // -----------------------------------------------------------------------

    @Test
    void methodLevelTransactionalIsPropagatedToOverride() throws Exception {
        Class<?> impl = implClass("TransactionalArticleRepositoryImpl");
        Method m = impl.getDeclaredMethod("findByTitleSensitive", String.class);
        assertThat(m.isAnnotationPresent(Transactional.class))
                .as("findByTitleSensitive override must carry @Transactional")
                .isTrue();
        Transactional tx = m.getAnnotation(Transactional.class);
        assertThat(tx.value())
                .as("findByTitleSensitive must carry Transactional.TxType.REQUIRES_NEW")
                .isEqualTo(Transactional.TxType.REQUIRES_NEW);
    }

    @Test
    void methodWithoutTransactionalCarriesNoMethodLevelAnnotation() throws Exception {
        Class<?> impl = implClass("TransactionalArticleRepositoryImpl");
        // findByTitle has no method-level @Transactional (covered by class-level)
        Method m = impl.getDeclaredMethod("findByTitle", String.class);
        assertThat(m.isAnnotationPresent(Transactional.class))
                .as("findByTitle override must NOT carry a redundant method-level @Transactional")
                .isFalse();
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private static Class<?> implClass(String simpleName) throws ClassNotFoundException {
        String fqn = "io.vidocq.mansart.data.tests." + simpleName;
        return Class.forName(fqn);
    }
}
