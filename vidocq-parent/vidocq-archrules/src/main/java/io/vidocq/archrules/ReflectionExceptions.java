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
package io.vidocq.archrules;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.PackageMatcher;

/**
 * The documented adapters of {@link VidocqArchRules#noReflection(ReflectionExceptions, String)}: the places where
 * a brick reads or invokes user code reflectively on purpose. An exception is declared by package, by annotation
 * or by class, and always with its reason: the rule prints every exception and its reason in its description, so
 * the list a reviewer reads is the list the rule applies.
 *
 * <p>An exception covers a class and its nested classes (an annotation on an outer class covers what it encloses). Prefer a package (a {@code gen} or {@code reflective}
 * package that holds the adapters) or an annotation (a marker the brick defines, on the adapters) to a list of
 * class names: a new class in the package, or carrying the marker, is documented by where it sits.
 */
public final class ReflectionExceptions {

    private static final ReflectionExceptions NONE = new ReflectionExceptions(List.of());

    private final List<Entry> entries;

    private ReflectionExceptions(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    /** No exception: the brick reads and invokes no user code reflectively. */
    public static ReflectionExceptions none() {
        return NONE;
    }

    /** A builder of the exceptions of one brick. */
    public static Builder builder() {
        return new Builder();
    }

    /** Whether the class, or the top-level class it is nested in, is covered by an exception. */
    DescribedPredicate<JavaClass> covering() {
        return DescribedPredicate.describe("are not a documented reflective adapter", type -> entries.stream()
                .noneMatch(entry -> entry.covers(type)));
    }

    /** The exceptions and their reasons, one per line, for the description of the rule. */
    String describe() {
        if (entries.isEmpty()) {
            return "no exception";
        }
        StringBuilder text = new StringBuilder("documented adapters:");
        entries.forEach(entry -> text.append("\n  - ").append(entry.label()).append(": ").append(entry.reason()));
        return text.toString();
    }

    private static String requireText(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("a reflective exception needs its " + what);
        }
        return value;
    }

    /** One exception: what it covers and why. */
    private interface Entry {
        boolean covers(JavaClass type);

        String label();

        String reason();
    }

    private record PackageEntry(String pattern, String reason) implements Entry {
        @Override
        public boolean covers(JavaClass type) {
            return PackageMatcher.of(pattern).matches(type.getPackageName());
        }

        @Override
        public String label() {
            return "package " + pattern;
        }
    }

    private record AnnotationEntry(String annotationName, String reason) implements Entry {
        @Override
        public boolean covers(JavaClass type) {
            for (Optional<JavaClass> current = Optional.of(type); current.isPresent(); current = current.get().getEnclosingClass()) {
                if (current.get().isAnnotatedWith(annotationName)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String label() {
            return "classes annotated @" + annotationName.substring(Math.max(annotationName.lastIndexOf('.'), annotationName.lastIndexOf('$')) + 1)
                    + " (" + annotationName + ")";
        }
    }

    private record ClassEntry(String className, String reason) implements Entry {
        @Override
        public boolean covers(JavaClass type) {
            return topLevel(type).getName().equals(className);
        }

        @Override
        public String label() {
            return "class " + className;
        }
    }

    private static JavaClass topLevel(JavaClass type) {
        JavaClass top = type;
        for (Optional<JavaClass> outer = top.getEnclosingClass(); outer.isPresent(); outer = top.getEnclosingClass()) {
            top = outer.get();
        }
        return top;
    }

    /** Collects the exceptions; each one is refused without its reason. */
    public static final class Builder {

        private final List<Entry> entries = new ArrayList<>();

        private Builder() {
            // Through ReflectionExceptions.builder().
        }

        /**
         * Spares the classes of a package.
         *
         * @param packagePattern an ArchUnit package pattern, {@code io.vidocq.foy.internal.gen..} for the package and
         *     its sub-packages
         * @param reason why reflection is wanted there, in one sentence
         */
        public Builder inPackage(String packagePattern, String reason) {
            entries.add(new PackageEntry(requireText(packagePattern, "package"), requireText(reason, "reason")));
            return this;
        }

        /**
         * Spares the classes carrying an annotation, which the brick defines for its adapters.
         *
         * @param annotation the marker, with a class or runtime retention
         * @param reason why reflection is wanted in the annotated classes, in one sentence
         */
        public Builder annotatedWith(Class<? extends Annotation> annotation, String reason) {
            return annotatedWith(annotation.getName(), reason);
        }

        /**
         * Spares the classes carrying an annotation, named by its qualified name.
         *
         * @param annotationName the qualified name of the marker
         * @param reason why reflection is wanted in the annotated classes, in one sentence
         */
        public Builder annotatedWith(String annotationName, String reason) {
            entries.add(new AnnotationEntry(requireText(annotationName, "annotation"), requireText(reason, "reason")));
            return this;
        }

        /**
         * Spares one top-level class and its nested classes. Reach for it last: a package or an annotation says
         * more than a name.
         *
         * @param className the binary name of the top-level class
         * @param reason why reflection is wanted in this class, in one sentence
         */
        public Builder inClass(String className, String reason) {
            entries.add(new ClassEntry(requireText(className, "class name"), requireText(reason, "reason")));
            return this;
        }

        /** The exceptions declared so far. */
        public ReflectionExceptions build() {
            return entries.isEmpty() ? NONE : new ReflectionExceptions(entries);
        }
    }
}
