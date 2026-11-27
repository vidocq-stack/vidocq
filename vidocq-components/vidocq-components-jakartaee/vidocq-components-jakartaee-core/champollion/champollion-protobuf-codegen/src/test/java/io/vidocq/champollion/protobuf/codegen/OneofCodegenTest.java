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
package io.vidocq.champollion.protobuf.codegen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.champollion.protobuf.Descriptors;
import io.vidocq.champollion.protobuf.Descriptors.Cardinality;
import io.vidocq.champollion.protobuf.Descriptors.FieldDescriptor;
import io.vidocq.champollion.protobuf.Parser;
import io.vidocq.champollion.protobuf.Protobuf;
import io.vidocq.champollion.protobuf.codegen.internal.ProtoParser;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A {@code oneof} from the {@code .proto} file to the generated record (BUG-20261127-84): its members are fields of
 * the message, with explicit presence, at most one of them is set, and the record tells which one.
 *
 * <p>Spec: <a href="https://protobuf.dev/programming-guides/proto3/#oneof">Proto3 §oneof</a>.</p>
 */
class OneofCodegenTest {

    private static final String PKG = "io.test.oneof";

    /** A scalar, a message, an enum and a string member, between two plain fields. */
    private static final String SHAPE_PROTO = """
            syntax = "proto3";
            package oneoftest;
            enum Color { COLOR_UNSPECIFIED = 0; RED = 1; GREEN = 2; }
            message Point { int32 x = 1; int32 y = 2; }
            message Shape {
              string name = 1;
              oneof kind {
                int64 count = 2;
                Point point = 3;
                Color color = 4;
                string label = 5;
              }
              int32 z = 6;
            }
            """;

    private static Descriptors.Descriptor shape() {
        return SchemaResolver.resolve(ProtoParser.parse("shape.proto", SHAPE_PROTO)).findMessageType("Shape");
    }

    private static Map<String, String> emit(boolean staticParser) {
        return new JavaEmitter(PKG, staticParser)
                .emit(SchemaResolver.resolve(ProtoParser.parse("shape.proto", SHAPE_PROTO)));
    }

    @Nested
    @DisplayName("Descriptor")
    class ResolvedDescriptor {

        @Test
        void every_member_is_a_field_with_explicit_presence() {
            Descriptors.Descriptor shape = shape();
            assertEquals(List.of("name", "count", "point", "color", "label", "z"),
                    shape.fields().stream().map(FieldDescriptor::name).toList());
            for (String member : List.of("count", "point", "color", "label")) {
                FieldDescriptor f = shape.findFieldByName(member);
                assertEquals("kind", f.containingOneof(), member);
                assertEquals(Cardinality.EXPLICIT, f.cardinality(), member);
            }
            assertNull(shape.findFieldByName("name").containingOneof());
            assertEquals(Cardinality.IMPLICIT, shape.findFieldByName("z").cardinality());
            assertEquals("oneoftest.Point", shape.findFieldByName("point").messageTypeName());
            assertEquals("oneoftest.Color", shape.findFieldByName("color").enumTypeName());
        }

        @Test
        void a_number_used_twice_across_a_oneof_is_refused() {
            assertThrows(SchemaResolver.SchemaResolutionException.class, () -> SchemaResolver.resolve(
                    ProtoParser.parse("dup.proto", """
                            syntax = "proto3";
                            message M { int32 a = 1; oneof c { string b = 1; } }
                            """)));
        }
    }

    @Nested
    @DisplayName("Generated record")
    class GeneratedSource {

        @Test
        void members_are_boxed_and_annotated_with_their_group() {
            String src = emit(false).get(PKG + ".Shape");
            assertTrue(src.contains("@ProtobufField(number = 2, type = FieldType.INT64, explicitPresence = true,"
                    + " oneofGroup = \"kind\") Long count"), src);
            assertTrue(src.contains("@ProtobufField(number = 3, type = FieldType.MESSAGE, explicitPresence = true,"
                    + " oneofGroup = \"kind\") Point point"), src);
            assertTrue(src.contains("@ProtobufField(number = 4, type = FieldType.ENUM, explicitPresence = true,"
                    + " oneofGroup = \"kind\") Color color"), src);
            assertTrue(src.contains("@ProtobufField(number = 1, type = FieldType.STRING) String name"), src);
            assertTrue(src.contains("public enum KindCase {"), src);
            assertTrue(src.contains("public KindCase kindCase()"), src);
        }
    }

    @Nested
    @DisplayName("Reflective runtime")
    class Reflective {

