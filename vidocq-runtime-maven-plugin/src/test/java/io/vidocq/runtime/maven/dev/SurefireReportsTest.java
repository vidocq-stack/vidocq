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
package io.vidocq.runtime.maven.dev;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurefireReportsTest {

    private static final String PASSING = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="com.acme.CartTest" time="0.12" tests="2" errors="0" skipped="0" failures="0">
              <properties><property name="java.version" value="25"/></properties>
              <testcase name="addsAnItem" classname="com.acme.CartTest" time="0.01"/>
              <testcase name="removesAnItem" classname="com.acme.CartTest" time="0.01"/>
            </testsuite>
            """;

    private static final String FAILING = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="com.acme.OrderServiceTest" tests="3" errors="1" skipped="1" failures="1">
              <testcase name="rejectsEmptyCart" classname="com.acme.OrderServiceTest" time="0.02">
                <failure message="expected: &lt;400&gt; but was: &lt;200&gt;" \
            type="org.opentest4j.AssertionFailedError"><![CDATA[org.opentest4j.AssertionFailedError: expected: <400>
            	at com.acme.OrderServiceTest.rejectsEmptyCart(OrderServiceTest.java:31)
            ]]></failure>
              </testcase>
              <testcase name="connects" classname="com.acme.OrderServiceTest" time="0.01">
                <error message="refused by jdbc:postgresql://dev:s3cret@localhost:5432/db&#10;second line" \
            type="java.sql.SQLException"><![CDATA[java.sql.SQLException: refused]]></error>
              </testcase>
              <testcase name="later" classname="com.acme.OrderServiceTest" time="0">
                <skipped message="not yet"/>
              </testcase>
            </testsuite>
            """;

    private static final Instant SINCE = Instant.parse("2026-09-25T10:12:03Z");

    private static Path report(Path dir, String name, String xml, Instant modified) throws Exception {
        Files.createDirectories(dir);
        Path file = dir.resolve(name);
        Files.writeString(file, xml);
        Files.setLastModifiedTime(file, FileTime.from(modified));
        return file;
    }

    @Test
    void countsEveryTestcaseOfTheFreshReports(@TempDir Path dir) throws Exception {
        report(dir, "TEST-com.acme.CartTest.xml", PASSING, SINCE.plusSeconds(3));
        report(dir, "TEST-com.acme.OrderServiceTest.xml", FAILING, SINCE.plusSeconds(3));

        SurefireReports.Reports reports = SurefireReports.read(dir, SINCE);

        assertEquals(new TestResults.Counts(5, 1, 1, 1), reports.counts());
        assertEquals(2, reports.readable());
        assertEquals(0, reports.unreadable());
        assertEquals(List.of("com.acme.OrderServiceTest#rejectsEmptyCart", "com.acme.OrderServiceTest#connects"),
                reports.failures().stream().map(TestResults.Failure::test).toList());
        TestResults.Failure failure = reports.failures().getFirst();
        assertEquals("org.opentest4j.AssertionFailedError", failure.type());
        assertEquals("expected: <400> but was: <200>", failure.message());
        assertEquals("OrderServiceTest.java:31", failure.at());
    }

    /**
     * A failure shows where the test failed, the first frame of its own class in the stack (BUG-20261030-08); the
     * frames of JUnit or of the code under test do not name the test's line.
     */
    @Test
    void theLocationIsTheFirstFrameOfTheTestClass() {
        assertEquals("OrderServiceTest.java:31", SurefireReports.location("com.acme.OrderServiceTest", """
                org.opentest4j.AssertionFailedError: expected: <400>
                	at org.junit.jupiter.api.AssertionUtils.fail(AssertionUtils.java:38)
                	at com.acme.OrderService.check(OrderService.java:12)
                	at com.acme.OrderServiceTest.rejectsEmptyCart(OrderServiceTest.java:31)
                	at com.acme.OrderServiceTest.other(OrderServiceTest.java:50)
                """));
        assertEquals("CartTest.java:8", SurefireReports.location("com.acme.CartTest",
                "x\n\tat com.acme.CartTest$Nested.adds(CartTest.java:8)"));
        assertEquals("", SurefireReports.location("com.acme.CartTest", "java.lang.Error: boom"));
        assertEquals("", SurefireReports.location("com.acme.CartTest", null));
    }

    /**
     * BUG-20261106-02: a Vidocq application's tests run in a named module, so the JDK prefixes each frame with the
     * module and its version, and sometimes the class loader ({@link StackTraceElement#toString}); the line was never
     * found. Every shape the JDK prints names the test's line, and the frames of other modules still do not.
     */
    @Test
    void theLocationIsFoundInFramesQualifiedByAModuleOrAClassLoader() {
        assertEquals("TodoResourceTest.java:18", SurefireReports.location("com.acme.todo.todos.TodoResourceTest", """
                org.opentest4j.AssertionFailedError: expected: <201> but was: <200>
                	at org.junit.jupiter.api@5.12.2/org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)
                	at org.junit.jupiter.api@5.12.2/org.junit.jupiter.api.AssertEquals.assertEquals(AssertEquals.java:150)
                	at com.acme.todo@1.0.0-SNAPSHOT/com.acme.todo.todos.TodoResourceTest.createsATodo(TodoResourceTest.java:18)
                	at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:104)
                """), "a module with its version, as Surefire runs a Vidocq application's tests");
        assertEquals("CartTest.java:8", SurefireReports.location("com.acme.CartTest",
                "x\n\tat app//com.acme.CartTest.adds(CartTest.java:8)"), "the application class loader, no module");
        assertEquals("CartTest.java:9", SurefireReports.location("com.acme.CartTest",
                "x\n\tat com.acme.loader/com.acme@2.1+7/com.acme.CartTest.adds(CartTest.java:9)"),
                "a named class loader and a module");
        assertEquals("CartTest.java:10", SurefireReports.location("com.acme.CartTest",
                "x\n\tat com.acme.loader//com.acme.CartTest$Nested.adds(CartTest.java:10)"),
                "a named class loader without a module, in a nested class");
        assertEquals("CartTest.java:11", SurefireReports.location("com.acme.CartTest",
                "x\n\tat com.acme/com.acme.CartTest.lambda$adds$0(CartTest.java:11)"), "a module without a version");
        assertEquals("", SurefireReports.location("com.acme.CartTest",
                "x\n\tat com.acme@1/com.acme.Cart.add(Cart.java:3)\n\tat java.base/java.lang.Thread.run(Thread.java:1583)"),
                "only other classes' frames");
    }

    @Test
    void aMessageKeepsItsFirstLineWithoutCredentials(@TempDir Path dir) throws Exception {
        report(dir, "TEST-com.acme.OrderServiceTest.xml", FAILING, SINCE.plusSeconds(3));

        String message = SurefireReports.read(dir, SINCE).failures().get(1).message();

        assertTrue(message.startsWith("refused by jdbc:postgresql://"), message);
        assertFalse(message.contains("s3cret"), "the password of the URL: " + message);
        assertFalse(message.contains("second line"), message);
    }

    @Test
    void aLongMessageIsCutTo200Characters() {
        assertEquals(SurefireReports.MAX_MESSAGE, SurefireReports.firstLine("x".repeat(500)).length());
        assertEquals("", SurefireReports.firstLine(null));
    }

    @Test
    void aReportOlderThanTheRunIsIgnored(@TempDir Path dir) throws Exception {
        report(dir, "TEST-com.acme.CartTest.xml", PASSING, SINCE.minus(Duration.ofHours(1)));

        SurefireReports.Reports reports = SurefireReports.read(dir, SINCE);

        assertEquals(0, reports.readable());
        assertEquals(TestResults.Counts.NONE, reports.counts());
    }

    @Test
    void aMalformedReportIsCountedUnreadable(@TempDir Path dir) throws Exception {
        report(dir, "TEST-com.acme.CartTest.xml", PASSING, SINCE.plusSeconds(1));
        report(dir, "TEST-com.acme.Broken.xml", "<testsuite><testcase", SINCE.plusSeconds(1));

        SurefireReports.Reports reports = SurefireReports.read(dir, SINCE);

        assertEquals(1, reports.readable());
        assertEquals(1, reports.unreadable());
        assertEquals(2, reports.counts().run());
    }

    @Test
    void noDirectoryMeansNoReport(@TempDir Path dir) {
        assertEquals(SurefireReports.Reports.NONE, SurefireReports.read(dir.resolve("absent"), SINCE));
    }

    @Test
    void onlyTestXmlFilesAreReports() {
        assertTrue(SurefireReports.isReport(Path.of("TEST-a.B.xml")));
        assertFalse(SurefireReports.isReport(Path.of("a.B.txt")));
        assertFalse(SurefireReports.isReport(Path.of("TEST-a.B-jvmRun1.dump")));
    }
}
