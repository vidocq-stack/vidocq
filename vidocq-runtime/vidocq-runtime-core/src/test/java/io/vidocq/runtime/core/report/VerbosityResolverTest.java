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
package io.vidocq.runtime.core.report;

import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.Verbosity;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static io.vidocq.runtime.spi.report.LaunchMode.DEV;
import static io.vidocq.runtime.spi.report.LaunchMode.PROD;
import static io.vidocq.runtime.spi.report.LaunchMode.TEST;
import static io.vidocq.runtime.spi.report.Verbosity.DETAILED;
import static io.vidocq.runtime.spi.report.Verbosity.OFF;
import static io.vidocq.runtime.spi.report.Verbosity.SUMMARY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How much the startup report shows: {@code vidocq.startup.report}, the launch mode, and the boot. */
class VerbosityResolverTest {

    static Stream<Arguments> cases() {
        return Stream.of(
                // auto, unset or written out: the launch mode decides
                Arguments.of("dev, first boot of the JVM", null, DEV, false, true, DETAILED),
                Arguments.of("dev, a reload of the dev loop", null, DEV, false, false, SUMMARY),
                Arguments.of("auto written out, dev, first boot", "auto", DEV, false, true, DETAILED),
                Arguments.of("auto written out, dev, reload", "auto", DEV, false, false, SUMMARY),
                Arguments.of("auto in capitals, dev, first boot", " AUTO ", DEV, false, true, DETAILED),
                Arguments.of("test, first boot", null, TEST, false, true, SUMMARY),
                Arguments.of("test, later boot", null, TEST, false, false, SUMMARY),
                Arguments.of("prod, first boot", null, PROD, false, true, SUMMARY),
                Arguments.of("prod, later boot", "auto", PROD, false, false, SUMMARY),
                Arguments.of("blank is unset", "  ", PROD, false, true, SUMMARY),
                Arguments.of("a mode that could not be read is prod", null, null, false, true, SUMMARY),
                // an embedded deployment (Arquillian, the TCK) boots hundreds of times: auto is off
                Arguments.of("embedded, dev, first boot", null, DEV, true, true, OFF),
                Arguments.of("embedded, test", "auto", TEST, true, true, OFF),
                Arguments.of("embedded, prod", null, PROD, true, false, OFF),
                // an explicit level is honoured whatever the launch, every boot
                Arguments.of("off in dev", "off", DEV, false, true, OFF),
                Arguments.of("summary on the first dev boot", "summary", DEV, false, true, SUMMARY),
                Arguments.of("detailed on a reload", "detailed", DEV, false, false, DETAILED),
                Arguments.of("detailed in prod", "detailed", PROD, false, false, DETAILED),
                Arguments.of("detailed in test", "detailed", TEST, false, true, DETAILED),
                Arguments.of("summary in an embedded deployment", "summary", TEST, true, true, SUMMARY),
                Arguments.of("detailed in an embedded deployment", "detailed", DEV, true, false, DETAILED),
                Arguments.of("case and blanks do not matter", " Detailed ", PROD, false, false, DETAILED),
                // an invalid value reads as auto (the bootstrap reports it as VIDOCQ-CFG-001)
                Arguments.of("a typo, dev, first boot", "detaild", DEV, false, true, DETAILED),
                Arguments.of("a typo, prod", "detaild", PROD, false, true, SUMMARY),
                Arguments.of("a typo, embedded", "sumary", DEV, true, true, OFF));
    }

    @ParameterizedTest(name = "{0} -> {5}")
    @MethodSource("cases")
    void theLevelOfABoot(String description, String configured, LaunchMode mode, boolean embeddedDeployment,
                         boolean firstBoot, Verbosity expected) {
        assertEquals(expected, VerbosityResolver.resolve(configured, mode, embeddedDeployment, firstBoot),
                description);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  ", "auto", "off", "summary", "detailed", " Summary ", "OFF", "\tdetailed\n"})
    void aValueOfTheKeyIsAcceptedSilently(String value) {
        assertTrue(VerbosityResolver.isSetting(value), value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"detaild", "full", "none", "true", "summary,detailed", "de tailed", "dev"})
    void anyOtherValueIsInvalid(String value) {
        assertFalse(VerbosityResolver.isSetting(value), value);
    }
}
