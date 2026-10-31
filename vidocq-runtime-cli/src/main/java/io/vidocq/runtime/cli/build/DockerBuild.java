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
package io.vidocq.runtime.cli.build;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Whether {@code vidocq build docker} can build the image itself: when the Docker daemon answers, the
 * {@code vidocq:docker} goal runs {@code docker build}; when it does not, the CLI says the image is not built and how
 * to build it, instead of a bare "Done." (BUG-20261030-17).
 */
public final class DockerBuild {

    /** The probe: the daemon answers only when the client reaches it. */
    static final List<String> PROBE = List.of("docker", "info", "--format", "{{.ServerVersion}}");

    private DockerBuild() {
    }

    /** Whether the Docker daemon answers here. */
    public static boolean available() {
        return available(PROBE);
    }

    /** Whether {@code probe} runs and exits with 0 within 20 seconds. */
    static boolean available(List<String> probe) {
        try {
            Process process = new ProcessBuilder(probe).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException _) {
            return false;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** The Maven properties of the build: {@code vidocq:docker} builds the image when Docker is available. */
    public static List<String> properties(boolean dockerAvailable) {
        return dockerAvailable ? List.of("-Dvidocq.docker.build=true") : List.of();
    }

    /** What the CLI says when Docker does not answer: the Dockerfile is written, the image is not built. */
    public static String unavailableMessage() {
        return "Docker does not answer here (docker info failed): the image is not built. The Dockerfile is in "
                + "target/Dockerfile; once Docker runs, build it with: docker build -f target/Dockerfile target/";
    }
}
