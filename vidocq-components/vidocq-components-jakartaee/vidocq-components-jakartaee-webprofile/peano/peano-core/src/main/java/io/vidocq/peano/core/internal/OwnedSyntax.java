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

import java.util.ArrayList;
import java.util.List;
import io.vidocq.peano.core.internal.syntax.Ast;
import io.vidocq.peano.core.internal.syntax.Template;
import io.vidocq.peano.core.internal.syntax.Token;

/** Materializes expression-owned String values while reusing unaffected immutable syntax. */
final class OwnedSyntax {
    private OwnedSyntax() {}

    static Template copy(Template original) {
        var parts=new ArrayList<Template.Part>(original.parts().size());
        for (Template.Part part:original.parts()) {
            if (part instanceof Template.Evaluation evaluation) {
                Ast body=copy(evaluation.body());
                parts.add(body==evaluation.body() ? part : new Template.Evaluation(body));
            } else parts.add(part);
        }
        return new Template(original.kind(),parts);
    }

    private static List<Ast> copy(List<Ast> original) {
        ArrayList<Ast> result=null;
        for (int i=0;i<original.size();i++) {
            Ast before=original.get(i),after=copy(before);
            if (result==null && before!=after) {
                result=new ArrayList<>(original.size());
                result.addAll(original.subList(0,i));
            }
            if (result!=null) result.add(after);
        }
        return result==null ? original : List.copyOf(result);
    }

    private static Ast copy(Ast node) {
        return switch (node) {
            case Ast.Literal literal -> {
                Token token=literal.token();
                yield token.value()==null || token.value().isEmpty() ? literal
                        : new Ast.Literal(new Token(token.kind(),token.text(),new String(token.value()),token.start(),token.end()));
            }
            case Ast.Identifier identifier -> identifier;
            case Ast.Unary unary -> {
                Ast operand=copy(unary.operand());
                yield operand==unary.operand() ? unary : new Ast.Unary(unary.operator(),operand);
            }
            case Ast.Binary binary -> {
                Ast left=copy(binary.left()),right=copy(binary.right());
                yield left==binary.left() && right==binary.right() ? binary : new Ast.Binary(binary.operator(),left,right);
            }
            case Ast.Conditional choice -> {
                Ast condition=copy(choice.condition()),yes=copy(choice.whenTrue()),no=copy(choice.whenFalse());
                yield condition==choice.condition() && yes==choice.whenTrue() && no==choice.whenFalse()
                        ? choice : new Ast.Conditional(condition,yes,no);
            }
            case Ast.Property property -> {
                Ast base=copy(property.base()),key=copy(property.key());
                yield base==property.base() && key==property.key() ? property : new Ast.Property(base,key);
            }
            case Ast.Call call -> {
                Ast target=copy(call.target()); var arguments=copy(call.arguments());
                yield target==call.target() && arguments==call.arguments() ? call : new Ast.Call(target,arguments);
            }
            case Ast.Function function -> {
                var arguments=copy(function.arguments());
                yield arguments==function.arguments() ? function : new Ast.Function(function.prefix(),function.name(),arguments);
            }
            case Ast.Lambda lambda -> {
                Ast body=copy(lambda.body());
                yield body==lambda.body() ? lambda : new Ast.Lambda(lambda.parameters(),body);
            }
            case Ast.ListLiteral list -> {
                var elements=copy(list.elements());
                yield elements==list.elements() ? list : new Ast.ListLiteral(elements);
            }
            case Ast.SetLiteral set -> {
                var elements=copy(set.elements());
                yield elements==set.elements() ? set : new Ast.SetLiteral(elements);
            }
            case Ast.MapLiteral map -> {
                ArrayList<Ast.Entry> entries=null;
                for (int i=0;i<map.entries().size();i++) {
                    var entry=map.entries().get(i); Ast key=copy(entry.key()),value=copy(entry.value());
                    if (entries==null && (key!=entry.key() || value!=entry.value())) {
                        entries=new ArrayList<>(map.entries().size()); entries.addAll(map.entries().subList(0,i));
                    }
                    if (entries!=null) entries.add(key==entry.key() && value==entry.value() ? entry : new Ast.Entry(key,value));
                }
                yield entries==null ? map : new Ast.MapLiteral(entries);
            }
        };
    }
}
