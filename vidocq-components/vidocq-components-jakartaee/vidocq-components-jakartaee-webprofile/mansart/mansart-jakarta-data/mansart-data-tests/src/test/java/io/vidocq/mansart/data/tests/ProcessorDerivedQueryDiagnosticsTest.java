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
package io.vidocq.mansart.data.tests;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A repository method the processor derives no query from fails the build instead of throwing at run time
 * (MANSART-030), and the {@code Null} keyword of Jakarta Data 1.0 is one it derives (MANSART-029).
 */
class ProcessorDerivedQueryDiagnosticsTest {

    private static final String LOAN = "demo.Loan";
    private static final String LOANS = "demo.Loans";
    private static final String LOAN_SOURCE = """
            package demo;

            import jakarta.persistence.Column;
            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            import java.time.LocalDate;

            @Entity
            public class Loan {
                @Id @Column(name = "id") public Long id;
                @Column(name = "member") public String member;
                @Column(name = "returned_on") public LocalDate returnedOn;
            }
            """;

    @TempDir
    Path out;

    private static String repository(String methods) {
        return """
                package demo;

                import jakarta.data.repository.BasicRepository;
                import jakarta.data.repository.Repository;
                import java.util.List;

                @Repository
                public interface Loans extends BasicRepository<Loan, Long> {
                %s
                }
                """.formatted(methods);
    }

    private static final String UNRESOLVED = "Mansart could not derive a query for 'demo.Loans.findByMembr'; the"
            + " generated method would throw UnsupportedOperationException at runtime. Check the method name and"
            + " attribute spelling, or write a @Query.";

    @Test
    void theNullKeywordDerivesAQuery() throws IOException {
        var result = InProcessCompilation.compileSources(out, LOAN, LOAN_SOURCE, LOANS, repository("""
                    List<Loan> findByMemberAndReturnedOnNull(String member);
                    List<Loan> findByReturnedOnNotNull();
                """));

        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).isEmpty();
        assertThat(result.generatedSource("demo.LoansImpl")).doesNotContain("UnsupportedOperationException");
    }

    @Test
    void aMethodNoQueryDerivesFromFailsTheBuild() throws IOException {
        var result = InProcessCompilation.compileSources(out, LOAN, LOAN_SOURCE, LOANS,
                repository("    List<Loan> findByMembr(String member);"));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).containsExactly(UNRESOLVED);
    }

    @Test
    void theWarnOptionKeepsTheRuntimeStub() throws IOException {
        var result = InProcessCompilation.compileSources(out, List.of("-Amansart.unresolvedMethods=warn"),
                LOAN, LOAN_SOURCE, LOANS, repository("    List<Loan> findByMembr(String member);"));

        assertThat(result.success()).isTrue();
        assertThat(result.warnings()).containsExactly(UNRESOLVED);
        assertThat(result.generatedSource("demo.LoansImpl")).contains("UnsupportedOperationException");
    }
}
