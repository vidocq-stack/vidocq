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
package io.vidocq.runtime.extensions.jakartaee.web.foy.spi;

import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.servlet.ServletContext;

/**
 * Told once a web application is deployed, before it serves its first request: an extension that completes the
 * application's configuration (the Pages extension registers CDI's bean names in the JSP EL context) does it here.
 * Found with {@link java.util.ServiceLoader} among the runtime extensions.
 */
public interface ServletDeploymentListener {

    /**
     * Called once per deployment, before the first request.
     *
     * @param context     the deployed application's context
     * @param beanManager the CDI container of the application, or {@code null} when it has none
     */
    void deployed(ServletContext context, BeanManager beanManager);
}
