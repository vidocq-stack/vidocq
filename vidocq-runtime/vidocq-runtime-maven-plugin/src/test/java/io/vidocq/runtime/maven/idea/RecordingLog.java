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
package io.vidocq.runtime.maven.idea;

import org.apache.maven.plugin.logging.Log;

import java.util.ArrayList;
import java.util.List;

/** A Maven {@link Log} that keeps every line as {@code "LEVEL message"}, for assertions. */
final class RecordingLog implements Log {

    final List<String> lines = new ArrayList<>();

    boolean has(String level, String message) {
        return lines.contains(level + " " + message);
    }

    boolean hasContaining(String level, String fragment) {
        return lines.stream().anyMatch(line -> line.startsWith(level + " ") && line.contains(fragment));
    }

    @Override
    public String toString() {
        return String.join("\n", lines);
    }

    private void add(String level, CharSequence content, Throwable error) {
        lines.add(level + " " + (content == null ? "" : content) + (error == null ? "" : " " + error));
    }

    @Override
    public boolean isDebugEnabled() {
        return true;
    }

    @Override
    public void debug(CharSequence content) {
        add("DEBUG", content, null);
    }

    @Override
    public void debug(CharSequence content, Throwable error) {
        add("DEBUG", content, error);
    }

    @Override
    public void debug(Throwable error) {
        add("DEBUG", null, error);
    }

    @Override
    public boolean isInfoEnabled() {
        return true;
    }

    @Override
    public void info(CharSequence content) {
        add("INFO", content, null);
    }

    @Override
    public void info(CharSequence content, Throwable error) {
        add("INFO", content, error);
    }

    @Override
    public void info(Throwable error) {
        add("INFO", null, error);
    }

    @Override
    public boolean isWarnEnabled() {
        return true;
    }

    @Override
    public void warn(CharSequence content) {
        add("WARN", content, null);
    }

    @Override
    public void warn(CharSequence content, Throwable error) {
        add("WARN", content, error);
    }

    @Override
    public void warn(Throwable error) {
        add("WARN", null, error);
    }

    @Override
    public boolean isErrorEnabled() {
        return true;
    }

    @Override
    public void error(CharSequence content) {
        add("ERROR", content, null);
    }

    @Override
    public void error(CharSequence content, Throwable error) {
        add("ERROR", content, error);
    }

    @Override
    public void error(Throwable error) {
        add("ERROR", null, error);
    }
}
