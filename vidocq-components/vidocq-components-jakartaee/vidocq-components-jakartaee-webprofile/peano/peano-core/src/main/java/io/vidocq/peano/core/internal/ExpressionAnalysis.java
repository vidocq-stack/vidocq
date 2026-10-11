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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import io.vidocq.peano.core.internal.syntax.Ast;
import io.vidocq.peano.core.internal.syntax.ExpressionParser;
import io.vidocq.peano.core.internal.syntax.Template;

/** Context-free syntax, canonical identity and ordered creation-time lookup descriptors. */
public final class ExpressionAnalysis {
    public record FunctionName(String prefix,String name,String key) {}
    private final Template template;
    private final Template identity;
    private final List<String> variableNames;
    private final List<List<FunctionName>> functionsByPart;
    private final long estimatedBytes;
    private final boolean stringValues;

    private ExpressionAnalysis(Template template,Template identity,List<String> variableNames,
            List<List<FunctionName>> functionsByPart,long estimatedBytes,boolean stringValues) {
        this.template=template; this.identity=identity; this.variableNames=variableNames;
        this.functionsByPart=functionsByPart; this.estimatedBytes=estimatedBytes; this.stringValues=stringValues;
    }

    public Template template() { return template; }
    public Template identity() { return identity; }
    public List<String> variableNames() { return variableNames; }
    public List<List<FunctionName>> functionsByPart() { return functionsByPart; }
    long estimatedBytes() { return estimatedBytes; }

    ExpressionAnalysis owned() {
        if (!stringValues) return this;
        return new ExpressionAnalysis(OwnedSyntax.copy(template),identity,variableNames,functionsByPart,estimatedBytes,true);
    }

    public static ExpressionAnalysis parse(String source) {
        Template template=ExpressionParser.parse(source);
        var builder=new Builder(template.parts().size());
        for (Template.Part part:template.parts()) {
            builder.syntaxBytes+=64;
            if (part instanceof Template.Evaluation evaluation) {
                builder.partFunctions=List.of();
                builder.visit(evaluation.body(),Set.of());
                builder.functions.add(builder.partFunctions);
            } else if (part instanceof Template.Text text) {
                builder.syntaxBytes+=48+2L*text.value().length();
            }
        }
        var functions=new ArrayList<List<FunctionName>>(builder.functions.size());
        long descriptors=32L+8L*builder.variables.size()+8L*builder.functions.size();
        for (var part:builder.functions) {
            functions.add(List.copyOf(part)); descriptors+=32L+8L*part.size();
            for (var function:part) descriptors+=96L+2L*function.key().length();
        }
        // Conservative artifact estimate, not an exact JVM heap-size measurement.
        long weight=128L+2L*source.length()+2L*builder.syntaxBytes+descriptors;
        return new ExpressionAnalysis(template,ExpressionIdentity.template(template),List.copyOf(builder.variables),
                List.copyOf(functions),weight,builder.stringValues);
    }

    private static final class Builder {
        private final List<String> variables=new ArrayList<>();
        private final Set<String> seenVariables=new HashSet<>();
        private Set<String> seenFunctions;
        private final List<List<FunctionName>> functions;
        private List<FunctionName> partFunctions;
        private long syntaxBytes=128;
        private boolean stringValues;

        private Builder(int partCount) { functions=new ArrayList<>(partCount); }

        private void function(String prefix,String name) {
            String key=prefix+":"+name;
            if (seenFunctions==null) seenFunctions=new HashSet<>();
            if (seenFunctions.add(key)) {
                if (partFunctions.isEmpty()) partFunctions=new ArrayList<>();
                partFunctions.add(new FunctionName(prefix,name,key));
            }
        }

        private void visit(Ast node,Set<String> locals) {
            syntaxBytes+=64;
            switch (node) {
                case Ast.Literal literal -> {
                    var token=literal.token();
                    syntaxBytes+=128L+2L*token.text().length();
                    if (token.value()!=null) {
                        syntaxBytes+=2L*token.value().length();
                        if (!token.value().isEmpty()) stringValues=true;
                    }
                }
                case Ast.Identifier name -> {
                    syntaxBytes+=48L+2L*name.name().length();
                    if (!locals.contains(name.name()) && seenVariables.add(name.name())) variables.add(name.name());
                }
                case Ast.Unary unary -> {
                    syntaxBytes+=96L+2L*unary.operator().text().length(); visit(unary.operand(),locals);
                }
                case Ast.Binary binary -> {
                    syntaxBytes+=96L+2L*binary.operator().text().length();
                    visit(binary.left(),locals); visit(binary.right(),locals);
                }
                case Ast.Conditional choice -> {
                    visit(choice.condition(),locals); visit(choice.whenTrue(),locals); visit(choice.whenFalse(),locals);
                }
                case Ast.Property property -> { visit(property.base(),locals); visit(property.key(),locals); }
                case Ast.Call call -> {
                    syntaxBytes+=8L*call.arguments().size();
                    if (call.target() instanceof Ast.Identifier name && !locals.contains(name.name())) function("",name.name());
                    visit(call.target(),locals);
                    call.arguments().forEach(argument->visit(argument,locals));
                }
                case Ast.Function function -> {
                    syntaxBytes+=96L+2L*function.prefix().length()+2L*function.name().length()+8L*function.arguments().size();
                    function(function.prefix(),function.name());
                    function.arguments().forEach(argument->visit(argument,locals));
                }
                case Ast.Lambda lambda -> {
                    syntaxBytes+=8L*lambda.parameters().size();
                    for (String parameter:lambda.parameters()) syntaxBytes+=48L+2L*parameter.length();
                    var nested=new HashSet<>(locals); nested.addAll(lambda.parameters());
                    visit(lambda.body(),nested);
                }
                case Ast.ListLiteral list -> {
                    syntaxBytes+=8L*list.elements().size(); list.elements().forEach(element->visit(element,locals));
                }
                case Ast.SetLiteral set -> {
                    syntaxBytes+=8L*set.elements().size(); set.elements().forEach(element->visit(element,locals));
                }
                case Ast.MapLiteral map -> {
                    syntaxBytes+=40L*map.entries().size();
                    map.entries().forEach(entry->{ visit(entry.key(),locals); visit(entry.value(),locals); });
                }
            }
        }
    }
}
