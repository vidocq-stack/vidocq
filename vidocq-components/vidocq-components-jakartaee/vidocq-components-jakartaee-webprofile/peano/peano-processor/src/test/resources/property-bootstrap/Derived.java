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
package demo;

/** Implicitly selected source subtype; it has no selection annotation. */
public class Derived extends Bean {
    @Override public String getName() { return "derived"; }
    public int getOnlyChild() { return 7; }
    @Override @Bean.Tag(value="derived")
    public int add(int a,int b) { return a+b+100; }
    public String choose(Long value) { return "derived-long"; }
    @Override public String label() { return "derived"; }
    public static String getStaticValue() { return "derived-static"; }
}

