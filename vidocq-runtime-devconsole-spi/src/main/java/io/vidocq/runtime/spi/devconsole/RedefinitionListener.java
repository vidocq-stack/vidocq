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
package io.vidocq.runtime.spi.devconsole;

/**
 * Told after each redefinition of {@code vidocq:dev} that changed at least one loaded class: the JVM redefined method
 * bodies in place, and the application keeps running (hot swap). Never after a layer swap, nor after a redefinition
 * the JVM refused.
 *
 * <p><b>Who provides one.</b> A {@code -dev} companion only ({@code provides RedefinitionListener with ...}), the
 * modules of the live panels: no binary contains them, so no packaged application has a listener. The runtime loads
 * the listeners at each redefinition, from the current application layer and its parents, so a companion that rides
 * in the application layer with its framework is found too, and a listener of a dropped layer never is.
 *
 * <p><b>When.</b> After the application acknowledged the redefinition, on one virtual thread: the listeners one after
 * the other, one redefinition after the other, in order. A request can run the redefined code before the listener is
 * called.
 *
 * <p><b>What a listener may do</b>: read the redefined classes and the names of their changed methods; act on the
 * running application through its framework's own API, such as Vaadin's {@code UI.access}; log.
 *
 * <p><b>What it may not do</b>:
 * <ul>
 *   <li>change a class or redefine anything: it gets no {@code Instrumentation}, and only method bodies changed;</li>
 *   <li>block: a slow listener delays the listeners after it, never the reload loop nor the next acknowledgement,
 *       but it must still return quickly ({@code UI.access} queues, it does not wait);</li>
 *   <li>keep state across a layer swap: an instance is created for each redefinition, and state it needs, such as the
 *       open pages, lives in its module, which a swap drops with the layer.</li>
 * </ul>
 * An exception it throws is logged, {@code Hot reload - the redefinition listener <class> failed: <exception>}, and
 * the next listener still runs.
 */
@FunctionalInterface
public interface RedefinitionListener {

    /** Called once for each redefinition that changed at least one loaded class. */
    void redefined(Redefinition redefinition);
}
