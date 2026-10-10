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
    private int calls;
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
    @Deprecated(since="6",forRemoval=false)
    @Tag(value="math",levels={2,3})
    public int add(int a,int b) { calls++; return a+b; }
    public void accept(String value) { calls++; name=value; }
    public int calls() { return calls; }
    public String choose(int value) { return "primitive"; }
    public String choose(Integer value) { return "wrapper"; }
    public String join(String separator,String... values) { return String.join(separator,values); }
    public int sum(int... values) { return java.util.Arrays.stream(values).sum(); }
    public int rows(int[]... values) { return values.length; }
    public CharSequence label() { return "base"; }
    public void broken() throws java.io.IOException { throw new java.io.IOException("method failure"); }
    public String ambiguous(java.io.Serializable value) { return "serializable"; }
    public String ambiguous(CharSequence value) { return "sequence"; }

    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    public @interface Tag {
        String value();
        int[] levels() default {1};
        Class<?> type() default String.class;
        Mode mode() default Mode.FAST;
        Nested nested() default @Nested;
        String[] labels() default {"a","b"};
        float decimal() default 0.0f/0.0f;
        double precise() default -0.0;
    }
    public enum Mode { FAST, SLOW }
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    public @interface Nested { String text() default "nested"; }
}

class Parent {
    private int inherited;
    public int getInherited() { return inherited; }
    public void setInherited(int value) { inherited=value; }
}

