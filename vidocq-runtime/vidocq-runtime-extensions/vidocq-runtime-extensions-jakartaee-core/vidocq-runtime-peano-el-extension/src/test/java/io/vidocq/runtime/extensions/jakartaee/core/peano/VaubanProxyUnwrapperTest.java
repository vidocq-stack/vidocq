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
package io.vidocq.runtime.extensions.jakartaee.core.peano;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * The unwrapper names the class a Vauban client proxy stands for, as {@code ClientProxyClass} reads it, when the proxy
 * is an instance of it: the accessor table of that class casts the proxy to it. Every path generates the proxy
 * {@code final}.
 */
class VaubanProxyUnwrapperTest {
    static class Cart { }
    static final class Cart_ClientProxy extends Cart { }
    static class Special extends Cart { }

    /** Named like a proxy, but not final: a class of the application. */
    static class Basket { }
    static class Basket_ClientProxy extends Basket { }

    /** A bean class with a final method; its proxy extends the class above it, and no instance of the bean class. */
    static class Shelf { }
    static class Locked extends Shelf {
        public final String code() {
            return "locked";
        }
    }
    static final class Locked_ClientProxy extends Shelf { }

    private final VaubanProxyUnwrapper unwrapper = new VaubanProxyUnwrapper();

    @Test
    void namesTheBeanClassOfAVaubanClientProxyAndNothingElse() {
        assertEquals(Cart.class, unwrapper.proxiedClass(Cart_ClientProxy.class));
        assertNull(unwrapper.proxiedClass(Special.class), "a plain subclass is not a proxy");
        assertNull(unwrapper.proxiedClass(Cart.class));
        assertNull(unwrapper.proxiedClass(String.class));
    }

    /**
     * BUG-20261118-02: the unwrapper named the superclass of any class named {@code *_ClientProxy}, so it named
     * {@code Shelf} for the proxy of {@code Locked}, which stands for {@code Locked}, and {@code Basket} for a class of
     * the application, which is no proxy.
     */
    @Test
    void namesNothingForAProxyThatIsNoInstanceOfItsBeanClassNorForALookalike() {
        assertNull(unwrapper.proxiedClass(Locked_ClientProxy.class), "the proxy of Locked stands for no Shelf");
        assertNull(unwrapper.proxiedClass(Basket_ClientProxy.class), "a class that is not final is no proxy");
    }
}
