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
            if (part instanceof Template.Evaluation(Ast originalBody)) {
                Ast body=copy(originalBody);
                parts.add(body==originalBody ? part : new Template.Evaluation(body));
            } else parts.add(part);
        }
        return new Template(original.kind(),parts);
    }

    private static List<Ast> copy(List<Ast> original) {
        ArrayList<Ast> result=null;
        for (int i=0;i<original.size();i++) {
            Ast before=original.get(i);
            Ast after=copy(before);
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
            case Ast.Literal literal -> literal(literal);
            case Ast.Identifier identifier -> identifier;
            case Ast.Unary unary -> unary(unary);
            case Ast.Binary binary -> binary(binary);
            case Ast.Conditional choice -> conditional(choice);
            case Ast.Property property -> property(property);
            case Ast.Call call -> call(call);
            case Ast.Function function -> function(function);
            case Ast.Lambda lambda -> lambda(lambda);
            case Ast.ListLiteral list -> list(list);
            case Ast.SetLiteral set -> set(set);
            case Ast.MapLiteral map -> map(map);
        };
    }

    /** A fresh String instance per owning expression: identity must not be shared (COMP-007). */
    private static Ast literal(Ast.Literal literal) {
        Token token=literal.token();
        return token.value()==null || token.value().isEmpty() ? literal
                : new Ast.Literal(new Token(token.kind(),token.text(),new String(token.value()),token.start(),token.end()));
    }

    private static Ast unary(Ast.Unary unary) {
        Ast operand=copy(unary.operand());
        return operand==unary.operand() ? unary : new Ast.Unary(unary.operator(),operand);
    }

    private static Ast binary(Ast.Binary binary) {
        Ast left=copy(binary.left());
        Ast right=copy(binary.right());
        return left==binary.left() && right==binary.right() ? binary : new Ast.Binary(binary.operator(),left,right);
    }

    private static Ast conditional(Ast.Conditional choice) {
        Ast condition=copy(choice.condition());
        Ast yes=copy(choice.whenTrue());
        Ast no=copy(choice.whenFalse());
        return condition==choice.condition() && yes==choice.whenTrue() && no==choice.whenFalse()
                ? choice : new Ast.Conditional(condition,yes,no);
    }

    private static Ast property(Ast.Property property) {
        Ast base=copy(property.base());
        Ast key=copy(property.key());
        return base==property.base() && key==property.key() ? property : new Ast.Property(base,key);
    }

    private static Ast call(Ast.Call call) {
        Ast target=copy(call.target());
        var arguments=copy(call.arguments());
        return target==call.target() && arguments==call.arguments() ? call : new Ast.Call(target,arguments);
    }

    private static Ast function(Ast.Function function) {
        var arguments=copy(function.arguments());
        return arguments==function.arguments() ? function : new Ast.Function(function.prefix(),function.name(),arguments);
    }

    private static Ast lambda(Ast.Lambda lambda) {
        Ast body=copy(lambda.body());
        return body==lambda.body() ? lambda : new Ast.Lambda(lambda.parameters(),body);
    }

    private static Ast list(Ast.ListLiteral list) {
        var elements=copy(list.elements());
        return elements==list.elements() ? list : new Ast.ListLiteral(elements);
    }

    private static Ast set(Ast.SetLiteral set) {
        var elements=copy(set.elements());
        return elements==set.elements() ? set : new Ast.SetLiteral(elements);
    }

    private static Ast map(Ast.MapLiteral map) {
        ArrayList<Ast.Entry> entries=null;
        for (int i=0;i<map.entries().size();i++) {
            var entry=map.entries().get(i);
            Ast key=copy(entry.key());
            Ast value=copy(entry.value());
            if (entries==null && (key!=entry.key() || value!=entry.value())) {
                entries=new ArrayList<>(map.entries().size());
                entries.addAll(map.entries().subList(0,i));
            }
            if (entries!=null) entries.add(key==entry.key() && value==entry.value() ? entry : new Ast.Entry(key,value));
        }
        return entries==null ? map : new Ast.MapLiteral(entries);
    }
}
