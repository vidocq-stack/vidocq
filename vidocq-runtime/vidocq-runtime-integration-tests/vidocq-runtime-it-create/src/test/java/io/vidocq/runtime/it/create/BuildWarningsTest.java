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
package io.vidocq.runtime.it.create;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BuildWarningsTest {

    private static final String OUTER = "file:///root/.m2/repository/";

    private static final String INTEGRITY = "[WARNING] Could not validate integrity of download from ";

    @Test
    void anIntegrityWarningAboutTheOuterRepositoryIsNotTheCreatedProjects() {
        String output = "[INFO] building\n" + INTEGRITY + OUTER
                + "io/vidocq/vauban/vauban-core/0.4.0-SNAPSHOT/vauban-core-0.4.0-SNAPSHOT.pom\n[INFO] done\n";

        assertEquals(List.of(), BuildWarnings.warningsOf(output, OUTER));
    }

    @Test
    void anIntegrityWarningAboutAnotherRepositoryIsKept() {
        String remote = INTEGRITY + "https://repo.maven.apache.org/maven2/a/b/1/b-1.pom";
        String otherFile = INTEGRITY + "file:///elsewhere/.m2/repository/a/b/1/b-1.pom";

        assertEquals(List.of(remote, otherFile),
                BuildWarnings.warningsOf(remote + "\n" + otherFile + "\n", OUTER));
    }

    @Test
    void aSiblingDirectoryOfTheOuterRepositoryIsAnotherRepository() {
        String sibling = INTEGRITY + "file:///root/.m2/repository-other/a/b/1/b-1.pom";

        assertEquals(List.of(sibling), BuildWarnings.warningsOf(sibling, OUTER));
    }

    @Test
    void aPlainWarningIsKept() {
        assertEquals(List.of("[WARNING] something else"),
                BuildWarnings.warningsOf("[INFO] x\n[WARNING] something else\n", OUTER));
    }

    @Test
    void anInfoLineIsIgnoredEvenIfItMentionsAWarning() {
        String output = "[INFO] [WARNING] not one\n[INFO] " + INTEGRITY + OUTER + "x\n";

        assertEquals(List.of(), BuildWarnings.warningsOf(output, OUTER));
    }

    @Test
    void anOuterUrlWithoutItsTrailingSlashStillDoesNotMatchASiblingDirectory() {
        String outer = "file:///root/.m2/repository";
        String sibling = INTEGRITY + outer + "-other/a/b/1/b-1.pom";
        String own = INTEGRITY + outer + "/a/b/1/b-1.pom";

        assertEquals(List.of(sibling), BuildWarnings.warningsOf(sibling + "\n" + own + "\n", outer));
    }
}
