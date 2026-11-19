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
package io.vidocq.runtime.extensions.jakartaee.web.foy.live;

import java.util.function.IntSupplier;

/**
 * A servlet deployment mounted during a boot, as the live panel of the {@code -dev} module reads it.
 *
 * @param name           {@code vidocq.servlet}, or a declarative mount's name
 * @param listener       the Chappe listener it is mounted on
 * @param contextPath    its context path, {@code /} for the root
 * @param activeSessions the sessions alive now, read from memory without blocking
 */
public record ServletMount(String name, String listener, String contextPath, IntSupplier activeSessions) {}
