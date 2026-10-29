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
package io.vidocq.peano.compiler;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Command lines of the child JVMs that run compiled expressions without core or compiler.
 *
 * <p>Under the {@code quality} profile, the coverage agent of the test JVM (handed over by
 * Surefire as {@code peano.test.coverageAgent}) is forwarded to every child {@code java}
 * process, so the {@code peano-api} runtime those programs execute is measured too. Outside
 * that profile the property holds no agent and the command is returned unchanged.
 */
final class ChildJvm {
    private static final String AGENT_PREFIX = "-javaagent:";

    private ChildJvm() {
    }

    static List<String> command(List<String> args) {
        String agent = System.getProperty("peano.test.coverageAgent", "").strip();
        if (!agent.startsWith(AGENT_PREFIX) || !args.getFirst().endsWith(File.separator + "java")) return args;
        var command = new ArrayList<String>(args.size() + 1);
        command.add(args.getFirst());
        command.add(agent);
        command.addAll(args.subList(1, args.size()));
        return command;
    }
}
