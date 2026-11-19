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

package io.vidocq.bodoni.core.testing;

import java.io.IOException;

import jakarta.servlet.jsp.JspWriter;

/** An unbuffered, string-backed {@link JspWriter}; the page's base writer. */
public class TestJspWriter extends JspWriter {

    private final StringBuilder out = new StringBuilder();

    public TestJspWriter() {
        super(NO_BUFFER, false);
    }

    /** Everything written so far. */
    public String getString() {
        return out.toString();
    }

    @Override
    public void write(char[] cbuf, int off, int len) {
        out.append(cbuf, off, len);
    }

    @Override
    public void newLine() {
        out.append('\n');
    }

    @Override
    public void print(boolean b) {
        out.append(b);
    }

    @Override
    public void print(char c) {
        out.append(c);
    }

    @Override
    public void print(int i) {
        out.append(i);
    }

    @Override
    public void print(long l) {
        out.append(l);
    }

    @Override
    public void print(float f) {
        out.append(f);
    }

    @Override
    public void print(double d) {
        out.append(d);
    }

    @Override
    public void print(char[] s) {
        out.append(s);
    }

    @Override
    public void print(String s) {
        out.append(s == null ? "null" : s);
    }

    @Override
    public void print(Object obj) {
        out.append(String.valueOf(obj));
    }

    @Override
    public void println() {
        newLine();
    }

    @Override
    public void println(boolean b) {
        print(b);
        newLine();
    }

    @Override
    public void println(char c) {
        print(c);
        newLine();
    }

    @Override
    public void println(int i) {
        print(i);
        newLine();
    }

    @Override
    public void println(long l) {
        print(l);
        newLine();
    }

    @Override
    public void println(float f) {
        print(f);
        newLine();
    }

    @Override
    public void println(double d) {
        print(d);
        newLine();
    }

    @Override
    public void println(char[] s) {
        print(s);
        newLine();
    }

    @Override
    public void println(String s) {
        print(s);
        newLine();
    }

    @Override
    public void println(Object obj) {
        print(obj);
        newLine();
    }

    @Override
    public void clear() throws IOException {
        out.setLength(0);
    }

    @Override
    public void clearBuffer() throws IOException {
        out.setLength(0);
    }

    @Override
    public void flush() {
        // a test double: nothing to flush or close
    }

    @Override
    public void close() {
        // a test double: nothing to flush or close
    }

    @Override
    public int getRemaining() {
        return 0;
    }
}
