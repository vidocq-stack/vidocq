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

import io.vidocq.runtime.devservices.host.SecretMasking;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reads what one Surefire run wrote in {@code target/surefire-reports} (spec §2.4): every {@code TEST-*.xml} modified
 * at or after the run's start — with {@link #SLACK} for the file systems that keep whole seconds — counted from its
 * {@code <testcase>} elements, and each failure or error with its class, method, exception type and the first line
 * of its message. A report that cannot be parsed is counted, never thrown.
 */
final class SurefireReports {

    /** The longest message kept, in characters. */
    static final int MAX_MESSAGE = 200;
    /** How much older than the run a report may look, for a file system that stores whole seconds. */
    static final Duration SLACK = Duration.ofSeconds(2);

    /**
     * The reports of a run.
     *
     * @param readable   the fresh reports parsed
     * @param unreadable the fresh reports that could not be read
     */
    record Reports(TestResults.Counts counts, List<TestResults.Failure> failures, int readable, int unreadable) {

        static final Reports NONE = new Reports(TestResults.Counts.NONE, List.of(), 0, 0);

        Reports {
            failures = List.copyOf(failures);
        }
    }

    private SurefireReports() {}

    /** Whether {@code file} is a Surefire XML report: {@code TEST-<class>.xml}. */
    static boolean isReport(Path file) {
        String name = file.getFileName().toString();
        return name.startsWith("TEST-") && name.endsWith(".xml");
    }

    /** The reports of {@code reportsDir} written since {@code since}; {@link Reports#NONE} when there is none. */
    static Reports read(Path reportsDir, Instant since) {
        List<Path> files;
        try (Stream<Path> listing = Files.list(reportsDir)) {
            files = listing.filter(SurefireReports::isReport).sorted().toList();
        } catch (IOException noDirectory) {
            return Reports.NONE;
        }
        Instant cutoff = since.minus(SLACK);
        int run = 0;
        int failed = 0;
        int errors = 0;
        int skipped = 0;
        int readable = 0;
        int unreadable = 0;
        List<TestResults.Failure> failures = new ArrayList<>();
        for (Path file : files) {
            try {
                if (Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) {
                    continue;
                }
                Suite suite = parse(file);
                run += suite.counts().run();
                failed += suite.counts().failures();
                errors += suite.counts().errors();
                skipped += suite.counts().skipped();
                failures.addAll(suite.failures());
                readable++;
            } catch (Exception unreadableReport) {
                unreadable++;
            }
        }
        return new Reports(new TestResults.Counts(run, failed, errors, skipped), failures, readable, unreadable);
    }

    /** One report, counted. */
    private record Suite(TestResults.Counts counts, List<TestResults.Failure> failures) {}

    private static Suite parse(Path file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Element root = factory.newDocumentBuilder().parse(file.toFile()).getDocumentElement();
        NodeList cases = root.getElementsByTagName("testcase");
        int run = 0;
        int failed = 0;
        int errors = 0;
        int skipped = 0;
        List<TestResults.Failure> failures = new ArrayList<>();
        for (int i = 0; i < cases.getLength(); i++) {
            Element testcase = (Element) cases.item(i);
            run++;
            Element failure = child(testcase, "failure");
            Element error = child(testcase, "error");
            if (failure != null || error != null) {
                Element problem = failure != null ? failure : error;
                if (failure != null) {
                    failed++;
                } else {
                    errors++;
                }
                failures.add(new TestResults.Failure(
                        testcase.getAttribute("classname") + "#" + testcase.getAttribute("name"),
                        problem.getAttribute("type"), message(problem)));
            } else if (child(testcase, "skipped") != null) {
                skipped++;
            }
        }
        return new Suite(new TestResults.Counts(run, failed, errors, skipped), failures);
    }

    private static Element child(Element parent, String tag) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && element.getTagName().equals(tag)) {
                return element;
            }
        }
        return null;
    }

    private static String message(Element problem) {
        String message = problem.getAttribute("message");
        return firstLine(message.isEmpty() ? problem.getTextContent() : message);
    }

    /**
     * The first line of {@code text}, without the credentials of a URL ({@link SecretMasking#withoutCredentials}),
     * cut to {@value #MAX_MESSAGE} characters; empty for {@code null}.
     */
    static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        String line = text.strip();
        int end = line.length();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\n' || c == '\r') {
                end = i;
                break;
            }
        }
        String masked = SecretMasking.withoutCredentials(line.substring(0, end).strip());
        return masked.length() <= MAX_MESSAGE ? masked : masked.substring(0, MAX_MESSAGE);
    }
}
