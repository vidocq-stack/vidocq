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
package io.vidocq.kerckhoffs.tck.arquillian;

import io.vidocq.foy.internal.boot.ComponentFactory;
import io.vidocq.foy.spi.gen.WebComponentDescriptor;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.servlet.ServletException;

import java.util.Objects;

/**
 * A {@link ComponentFactory} that creates the servlets, filters and listeners of a war through the
 * CDI container of that war, so that their injection points are satisfied, and falls back to the
 * wrapped factory for a class the container holds no bean for.
 */
final class CdiComponentFactory implements ComponentFactory {

    private final ComponentFactory delegate;
    private final BeanManager beanManager;

    CdiComponentFactory(ComponentFactory delegate, BeanManager beanManager) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.beanManager = Objects.requireNonNull(beanManager, "beanManager");
    }

    @Override
    public Class<?> load(String className) throws ClassNotFoundException, ServletException {
        return delegate.load(className);
    }

    @Override
    public <T> T newInstance(Class<T> type) throws ServletException {
        var beans = beanManager.getBeans(type);
        if (beans.isEmpty()) {
            return delegate.newInstance(type);
        }
        Bean<?> bean = beanManager.resolve(beans);
        return type.cast(beanManager.getReference(bean, type, beanManager.createCreationalContext(bean)));
    }

    @Override
    public boolean isVisible(Class<?> type) {
        return delegate.isVisible(type);
    }

    @Override
    public WebComponentDescriptor descriptor(Class<?> type) {
        return delegate.descriptor(type);
    }
}