        @Test
        void the_case_names_the_member_that_is_set() throws Exception {
            Generated g = new Generated(JavaEmitterTest.compile(emit(false)));
            assertEquals("COUNT", g.kindCase(g.shape("s", 7L, null, null, null)));
            assertEquals("POINT", g.kindCase(g.shape("s", null, g.point(1, 2), null, null)));
            assertEquals("COLOR", g.kindCase(g.shape("s", null, null, g.color("GREEN"), null)));
            assertEquals("LABEL", g.kindCase(g.shape("s", null, null, null, "")));
            assertEquals("KIND_NOT_SET", g.kindCase(g.shape("s", null, null, null, null)));
        }

        @Test
        void the_record_refuses_two_members_set() throws Exception {
            Generated g = new Generated(JavaEmitterTest.compile(emit(false)));
            InvocationTargetException ex = assertThrows(InvocationTargetException.class,
                    () -> g.shape("s", 7L, null, null, "x"));
            IllegalArgumentException cause = assertInstanceOf(IllegalArgumentException.class, ex.getCause());
            assertTrue(cause.getMessage().contains("kind"), cause.getMessage());
        }

        @Test
        void a_member_set_to_its_default_value_is_written_and_read_back() throws Exception {
            Generated g = new Generated(JavaEmitterTest.compile(emit(false)));
            Object zero = g.shape("", 0L, null, null, null);
            byte[] bytes = Protobuf.toByteArray(zero);
            assertArrayEquals(new byte[] {0x10, 0x00}, bytes);
            Object back = Protobuf.parser(g.shape).parseFrom(bytes);
            assertEquals(zero, back);
            assertEquals("COUNT", g.kindCase(back));
        }

        @Test
        void every_member_round_trips() throws Exception {
            Generated g = new Generated(JavaEmitterTest.compile(emit(false)));
            for (Object shape : g.oneOfEach()) {
                assertEquals(shape, Protobuf.parser(g.shape).parseFrom(Protobuf.toByteArray(shape)));
            }
        }

        @Test
        void the_last_member_on_the_wire_wins() throws Exception {
            Generated g = new Generated(JavaEmitterTest.compile(emit(false)));
            assertLastWins(g, Protobuf.parser(g.shape));
        }
    }

    @Nested
    @DisplayName("Static parser (@ProtobufStatic)")
    class Static {

        @Test
        void the_static_parser_keeps_the_last_member_on_the_wire(@TempDir Path tmp) throws Exception {
            ProtobufStaticProcessorTest.CompileResult r = ProtobufStaticProcessorTest.compile(tmp, emit(true));
            try (URLClassLoader cl = new URLClassLoader(new URL[] {r.classesOutput().toUri().toURL()},
                    OneofCodegenTest.class.getClassLoader())) {
                Generated g = new Generated(cl);
                Parser<?> parser = (Parser<?>) cl.loadClass(PKG + ".Shape$$Parser")
                        .getDeclaredConstructor().newInstance();
                assertLastWins(g, parser);
                for (Object shape : g.oneOfEach()) {
                    assertEquals(shape, parser.parseFrom(Protobuf.toByteArray(shape)));
                }
            }
        }
    }

    /** {@code count = 7} then {@code point = {x: 1}}, and the other way round: only the last member is kept. */
    private static void assertLastWins(Generated g, Parser<?> parser) throws Exception {
        byte[] countThenPoint = {0x10, 0x07, 0x1A, 0x02, 0x08, 0x01};
        Object a = parser.parseFrom(countThenPoint);
        assertEquals(g.shape("", null, g.point(1, 0), null, null), a);
        assertEquals("POINT", g.kindCase(a));

        byte[] pointThenCount = {0x1A, 0x02, 0x08, 0x01, 0x10, 0x07};
        Object b = parser.parseFrom(pointThenCount);
        assertEquals(g.shape("", 7L, null, null, null), b);
        assertEquals("COUNT", g.kindCase(b));
    }

    /** The generated classes, loaded from {@code loader}. */
    private static final class Generated {
        final Class<?> shape;
        final Class<?> point;
        final Class<?> color;

        Generated(ClassLoader loader) throws ClassNotFoundException {
            this.shape = loader.loadClass(PKG + ".Shape");
            this.point = loader.loadClass(PKG + ".Point");
            this.color = loader.loadClass(PKG + ".Color");
        }

        Object shape(String name, Long count, Object point, Object color, String label) throws Exception {
            return shape.getDeclaredConstructors()[0].newInstance(name, count, point, color, label, 0);
        }

        Object point(int x, int y) throws Exception {
            return point.getDeclaredConstructors()[0].newInstance(x, y);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        Object color(String constant) {
            return Enum.valueOf((Class) color, constant);
        }

        String kindCase(Object shapeValue) throws Exception {
            return ((Enum<?>) shape.getMethod("kindCase").invoke(shapeValue)).name();
        }

        List<Object> oneOfEach() throws Exception {
            return List.of(
                    shape("a", -3L, null, null, null),
                    shape("b", null, point(4, -5), null, null),
                    shape("c", null, null, color("RED"), null),
                    shape("d", null, null, null, "label"));
        }
    }
}
