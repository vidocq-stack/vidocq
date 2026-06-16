package io.vidocq.runtime.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliParserDoctorTest {

    @Test
    void bareDoctorIsNonVerbose() {
        Command cmd = CliParser.parse(new String[]{"doctor"});

        Command.Doctor doctor = assertInstanceOf(Command.Doctor.class, cmd);
        assertFalse(doctor.verbose());
    }

    @Test
    void longVerboseFlagIsParsed() {
        Command.Doctor doctor = (Command.Doctor) CliParser.parse(new String[]{"doctor", "--verbose"});
        assertTrue(doctor.verbose());
    }

    @Test
    void shortVerboseFlagIsParsed() {
        Command.Doctor doctor = (Command.Doctor) CliParser.parse(new String[]{"doctor", "-v"});
        assertTrue(doctor.verbose());
    }

    @Test
    void unknownDoctorOptionIsRejected() {
        CliException ex = assertThrows(CliException.class,
                () -> CliParser.parse(new String[]{"doctor", "--bogus"}));
        assertTrue(ex.getMessage().contains("doctor"));
    }

    @Test
    void doctorDefaultsMatchParserDefault() {
        assertEquals(Command.Doctor.defaults(), CliParser.parse(new String[]{"doctor"}));
    }
}
