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
package io.vidocq.runtime.maven;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The hand-written plugin.xml must declare every {@code @Parameter} of every mojo, with its property or default value,
 * or Maven never sets the field: {@code vidocq:check-module-info} threw a {@code NullPointerException} on its
 * {@code session} under {@code ./mvnw package} and {@code vidocq dev} while its unit tests, which set the field
 * themselves, passed (BUG-20261031-11). The annotations have class retention, so they are read from the class files.
 */
class PluginDescriptorCompletenessTest {

    private static final Path CLASSES = Path.of("target", "classes");
    private static final String MOJO = "Lorg/apache/maven/plugins/annotations/Mojo;";
    private static final String PARAMETER = "Lorg/apache/maven/plugins/annotations/Parameter;";

    @Test
    void everyMojoParameterIsDeclaredInThePluginDescriptor() throws IOException {
        String xml = Files.readString(CLASSES.resolve("META-INF/maven/plugin.xml"));
        Map<String, ClassModel> classes = readClasses();
        List<String> missing = new ArrayList<>();
        int mojos = 0;
        for (ClassModel model : classes.values()) {
            Optional<String> goal = annotation(invisible(model), MOJO).flatMap(a -> element(a, "name"));
            if (goal.isEmpty()) {
                continue;
            }
            mojos++;
            int start = xml.indexOf("<goal>" + goal.get() + "</goal>");
            if (start < 0) {
                missing.add(goal.get() + ": no <mojo>");
                continue;
            }
            String mojo = xml.substring(start, xml.indexOf("</mojo>", start));
            for (ClassModel type = model; type != null; type = superclass(type, classes)) {
                for (FieldModel field : type.fields()) {
                    Optional<Annotation> parameter = annotation(invisible(field), PARAMETER);
                    if (parameter.isEmpty()) {
                        continue;
                    }
                    String name = field.fieldName().stringValue();
                    if (!mojo.contains("<name>" + name + "</name>")) {
                        missing.add(goal.get() + ": parameter " + name + " not declared");
                        continue;
                    }
                    Optional<String> bound = element(parameter.get(), "property").map(p -> "${" + p + "}")
                            .or(() -> element(parameter.get(), "defaultValue"));
                    if (bound.isPresent() && !configured(mojo, name, bound.get())) {
                        missing.add(goal.get() + ": parameter " + name + " not bound to " + bound.get());
                    }
                }
            }
        }
        assertFalse(mojos == 0, "no mojo found in " + CLASSES);
        assertEquals(List.of(), missing);
    }

    /** Whether the {@code <configuration>} element of {@code name} carries {@code bound}, as text or default value. */
    private static boolean configured(String mojo, String name, String bound) {
        Matcher element = Pattern.compile("<" + Pattern.quote(name) + "(\\s[^>]*)?(/>|>([^<]*)</" + Pattern.quote(name) + ">)")
                .matcher(mojo.substring(Math.max(0, mojo.indexOf("<configuration>"))));
        while (element.find()) {
            if (element.group().contains(bound)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, ClassModel> readClasses() throws IOException {
        Map<String, ClassModel> classes = new HashMap<>();
        try (Stream<Path> files = Files.walk(CLASSES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                ClassModel model = ClassFile.of().parse(Files.readAllBytes(file));
                classes.put(model.thisClass().asInternalName(), model);
            }
        }
        return classes;
    }

    private static ClassModel superclass(ClassModel type, Map<String, ClassModel> classes) {
        return type.superclass().map(s -> classes.get(s.asInternalName())).orElse(null);
    }

    private static List<Annotation> invisible(ClassModel model) {
        return model.findAttribute(Attributes.runtimeInvisibleAnnotations()).map(a -> a.annotations()).orElse(List.of());
    }

    private static List<Annotation> invisible(FieldModel field) {
        return field.findAttribute(Attributes.runtimeInvisibleAnnotations()).map(a -> a.annotations()).orElse(List.of());
    }

    private static Optional<Annotation> annotation(List<Annotation> annotations, String descriptor) {
        return annotations.stream().filter(a -> a.className().stringValue().equals(descriptor)).findFirst();
    }

    private static Optional<String> element(Annotation annotation, String name) {
        return annotation.elements().stream()
                .filter(e -> e.name().stringValue().equals(name))
                .map(e -> e.value())
                .filter(v -> v instanceof AnnotationValue.OfString)
                .map(v -> ((AnnotationValue.OfString) v).stringValue())
                .findFirst();
    }
}
