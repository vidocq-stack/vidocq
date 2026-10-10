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
package io.vidocq.heisenberg.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

class BeanClassesTest {

    static class Service {
    }

    static class Outer {
        static class Inner {
        }
    }

    // Shapes of the subclasses CDI containers generate for intercepted beans.
    static class Service$$Intercepted extends Service {
    }

    static class Service$Proxy$_$$_WeldSubclass extends Service {
    }

    static class Service$$OwbInterceptProxy0 extends Service {
    }

    static class Service$$Intercepted$Proxy$_$$_WeldSubclass extends Service$$Intercepted {
    }

    @Test
    void applicationClassIsKept() {
        assertSame(Service.class, BeanClasses.userClass(Service.class));
    }

    @Test
    void nestedApplicationClassIsKept() {
        assertSame(Outer.Inner.class, BeanClasses.userClass(Outer.Inner.class));
    }

    @Test
    void vaubanSubclassIsUnwrapped() {
        assertSame(Service.class, BeanClasses.userClass(Service$$Intercepted.class));
    }

    @Test
    void weldSubclassIsUnwrapped() {
        assertSame(Service.class, BeanClasses.userClass(Service$Proxy$_$$_WeldSubclass.class));
    }

    @Test
    void openWebBeansSubclassIsUnwrapped() {
        assertSame(Service.class, BeanClasses.userClass(Service$$OwbInterceptProxy0.class));
    }

    @Test
    void stackedSubclassesAreUnwrapped() {
        assertSame(Service.class, BeanClasses.userClass(Service$$Intercepted$Proxy$_$$_WeldSubclass.class));
    }
}
