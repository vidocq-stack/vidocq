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
package io.vidocq.peano.core.internal.syntax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import jakarta.el.ELException;
import org.junit.jupiter.api.Test;

class ParserCompositeExpressionTest {
    @Test
    void treatsPlainTextAndEscapedMarkersAsLiteralExpressions() {
        for (String text : List.of("", "true", "1 + 2", "price $5", "a\\b")) {
            var parsed = ExpressionParser.parse(text);
            assertEquals(Template.Kind.LITERAL, parsed.kind());
            assertEquals(List.of(new Template.Text(text)), parsed.parts());
        }
        assertEquals(List.of(new Template.Text("${x} #{y}")),
                ExpressionParser.parse("\\${x} \\#{y}").parts());
    }

    @Test
    void parsesImmediateDeferredAndCompositeExpressions() {
        var immediate = ExpressionParser.parse("${1 + 2}");
        assertEquals(Template.Kind.IMMEDIATE, immediate.kind());
        var eval = assertInstanceOf(Template.Evaluation.class, immediate.parts().getFirst());
        assertEquals("(PLUS 1 2)", GrammarAssertions.shape(eval.body()));
        assertEquals(Template.Kind.DEFERRED, ExpressionParser.parse("#{x}").kind());
        var composite = ExpressionParser.parse("Hello ${user.name}: ${1}");
        assertEquals(4, composite.parts().size());
        assertEquals(new Template.Text("Hello "), composite.parts().get(0));
        assertEquals(new Template.Text(": "), composite.parts().get(2));
    }

    @Test
    void balancesCollectionBracesAndIgnoresMarkersInsideStrings() {
        var parsed = ExpressionParser.parse("${{'a':'}','b':'${x}'}}");
        var eval = assertInstanceOf(Template.Evaluation.class, parsed.parts().getFirst());
        assertEquals("map('a':'}','b':'${x}')", GrammarAssertions.shape(eval.body()));
        assertEquals(1, parsed.parts().size());
    }

    @Test
    void rejectsMixedNestedAndUnterminatedExpressions() {
        for (String text : List.of("${a}#{b}", "#{a}${b}", "${${a}}", "${#{a}}",
                "text ${1", "${'x}", "${}", "${{'a':1}")) {
            assertThrows(ELException.class, () -> ExpressionParser.parse(text), text);
        }
    }

    @Test
    void reportsOffsetsInTheOriginalCompositeSource() {
        for (String text : List.of("prefix ${1 + )}", "prefix ${@}")) {
            ELException error = assertThrows(ELException.class, () -> ExpressionParser.parse(text));
            int offset = text.indexOf(text.contains("@") ? "@" : ")");
            assertTrue(error.getMessage().endsWith("offset " + offset), error.getMessage());
        }
    }

    @Test
    void copiesTheTemplateParts() {
        var parts = new java.util.ArrayList<Template.Part>();
        parts.add(new Template.Text("hello"));
        var template = new Template(Template.Kind.LITERAL, parts);
        parts.clear();
        assertEquals(1, template.parts().size());
        assertThrows(UnsupportedOperationException.class, () -> template.parts().clear());
    }

    @Test
    void distinguishesEscapedBackslashesFromEscapedExpressionMarkers() {
        String slash = Character.toString((char) 92);
        var parsed = ExpressionParser.parse(slash + slash + "${1}");
        assertEquals(Template.Kind.IMMEDIATE, parsed.kind());
        assertEquals(new Template.Text(slash), parsed.parts().getFirst());
        assertEquals(2, parsed.parts().size());
        assertEquals(List.of(new Template.Text(slash + "${1}")),
                ExpressionParser.parse(slash + slash + slash + "${1}").parts());
    }
}

