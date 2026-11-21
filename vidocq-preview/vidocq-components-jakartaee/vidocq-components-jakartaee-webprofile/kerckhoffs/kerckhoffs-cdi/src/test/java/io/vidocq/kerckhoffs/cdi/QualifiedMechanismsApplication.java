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
package io.vidocq.kerckhoffs.cdi;

import jakarta.enterprise.context.Dependent;
import jakarta.security.enterprise.authentication.mechanism.http.BasicAuthenticationMechanismDefinition;

/**
 * An application that declares two BASIC mechanisms (the definition is repeatable): the first keeps the default
 * qualifier {@code @BasicAuthenticationMechanism}, the second one is told apart by its own.
 */
@BasicAuthenticationMechanismDefinition(realmName = "first realm")
@BasicAuthenticationMechanismDefinition(realmName = "second realm", qualifiers = SecondRealm.class)
@Dependent
public class QualifiedMechanismsApplication {
}
