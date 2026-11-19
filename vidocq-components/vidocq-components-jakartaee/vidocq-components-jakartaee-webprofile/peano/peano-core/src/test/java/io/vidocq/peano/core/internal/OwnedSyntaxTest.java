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
package io.vidocq.peano.core.internal;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import io.vidocq.peano.core.internal.syntax.Ast;
import io.vidocq.peano.core.internal.syntax.ExpressionParser;
import io.vidocq.peano.core.internal.syntax.Template;
import io.vidocq.peano.core.internal.syntax.Token;
import org.junit.jupiter.api.Test;

class OwnedSyntaxTest {
    @Test void copiesEveryNonEmptyStringLiteralInEverySyntaxForm() {
        Template original=ExpressionParser.parse("${-'a' + ('b' ? 'c' : 'd')['e'].f('g') + fn:h('i') + (x -> 'j')"
                +" + [1, 'k'] + {'l'} + {1: 2, 'm': 'n'} + ''}");
        Template copy=OwnedSyntax.copy(original);
        assertEquals(original,copy);
        List<String> before=strings(original);
        List<String> after=strings(copy);
        assertEquals(List.of("a","b","c","d","e","f","g","i","j","k","l","m","n",""),after);
        for (int i=0;i<before.size()-1;i++) assertNotSame(before.get(i),after.get(i),before.get(i));
        assertSame(before.getLast(),after.getLast());
    }

    @Test void reusesTextPartsAndSyntaxWithoutStringValues() {
        Template original=ExpressionParser.parse("${'a'} text ${1 + x + -y + (a ? b : c) + p[1] + f(1)"
                +" + fn:g(2) + (z -> z) + [1] + {2} + {3: 4} + ''}");
        Template copy=OwnedSyntax.copy(original);
        assertEquals(original,copy);
        assertNotSame(original.parts().get(0),copy.parts().get(0));
        assertSame(original.parts().get(1),copy.parts().get(1));
        assertSame(original.parts().get(2),copy.parts().get(2));
    }

    private static List<String> strings(Template template) {
        var values=new ArrayList<String>();
        for (Template.Part part:template.parts()) {
            if (part instanceof Template.Evaluation(Ast body)) collect(body,values);
        }
        return values;
    }

    private static void collect(Ast node,List<String> values) {
        switch (node) {
            case Ast.Literal(Token token) -> {
                if (token.value()!=null) values.add(token.value());
            }
            case Ast.Identifier _ -> {
                // An identifier holds no string literal.
            }
            case Ast.Unary(_, Ast operand) -> collect(operand,values);
            case Ast.Binary(_, Ast left, Ast right) -> {
                collect(left,values);
                collect(right,values);
            }
            case Ast.Conditional(Ast condition, Ast whenTrue, Ast whenFalse) -> {
                collect(condition,values);
                collect(whenTrue,values);
                collect(whenFalse,values);
            }
            case Ast.Property(Ast base, Ast key) -> {
                collect(base,values);
                collect(key,values);
            }
            case Ast.Call(Ast target, List<Ast> arguments) -> {
                collect(target,values);
                arguments.forEach(argument->collect(argument,values));
            }
            case Ast.Function(_, _, List<Ast> arguments) -> arguments.forEach(argument->collect(argument,values));
            case Ast.Lambda(_, Ast body) -> collect(body,values);
            case Ast.ListLiteral(List<Ast> elements) -> elements.forEach(element->collect(element,values));
            case Ast.SetLiteral(List<Ast> elements) -> elements.forEach(element->collect(element,values));
            case Ast.MapLiteral(List<Ast.Entry> entries) -> entries.forEach(entry->{
                collect(entry.key(),values);
                collect(entry.value(),values);
            });
        }
    }
}
