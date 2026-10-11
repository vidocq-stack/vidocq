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

import java.util.List;

/**
 * Immutable expression syntax. Numeric literal text remains unconverted until evaluation.
 * Types stay in an implementation package visible only to the build-time compiler.
 */
public sealed interface Ast {
    record Literal(Token token) implements Ast {
    }

    record Identifier(String name) implements Ast {
    }

    record Unary(Token operator, Ast operand) implements Ast {
    }

    record Binary(Token operator, Ast left, Ast right) implements Ast {
    }

    record Conditional(Ast condition, Ast whenTrue, Ast whenFalse) implements Ast {
    }

    record Property(Ast base, Ast key) implements Ast {
    }

    record Call(Ast target, List<Ast> arguments) implements Ast {
        public Call {
            arguments = List.copyOf(arguments);
        }
    }

    record Function(String prefix, String name, List<Ast> arguments) implements Ast {
        public Function {
            arguments = List.copyOf(arguments);
        }
    }

    record Lambda(List<String> parameters, Ast body) implements Ast {
        public Lambda {
            parameters = List.copyOf(parameters);
        }
    }

    record ListLiteral(List<Ast> elements) implements Ast {
        public ListLiteral {
            elements = List.copyOf(elements);
        }
    }

    record SetLiteral(List<Ast> elements) implements Ast {
        public SetLiteral {
            elements = List.copyOf(elements);
        }
    }

    record MapLiteral(List<Entry> entries) implements Ast {
        public MapLiteral {
            entries = List.copyOf(entries);
        }
    }

    record Entry(Ast key, Ast value) {
    }
}

