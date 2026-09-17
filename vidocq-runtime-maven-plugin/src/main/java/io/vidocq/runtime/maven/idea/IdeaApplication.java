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

/**
 * A Vidocq application of the reactor, as one IntelliJ IDEA run configuration describes it.
 *
 * @param coordinates       {@code groupId:artifactId} of its Maven project, for messages
 * @param mainClass         the application main class as the pom declares it (a binary name: a nested
 *                          class uses {@code $})
 * @param configurationName the run configuration name, which also gives the file name
 * @param moduleName        the IntelliJ module that holds the main class
 * @param pomPath           its pom, relative to the project directory, with {@code /} separators
 * @param generateGoal      the Maven goal of the before-launch step: {@code vidocq:generate}, or
 *                          {@code vidocq:generate@<execution>} when one execution carries the
 *                          configuration of that goal
 */
record IdeaApplication(String coordinates, String mainClass, String configurationName, String moduleName,
                       String pomPath, String generateGoal) {
}
