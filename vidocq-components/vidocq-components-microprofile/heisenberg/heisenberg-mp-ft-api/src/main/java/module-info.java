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
/**
 * Explicit module descriptor for the MicroProfile Fault Tolerance 4.1 spec.
 *
 * <p>The official artifact {@code org.eclipse.microprofile.fault-tolerance:microprofile-fault-tolerance-api}
 * published by the Eclipse Foundation does not provide a {@code module-info.class}; jlink refuses
 * this type of module when composing a runtime image. This module-info turns it into an
 * explicit module, without modifying the spec code, while preserving exactly the same module name
 * ({@code microprofile.fault.tolerance.api}) so that any existing {@code requires} continues
 * to work.
 *
 * <p>The name {@code microprofile.fault.tolerance.api} is the one the JDK had been deriving so far
 * from the file name {@code microprofile-fault-tolerance-api-4.1.jar} using the
 * Java Modules rule "strip version + replace '-' with '.'" — so no migration is required
 * in modules that already declared {@code requires microprofile.fault.tolerance.api}.
 */
module microprofile.fault.tolerance.api {
    exports org.eclipse.microprofile.faulttolerance;
    exports org.eclipse.microprofile.faulttolerance.exceptions;
}
