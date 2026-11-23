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
package io.vidocq.runtime.extensions.jakartaee.web.kerckhoffs;

import io.vidocq.runtime.spi.VidocqExtension;

/**
 * Jakarta Security 4.0 on the Vidocq runtime, with Kerckhoffs.
 *
 * <p>The build compatible extensions of {@code kerckhoffs-cdi} (identity store handler, stores and mechanisms declared
 * by annotation, the built-in {@code Pbkdf2PasswordHash}) and of {@code kerckhoffs-foy} (the {@code SecurityContext} and {@code Principal} beans) are found by
 * Vauban, and frozen by the build with the others. Two beans of this module, which Vauban compiles ahead of time,
 * carry the rest of the runtime: {@link VidocqIdentityStoreStartCheck} checks the identity stores on {@code Startup},
 * and {@link VidocqAutoApplySessionInterceptor} weaves {@code @AutoApplySession}.
 * {@link KerckhoffsServletAuthentication} hands Foy the authentication of every request.
 *
 * <p>Priority 580: before the Servlet extension (600), whose deployment asks for the hook.
 */
public final class KerckhoffsSecurityExtension implements VidocqExtension {

    @Override
    public String name() {
        return "kerckhoffs-security";
    }

    @Override
    public int priority() {
        return 580;
    }

}
