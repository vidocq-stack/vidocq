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
package io.vidocq.archrules;

/**
 * The JDK packages a Vidocq module may depend on, for {@link VidocqArchRules#dependOnlyOn}. Each method returns a
 * fresh array, so a caller cannot change what the next one gets.
 */
public final class Jdk {

    private static final String JAVA = "java..";

    /** Every {@code java} and {@code javax} package: for a module that may use the whole JDK. */
    public static String[] any() {
        return new String[] {JAVA, "javax.."};
    }

    /**
     * The JDK packages a runtime module may use: java.base, and java.xml for a parser. A {@code javax} package
     * outside the JDK (the old {@code javax.servlet}, {@code javax.inject}) is not one.
     */
    public static String[] runtime() {
        return new String[] {
            JAVA, "javax.crypto..", "javax.net..", "javax.security..", "javax.xml..", "org.w3c.dom..", "org.xml.sax.."
        };
    }

    /** The JDK packages of an annotation processor: java.base and java.compiler. */
    public static String[] compiler() {
        return new String[] {JAVA, "javax.annotation.processing..", "javax.lang.model..", "javax.tools.."};
    }

    private Jdk() {
        // Factories only.
    }
}
