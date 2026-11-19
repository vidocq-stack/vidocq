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
package io.vidocq.runtime.cli.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Vidocq/vidocq#353: the CLI says, at most once a day, that a newer build exists. */
class UpdateNoticeTest {

    private static final Instant NOW = Instant.parse("2026-10-25T08:00:00Z");
    private static final UnaryOperator<String> NO_ENV = Map.<String, String>of()::get;
    private static final UpdatePlan.Decision NEWER =
            new UpdatePlan.Update("0.4.1", "https://central/cli.zip", Optional.empty());

    @TempDir
    Path dir;

    private Path stamp() {
        return dir.resolve("cache/update-check");
    }

    @Test
    void aCheckIsDueOnATerminalWhenNoneRanToday() throws IOException {
        assertTrue(UpdateNotice.due(NO_ENV, true, stamp(), NOW), "never checked");

        Files.createDirectories(stamp().getParent());
        Files.writeString(stamp(), NOW.minus(Duration.ofHours(23)).toString());
        assertFalse(UpdateNotice.due(NO_ENV, true, stamp(), NOW), "checked 23 h ago");

        Files.writeString(stamp(), NOW.minus(Duration.ofHours(25)).toString());
        assertTrue(UpdateNotice.due(NO_ENV, true, stamp(), NOW), "checked 25 h ago");

        Files.writeString(stamp(), "not an instant");
        assertTrue(UpdateNotice.due(NO_ENV, true, stamp(), NOW), "an unreadable stamp");
    }

    /** A script or a CI job reads the output: it is not told about updates. */
    @Test
    void noCheckRunsWithoutATerminalOrWhenTurnedOff() {
        assertFalse(UpdateNotice.due(NO_ENV, false, stamp(), NOW));
        assertFalse(UpdateNotice.due(Map.of("VIDOCQ_NO_UPDATE_CHECK", "1")::get, true, stamp(), NOW));
        assertTrue(UpdateNotice.due(Map.of("VIDOCQ_NO_UPDATE_CHECK", " ")::get, true, stamp(), NOW));
    }

    @Test
    void aNewerBuildIsAnnouncedAndTheCheckRecorded() throws IOException {
        String err = printed(UpdateNotice.start(() -> NEWER), Duration.ofSeconds(5));

        assertEquals("A newer Vidocq CLI is available: 0.4.1. Run 'vidocq update' to install it"
                + " (VIDOCQ_NO_UPDATE_CHECK=1 turns this notice off).\n", err);
        assertEquals(NOW.toString(), Files.readString(stamp()));
    }

    @Test
    void aSnapshotBuildIsNamed() {
        var build = new UpdatePlan.Update("0.4.0-SNAPSHOT", "https://snapshots/cli.zip",
                Optional.of("0.4.0-20261025.071500-12"));

        assertEquals(Optional.of("A newer Vidocq CLI is available: 0.4.0-SNAPSHOT (build 0.4.0-20261025.071500-12)."
                + " Run 'vidocq update' to install it (VIDOCQ_NO_UPDATE_CHECK=1 turns this notice off)."),
                UpdateNotice.message(build));
    }

    @Test
    void anUpToDateCliSaysNothingButRecordsTheCheck() throws IOException {
        String err = printed(UpdateNotice.start(() -> new UpdatePlan.UpToDate("0.4.1")), Duration.ofSeconds(5));

        assertEquals("", err);
        assertEquals(NOW.toString(), Files.readString(stamp()));
    }

    /** A repository that does not answer is asked again next time, and the user is not bothered. */
    @Test
    void anUnavailableOrFailingCheckSaysNothingAndIsNotRecorded() {
        assertEquals("", printed(UpdateNotice.start(() -> new UpdatePlan.Unavailable("no release")),
                Duration.ofSeconds(5)));
        assertEquals("", printed(UpdateNotice.start(() -> {
            throw new IllegalStateException("network down");
        }), Duration.ofSeconds(5)));

        assertFalse(Files.exists(stamp()));
    }

    /** The command's end does not wait for a slow repository. */
    @Test
    void aCheckStillRunningIsLeftBehind() {
        CountDownLatch never = new CountDownLatch(1);
        UpdateNotice notice = UpdateNotice.start(() -> {
            try {
                never.await();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            return NEWER;
        });

        assertEquals("", printed(notice, Duration.ofMillis(50)));
        assertFalse(Files.exists(stamp()));
        never.countDown();
    }

    @Test
    void noNoticeSaysNothing() {
        assertEquals("", printed(UpdateNotice.NONE, Duration.ofSeconds(5)));
    }

    private String printed(UpdateNotice notice, Duration wait) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        notice.print(new PrintStream(bytes, true, StandardCharsets.UTF_8), stamp(), NOW, wait);
        return bytes.toString(StandardCharsets.UTF_8).replace(System.lineSeparator(), "\n");
    }
}
