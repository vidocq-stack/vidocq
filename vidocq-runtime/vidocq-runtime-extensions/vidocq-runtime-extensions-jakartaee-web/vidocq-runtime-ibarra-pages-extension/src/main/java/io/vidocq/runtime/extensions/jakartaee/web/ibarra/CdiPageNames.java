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
package io.vidocq.runtime.extensions.jakartaee.web.ibarra;

import io.vidocq.runtime.extensions.jakartaee.web.foy.spi.ServletDeploymentListener;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.servlet.ServletContext;
import jakarta.servlet.jsp.JspFactory;

/**
 * Registers CDI's bean names in the EL context of the application's pages, once it is deployed and before its first
 * request ({@code JspApplicationContext.addELResolver}): a page reads {@code ${cart}} from the {@code @Named} bean
 * {@code cart}. Nothing is registered without a CDI container or without a JSP engine.
 */
public final class CdiPageNames implements ServletDeploymentListener {

    /** Created by the runtime's service loader. */
    public CdiPageNames() {
        // Stateless.
    }

    @Override
    public void deployed(ServletContext context, BeanManager beanManager) {
        if (beanManager != null) register(JspFactory.getDefaultFactory(), context, CdiBeanNameELResolver.of(beanManager));
    }

    /** Adds the bean names to the pages' EL context of {@code context}; nothing without a JSP engine. */
    static void register(JspFactory factory, ServletContext context, CdiBeanNameELResolver.BeanNames names) {
        if (factory != null) factory.getJspApplicationContext(context).addELResolver(new CdiBeanNameELResolver(names));
    }
}
