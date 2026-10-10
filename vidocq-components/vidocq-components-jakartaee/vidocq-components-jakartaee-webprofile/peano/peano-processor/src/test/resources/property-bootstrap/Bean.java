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
import io.vidocq.peano.annotation.ELAccessible;

@ELAccessible
public class Bean extends Parent {
    private String name = "Peano";
    private int count;
    private boolean ready;
    private int reads;
    private String written;
    public String getName() { reads++; return name; }
    public void setName(String value) { name=value; }
    public int getCount() { return count; }
    public void setCount(int value) { count=value; }
    public boolean isReady() { return ready; }
    public boolean getReady() { return false; }
    public void setReady(boolean value) { ready=value; }
    public String getURL() { return "https://vidocq.dev"; }
    public String getNullable() { return null; }
    public String getFailure() throws java.io.IOException { throw new java.io.IOException("failure"); }
    public void setWriteOnly(String value) { written=value; }
    public String written() { return written; }
    public int reads() { return reads; }
    public static String getStaticValue() { return "static"; }
}

class Parent {
    private int inherited;
    public int getInherited() { return inherited; }
    public void setInherited(int value) { inherited=value; }
}

