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
package io.vidocq.runtime.maven.dev;

import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.Attribute;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.Label;
import java.lang.classfile.MethodModel;
import java.lang.classfile.TypeAnnotation;
import java.lang.classfile.attribute.AnnotationDefaultAttribute;
import java.lang.classfile.attribute.ConstantValueAttribute;
import java.lang.classfile.attribute.EnclosingMethodAttribute;
import java.lang.classfile.attribute.ExceptionsAttribute;
import java.lang.classfile.attribute.InnerClassInfo;
import java.lang.classfile.attribute.InnerClassesAttribute;
import java.lang.classfile.attribute.MethodParametersAttribute;
import java.lang.classfile.attribute.NestHostAttribute;
import java.lang.classfile.attribute.NestMembersAttribute;
import java.lang.classfile.attribute.PermittedSubclassesAttribute;
import java.lang.classfile.attribute.RecordAttribute;
import java.lang.classfile.attribute.RuntimeInvisibleAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeInvisibleParameterAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeInvisibleTypeAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleParameterAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleTypeAnnotationsAttribute;
import java.lang.classfile.attribute.SignatureAttribute;
import java.lang.classfile.attribute.SourceFileAttribute;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.ExceptionCatch;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.IncrementInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LineNumber;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.LocalVariable;
import java.lang.classfile.instruction.LocalVariableType;
import java.lang.classfile.instruction.LookupSwitchInstruction;
import java.lang.classfile.instruction.NewMultiArrayInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.NewPrimitiveArrayInstruction;
import java.lang.classfile.instruction.NewReferenceArrayInstruction;
import java.lang.classfile.instruction.StoreInstruction;
import java.lang.classfile.instruction.TableSwitchInstruction;
import java.lang.classfile.instruction.TypeCheckInstruction;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * What the hot swap classifier compares of a compiled class (design §2): its <em>shape</em>, everything the JVM
 * refuses to change in a redefinition and everything the frameworks read at start, and the <em>code</em> of each
 * method. Both are rendered symbolically, names and constants resolved, so that a renumbered constant pool is no
 * change. The shape leaves out {@code Code}, {@code BootstrapMethods}, {@code SourceFile},
 * {@code SourceDebugExtension} and the {@code InnerClasses} entries about other classes (the
 * {@code MethodHandles$Lookup} entry a first lambda or string concatenation adds). The code keeps the instructions,
 * the exception table, the line numbers and the local variable tables, but not the stack maps, which derive from
 * the rest.
 */
final class ClassFingerprint {

    private static final Set<String> ANNOTATION_ATTRIBUTES = Set.of("RuntimeVisibleAnnotations",
            "RuntimeInvisibleAnnotations", "RuntimeVisibleTypeAnnotations", "RuntimeInvisibleTypeAnnotations",
            "RuntimeVisibleParameterAnnotations", "RuntimeInvisibleParameterAnnotations");
    private static final Set<String> NOT_SHAPE = Set.of("Code", "BootstrapMethods", "SourceFile",
            "SourceDebugExtension");
    private static final String INIT = "<init>";
    private static final String CLINIT = "<clinit>";
    private static final String LAMBDA = "lambda$";
    private static final String DESERIALIZE_LAMBDA = "$deserializeLambda$";
    private static final String SERIAL_VERSION_UID = "serialVersionUID";
    private static final String WRITE_OBJECT = "writeObject(Ljava/io/ObjectOutputStream;)V";
    private static final String EXTERNALIZABLE = "java.io.Externalizable";
    private static final String RECORD = "java.lang.Record";
    private static final String ENUM = "java.lang.Enum";
    private static final String CONSTANT_VALUE = "ConstantValue";
    /** The field types {@code Field.getLong} reads, as {@code ObjectStreamClass} reads a declared UID. */
    private static final Set<String> LONG_COMPATIBLE = Set.of("J", "I", "S", "C", "B");
    private static final int STATIC_FINAL = ClassFile.ACC_STATIC | ClassFile.ACC_FINAL;
    /** The field modifiers the default {@code serialVersionUID} hashes. */
    private static final int FIELD_MODIFIERS = ClassFile.ACC_PUBLIC | ClassFile.ACC_PRIVATE | ClassFile.ACC_PROTECTED
            | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL | ClassFile.ACC_VOLATILE | ClassFile.ACC_TRANSIENT;
    /** The constructor and method modifiers the default {@code serialVersionUID} hashes. */
    private static final int METHOD_MODIFIERS = ClassFile.ACC_PUBLIC | ClassFile.ACC_PRIVATE | ClassFile.ACC_PROTECTED
            | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL | ClassFile.ACC_SYNCHRONIZED | ClassFile.ACC_NATIVE
            | ClassFile.ACC_ABSTRACT | ClassFile.ACC_STRICT;

    /** A field or a method: its modifiers and descriptor, and its attributes by name. */
    private record Member(String name, String descriptor, int flags, SequencedMap<String, String> attributes) {}

    private final ClassModel model;
    private final String binaryName;
    private final String sourceFile;
    private final String superclass;
    private final List<String> interfaces;
    private final int flags;
    private final SequencedMap<String, String> classAttributes;
    private final List<Member> fields;
    private final Map<String, Member> methods;
    private final SequencedMap<String, String> codeByMethod;
    private final Set<String> serializableLambdas;

    private ClassFingerprint(ClassModel model) {
        this.model = model;
        this.binaryName = dotted(model.thisClass());
        this.sourceFile = model.findAttribute(java.lang.classfile.Attributes.sourceFile())
                .map(SourceFileAttribute::sourceFile).map(e -> e.stringValue()).orElse("");
        this.superclass = model.superclass().map(ClassFingerprint::dotted).orElse(null);
        this.interfaces = model.interfaces().stream().map(ClassFingerprint::dotted).toList();
        this.flags = model.flags().flagsMask();
        this.classAttributes = attributes(model.attributes(), model.thisClass().asInternalName());
        this.fields = model.fields().stream().map(f -> new Member(f.fieldName().stringValue(),
                f.fieldType().stringValue(), f.flags().flagsMask(), attributes(f.attributes(), null))).toList();
        var byKey = new TreeMap<String, Member>();
        var code = new LinkedHashMap<String, String>();
        var serializable = new LinkedHashSet<String>();
        for (MethodModel m : model.methods()) {
            String name = m.methodName().stringValue();
            String descriptor = m.methodType().stringValue();
            byKey.put(name + descriptor, new Member(name, descriptor, m.flags().flagsMask(),
                    attributes(m.attributes(), null)));
            code.put(name + descriptor, m.code().map(ClassFingerprint::code).orElse(""));
            if (name.equals(DESERIALIZE_LAMBDA)) {
                m.code().ifPresent(body -> serializable.addAll(lambdaNames(body)));
            }
        }
        this.serializableLambdas = Collections.unmodifiableSet(serializable);
        this.methods = Collections.unmodifiableMap(byKey);
        this.codeByMethod = Collections.unmodifiableSequencedMap(code);
    }

    /**
     * Parses a class file.
     *
     * @throws IllegalArgumentException when the bytes are not a class file the JDK can read
     */
    static ClassFingerprint of(byte[] classFile) {
        return new ClassFingerprint(ClassFile.of().parse(classFile));
    }

    /** The parsed class, for the call graph and the start-time rules. */
    ClassModel model() {
        return model;
    }

    String binaryName() {
        return binaryName;
    }

    /** The {@code SourceFile} attribute, such as {@code MainView.java}; empty when the class has none. */
    String sourceFile() {
        return sourceFile;
    }

    /** Dotted binary name; {@code null} for {@code java.lang.Object} and a module descriptor. */
    String superclass() {
        return superclass;
    }

    List<String> interfaces() {
        return interfaces;
    }

    /** Each method's code, by name and descriptor ({@code greet(Ljava/lang/String;)V}). */
    SequencedMap<String, String> codeByMethod() {
        return codeByMethod;
    }

    /**
     * The first difference of shape since {@code previous}, as the log says it after the class's simple name:
     * {@code gained a method lambda$new$1}, {@code lost a field total}, {@code changed an annotation on hello()}.
     */
    Optional<String> firstShapeDifference(ClassFingerprint previous) {
        if (flags != previous.flags) {
            return Optional.of("changed its modifiers");
        }
        if (!Objects.equals(superclass, previous.superclass)) {
            return Optional.of("changed its superclass");
        }
        if (!interfaces.equals(previous.interfaces)) {
            return Optional.of("changed its interfaces");
        }
        return attributeDifference(classAttributes, previous.classAttributes, "the class")
                .or(() -> fieldDifference(previous))
                .or(() -> methodDifference(previous));
    }

    /**
     * The first difference since {@code previous} that changes what a serialized instance holds, worded as
     * {@link #firstShapeDifference}: its superclass, its interfaces, or an instance field gained, lost or retyped.
     * {@code transient} fields count: a restored instance leaves them at their default, as a new one would not.
     */
    Optional<String> instanceShapeDifference(ClassFingerprint previous) {
        if (!Objects.equals(superclass, previous.superclass)) {
            return Optional.of("changed its superclass");
        }
        if (!interfaces.equals(previous.interfaces)) {
            return Optional.of("changed its interfaces");
        }
        var now = byName(instanceFields(fields));
        var before = byName(instanceFields(previous.fields));
        for (String name : now.keySet()) {
            if (!before.containsKey(name)) {
                return Optional.of("gained a field " + name);
            }
        }
        for (String name : before.keySet()) {
            if (!now.containsKey(name)) {
                return Optional.of("lost a field " + name);
            }
        }
        for (Member field : now.values()) {
            if (!field.descriptor().equals(before.get(field.name()).descriptor())) {
                return Optional.of("changed the type of field " + field.name());
            }
        }
        return Optional.empty();
    }

    /**
     * The {@code serialVersionUID} this class declares, as Java serialization reads it: a {@code static final} field of
     * that name whose value fits a {@code long}. Its constant value, or {@code <clinit>} when the class computes it at
     * initialization; empty when the class declares none, and serialization computes a default one.
     */
    Optional<String> declaredSerialVersionUid() {
        return fields.stream()
                .filter(field -> field.name().equals(SERIAL_VERSION_UID) && LONG_COMPATIBLE.contains(field.descriptor())
                        && (field.flags() & STATIC_FINAL) == STATIC_FINAL)
                .findFirst()
                .map(field -> field.attributes().getOrDefault(CONSTANT_VALUE, CLINIT));
    }

    /**
     * The {@code serialVersionUID} Java serialization computes for this class when it declares none, from the class
     * file alone (Java Object Serialization Specification §4.6, as {@code ObjectStreamClass} computes it): the
     * SHA-1 of the class name and modifiers, the sorted interface names, the fields but the private static and
     * private transient ones, the static initializer, and the non-private constructors and methods. A method added
     * that is not private changes it; a lambda, which is private, does not. The modifiers of a nested class come from
     * its {@code InnerClasses} entry, as {@code Class.getModifiers()} reads them.
     */
    long defaultSerialVersionUid() {
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        try {
            out.writeUTF(binaryName);
            int classFlags = classModifiers() & (ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_INTERFACE
                    | ClassFile.ACC_ABSTRACT);
            if ((classFlags & ClassFile.ACC_INTERFACE) != 0) {
                boolean anyMethod = methods.values().stream().anyMatch(m -> !m.name().equals(CLINIT));
                classFlags = anyMethod ? classFlags | ClassFile.ACC_ABSTRACT : classFlags & ~ClassFile.ACC_ABSTRACT;
            }
            out.writeInt(classFlags);
            for (String name : interfaces.stream().sorted().toList()) {
                out.writeUTF(name);
            }
            for (Member field : fields.stream().sorted(Comparator.comparing(Member::name)).toList()) {
                int mods = field.flags() & FIELD_MODIFIERS;
                if ((mods & ClassFile.ACC_PRIVATE) == 0
                        || (mods & (ClassFile.ACC_STATIC | ClassFile.ACC_TRANSIENT)) == 0) {
                    out.writeUTF(field.name());
                    out.writeInt(mods);
                    out.writeUTF(field.descriptor());
                }
            }
            if (methods.values().stream().anyMatch(m -> m.name().equals(CLINIT))) {
                out.writeUTF(CLINIT);
                out.writeInt(ClassFile.ACC_STATIC);
                out.writeUTF("()V");
            }
            var constructors = methods.values().stream().filter(m -> m.name().equals(INIT))
                    .sorted(Comparator.comparing(Member::descriptor));
            var others = methods.values().stream().filter(m -> !m.name().equals(INIT) && !m.name().equals(CLINIT))
                    .sorted(Comparator.comparing(Member::name).thenComparing(Member::descriptor));
            for (Member method : java.util.stream.Stream.concat(constructors, others).toList()) {
                int mods = method.flags() & METHOD_MODIFIERS;
                if ((mods & ClassFile.ACC_PRIVATE) == 0) {
                    out.writeUTF(method.name());
                    out.writeInt(mods);
                    out.writeUTF(method.descriptor().replace('/', '.'));
                }
            }
            out.flush();
            // The serialization specification's hash, as ObjectStreamClass computes it: an identifier, no security.
            byte[] hash = MessageDigest.getInstance("SHA").digest(bytes.toByteArray()); // NOSONAR java:S4790
            long uid = 0;
            for (int i = Math.min(hash.length, 8) - 1; i >= 0; i--) {
                uid = (uid << 8) | (hash[i] & 0xFF);
            }
            return uid;
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("cannot compute the default serialVersionUID of " + binaryName, e);
        }
    }

    /** The modifiers {@code Class.getModifiers()} gives: a nested class's come from its {@code InnerClasses} entry. */
    private int classModifiers() {
        return model.findAttribute(java.lang.classfile.Attributes.innerClasses()).stream()
                .flatMap(attribute -> attribute.classes().stream())
                .filter(inner -> inner.innerClass().asInternalName().equals(model.thisClass().asInternalName()))
                .findFirst()
                .map(InnerClassInfo::flagsMask)
                .orElse(flags) & ~ClassFile.ACC_SUPER;
    }

    /**
     * How the {@code serialVersionUID} changed since {@code previous} in a way the dev handover cannot read, worded as
     * {@link #firstShapeDifference}: {@code changed its serialVersionUID from 1 to 2}. The UID is the declared one,
     * or the default one. Equal UIDs read as before. Different ones are read with the new class, by Foy's import, only
     * when neither version declares a {@code serialVersionUID} field, is {@code Externalizable} or declares
     * {@code writeObject}; the fields and the superclass are {@link #instanceShapeDifference}'s. Records and enums
     * have none: serialization waives it.
     *
     * @param externalizable whether this class is {@code Externalizable}, through any of its supertypes
     */
    Optional<String> serialVersionUidDifference(ClassFingerprint previous, boolean externalizable) {
        if ((flags & ClassFile.ACC_ENUM) != 0 || RECORD.equals(superclass) || ENUM.equals(superclass)) {
            return Optional.empty();
        }
        var now = declaredSerialVersionUid();
        var before = previous.declaredSerialVersionUid();
        String uidNow = now.orElseGet(() -> Long.toString(defaultSerialVersionUid()));
        String uidBefore = before.orElseGet(() -> Long.toString(previous.defaultSerialVersionUid()));
        if (uidNow.equals(uidBefore)) {
            return Optional.empty();
        }
        if (now.isPresent() && before.isPresent()) {
            return Optional.of("changed its serialVersionUID from " + uidBefore + " to " + uidNow);
        }
        if (now.isPresent()) {
            return Optional.of("declared serialVersionUID " + uidNow + ", not its default " + uidBefore);
        }
        if (before.isPresent()) {
            return Optional.of("no longer declares serialVersionUID " + uidBefore);
        }
        String changed = "changed its default serialVersionUID and ";
        if (hasSerialVersionUidField() || previous.hasSerialVersionUidField()) {
            return Optional.of(changed + "has a serialVersionUID field that is not static final");
        }
        if (externalizable || previous.interfaces.contains(EXTERNALIZABLE)) {
            return Optional.of(changed + "is Externalizable");
        }
        if (methods.containsKey(WRITE_OBJECT) || previous.methods.containsKey(WRITE_OBJECT)) {
            return Optional.of(changed + "declares writeObject");
        }
        return Optional.empty();
    }

    /** A {@code long} field named {@code serialVersionUID}, whatever its modifiers, as Foy's import looks for one. */
    private boolean hasSerialVersionUidField() {
        return fields.stream().anyMatch(field -> field.name().equals(SERIAL_VERSION_UID)
                && field.descriptor().equals("J"));
    }

    /**
     * Whether the serializable lambdas of this class moved since {@code previous}: it gained or lost one while the code
     * of one it kept changed, as when a lambda is inserted before the others and javac numbers them again. A listener
     * restored from a session would then run another lambda's code. Lambdas appended, the last one removed, or a body
     * edited in place did not move. Only the lambdas {@code $deserializeLambda$} names count: no other can be read
     * back from a session.
     */
    boolean lambdasMoved(ClassFingerprint previous) {
        Set<String> now = lambdas(codeByMethod.keySet(), serializableLambdas);
        Set<String> before = lambdas(previous.codeByMethod.keySet(), previous.serializableLambdas);
        return !now.equals(before) && now.stream().filter(before::contains)
                .anyMatch(key -> !codeByMethod.get(key).equals(previous.codeByMethod.get(key)));
    }

    /** The names of the methods whose code changed since {@code previous}, among the methods both have. */
    Set<String> methodsWithChangedCode(ClassFingerprint previous) {
        Set<String> changed = new LinkedHashSet<>();
        methodKeysWithChangedCode(previous).forEach(key -> changed.add(methods.get(key).name()));
        return changed;
    }

    /** As {@link #methodsWithChangedCode}, by name and descriptor ({@code greet(Ljava/lang/String;)V}). */
    Set<String> methodKeysWithChangedCode(ClassFingerprint previous) {
        Set<String> changed = new LinkedHashSet<>();
        codeByMethod.forEach((key, code) -> {
            String before = previous.codeByMethod.get(key);
            if (before != null && !before.equals(code)) {
                changed.add(key);
            }
        });
        return changed;
    }

    private Optional<String> fieldDifference(ClassFingerprint previous) {
        var now = byName(fields);
        var before = byName(previous.fields);
        for (String name : before.keySet()) {
            if (!now.containsKey(name)) {
                return Optional.of("lost a field " + name);
            }
        }
        for (String name : now.keySet()) {
            if (!before.containsKey(name)) {
                return Optional.of("gained a field " + name);
            }
        }
        for (Member field : fields) {
            Member old = before.get(field.name());
            if (!field.descriptor().equals(old.descriptor())) {
                return Optional.of("changed the type of field " + field.name());
            }
            if (field.flags() != old.flags()) {
                return Optional.of("changed the modifiers of field " + field.name());
            }
            var difference = attributeDifference(field.attributes(), old.attributes(), "field " + field.name());
            if (difference.isPresent()) {
                return difference;
            }
        }
        if (!List.copyOf(now.keySet()).equals(List.copyOf(before.keySet()))) {
            return Optional.of("reordered its fields");
        }
        return Optional.empty();
    }

    private Optional<String> methodDifference(ClassFingerprint previous) {
        Set<String> lostNames = new LinkedHashSet<>();
        Set<String> gainedNames = new LinkedHashSet<>();
        previous.methods.forEach((key, m) -> {
            if (!methods.containsKey(key)) {
                lostNames.add(m.name());
            }
        });
        methods.forEach((key, m) -> {
            if (!previous.methods.containsKey(key)) {
                gainedNames.add(m.name());
            }
        });
        for (String name : gainedNames) {
            if (lostNames.contains(name)) {
                return Optional.of("changed the signature of " + describe(name));
            }
        }
        if (!gainedNames.isEmpty()) {
            return Optional.of("gained " + article(gainedNames.iterator().next()));
        }
        if (!lostNames.isEmpty()) {
            return Optional.of("lost " + article(lostNames.iterator().next()));
        }
        for (var entry : methods.entrySet()) {
            Member m = entry.getValue();
            Member old = previous.methods.get(entry.getKey());
            if (m.flags() != old.flags()) {
                return Optional.of("changed the modifiers of " + describe(m.name()));
            }
            var difference = attributeDifference(m.attributes(), old.attributes(), describe(m.name()));
            if (difference.isPresent()) {
                return difference;
            }
        }
        return Optional.empty();
    }

    private static Optional<String> attributeDifference(SequencedMap<String, String> now,
            SequencedMap<String, String> before, String on) {
        Set<String> names = new LinkedHashSet<>(before.keySet());
        names.addAll(now.keySet());
        for (String name : names) {
            if (!Objects.equals(now.get(name), before.get(name))) {
                return Optional.of(ANNOTATION_ATTRIBUTES.contains(name)
                        ? "changed an annotation on " + on
                        : "changed the " + name + " attribute of " + on);
            }
        }
        return Optional.empty();
    }

    private static String describe(String method) {
        return switch (method) {
            case INIT -> "a constructor";
            case CLINIT -> "its static initializer";
            default -> method + "()";
        };
    }

    private static String article(String method) {
        return switch (method) {
            case INIT -> "a constructor";
            case CLINIT -> "a static initializer";
            default -> "a method " + method;
        };
    }

    private static List<Member> instanceFields(List<Member> fields) {
        return fields.stream().filter(field -> (field.flags() & ClassFile.ACC_STATIC) == 0).toList();
    }

    private static Set<String> lambdas(Set<String> methodKeys, Set<String> names) {
        return methodKeys.stream().filter(key -> key.startsWith(LAMBDA) && names.contains(key.substring(0,
                key.indexOf('('))))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** The lambda method names a {@code $deserializeLambda$} compares the serialized form with. */
    private static Set<String> lambdaNames(CodeModel code) {
        Set<String> names = new LinkedHashSet<>();
        for (CodeElement element : code) {
            if (element instanceof ConstantInstruction constant && constant.constantValue() instanceof String name
                    && name.startsWith(LAMBDA)) {
                names.add(name);
            }
        }
        return names;
    }

    private static SequencedMap<String, Member> byName(List<Member> members) {
        var map = new LinkedHashMap<String, Member>();
        members.forEach(m -> map.put(m.name(), m));
        return map;
    }

    private static SequencedMap<String, String> attributes(List<Attribute<?>> attributes, String thisClass) {
        var rendered = new TreeMap<String, String>();
        for (Attribute<?> attribute : attributes) {
            String name = attribute.attributeName().stringValue();
            if (NOT_SHAPE.contains(name)) {
                continue;
            }
            String value = attribute(attribute, thisClass);
            if (value != null) {
                rendered.merge(name, value, (a, b) -> a + "|" + b);
            }
        }
        return Collections.unmodifiableSequencedMap(new LinkedHashMap<>(rendered));
    }

    /** One attribute, symbolically; {@code null} when it says nothing of this class (filtered inner classes). */
    private static String attribute(Attribute<?> attribute, String thisClass) {
        return switch (attribute) {
            case RuntimeVisibleAnnotationsAttribute a -> annotations(a.annotations());
            case RuntimeInvisibleAnnotationsAttribute a -> annotations(a.annotations());
            case RuntimeVisibleParameterAnnotationsAttribute a ->
                    a.parameterAnnotations().stream().map(ClassFingerprint::annotations).toList().toString();
            case RuntimeInvisibleParameterAnnotationsAttribute a ->
                    a.parameterAnnotations().stream().map(ClassFingerprint::annotations).toList().toString();
            case RuntimeVisibleTypeAnnotationsAttribute a -> typeAnnotations(a.annotations());
            case RuntimeInvisibleTypeAnnotationsAttribute a -> typeAnnotations(a.annotations());
            case SignatureAttribute a -> a.signature().stringValue();
            case InnerClassesAttribute a -> innerClasses(a, thisClass);
            case EnclosingMethodAttribute a -> a.enclosingClass().asInternalName() + "."
                    + a.enclosingMethodName().map(e -> e.stringValue()).orElse("")
                    + a.enclosingMethodType().map(e -> e.stringValue()).orElse("");
            case NestHostAttribute a -> a.nestHost().asInternalName();
            case NestMembersAttribute a -> names(a.nestMembers());
            case PermittedSubclassesAttribute a -> names(a.permittedSubclasses());
            case RecordAttribute a -> a.components().stream()
                    .map(c -> c.name().stringValue() + ":" + c.descriptor().stringValue()
                            + attributes(c.attributes(), null))
                    .toList().toString();
            case ExceptionsAttribute a -> names(a.exceptions());
            case MethodParametersAttribute a -> a.parameters().stream()
                    .map(p -> p.name().map(e -> e.stringValue()).orElse("") + "/" + p.flagsMask())
                    .toList().toString();
            case AnnotationDefaultAttribute a -> value(a.defaultValue());
            case ConstantValueAttribute a -> String.valueOf(a.constant().constantValue());
            default -> attribute.attributeName().stringValue();
        };
    }

    private static String innerClasses(InnerClassesAttribute attribute, String thisClass) {
        var own = attribute.classes().stream()
                .filter(c -> c.innerClass().asInternalName().equals(thisClass)
                        || c.outerClass().map(o -> o.asInternalName().equals(thisClass)).orElse(false))
                .map(c -> c.innerClass().asInternalName() + "<" + c.outerClass().map(ClassEntry::asInternalName)
                        .orElse("") + ":" + c.innerName().map(e -> e.stringValue()).orElse("") + "/"
                        + c.flagsMask())
                .sorted()
                .toList();
        return own.isEmpty() ? null : own.toString();
    }

    private static String names(List<ClassEntry> entries) {
        return entries.stream().map(ClassEntry::asInternalName).toList().toString();
    }

    private static String annotations(List<Annotation> annotations) {
        return annotations.stream().map(ClassFingerprint::annotation).toList().toString();
    }

    private static String typeAnnotations(List<TypeAnnotation> annotations) {
        return annotations.stream()
                .map(t -> t.targetInfo().targetType() + ":" + t.targetPath() + ":" + annotation(t.annotation()))
                .toList().toString();
    }

    private static String annotation(Annotation annotation) {
        return "@" + annotation.className().stringValue() + annotation.elements().stream()
                .map(e -> e.name().stringValue() + "=" + value(e.value()))
                .collect(Collectors.joining(",", "(", ")"));
    }

    private static String value(AnnotationValue value) {
        return switch (value) {
            case AnnotationValue.OfConstant c -> value.tag() + String.valueOf(c.resolvedValue());
            case AnnotationValue.OfClass c -> "class " + c.className().stringValue();
            case AnnotationValue.OfEnum e -> e.className().stringValue() + "." + e.constantName().stringValue();
            case AnnotationValue.OfAnnotation a -> annotation(a.annotation());
            case AnnotationValue.OfArray a -> a.values().stream().map(ClassFingerprint::value).toList().toString();
        };
    }

    /** The code of a method: instructions with resolved operands, labels numbered in order, the debug tables. */
    private static String code(CodeModel code) {
        Map<Label, Integer> labels = new IdentityHashMap<>();
        for (CodeElement element : code) {
            if (element instanceof LabelTarget target) {
                labels.putIfAbsent(target.label(), labels.size());
            }
        }
        List<String> lines = new ArrayList<>();
        for (CodeElement element : code) {
            String line = element(element, labels);
            if (line != null) {
                lines.add(line);
            }
        }
        return String.join("\n", lines);
    }

    private static String element(CodeElement element, Map<Label, Integer> labels) {
        return switch (element) {
            case LabelTarget t -> label(t.label(), labels) + ":";
            case LineNumber l -> "line " + l.line();
            case LocalVariable v -> "var " + v.slot() + " " + v.name().stringValue() + " " + v.type().stringValue()
                    + " " + label(v.startScope(), labels) + "-" + label(v.endScope(), labels);
            case LocalVariableType v -> "vartype " + v.slot() + " " + v.name().stringValue() + " "
                    + v.signature().stringValue() + " " + label(v.startScope(), labels) + "-"
                    + label(v.endScope(), labels);
            case ExceptionCatch c -> "catch " + label(c.tryStart(), labels) + "-" + label(c.tryEnd(), labels) + " "
                    + label(c.handler(), labels) + " " + c.catchType().map(ClassEntry::asInternalName).orElse("*");
            case Instruction i -> instruction(i, labels);
            default -> null;
        };
    }

    private static String instruction(Instruction instruction, Map<Label, Integer> labels) {
        return switch (instruction) {
            case BranchInstruction b -> b.opcode().name().replace("_W", "") + " " + label(b.target(), labels);
            case ConstantInstruction c -> "const " + c.typeKind() + " " + c.constantValue();
            case FieldInstruction f -> f.opcode().name() + " " + f.owner().asInternalName() + "." + f.name()
                    .stringValue() + ":" + f.type().stringValue();
            case InvokeInstruction i -> i.opcode().name() + " " + i.owner().asInternalName() + "." + i.name()
                    .stringValue() + i.type().stringValue() + (i.isInterface() ? " itf" : "");
            case InvokeDynamicInstruction d -> "indy " + d.bootstrapMethod() + " " + d.bootstrapArgs() + " "
                    + d.name().stringValue() + d.type().stringValue();
            case LoadInstruction l -> "load " + l.typeKind() + " " + l.slot();
            case StoreInstruction s -> "store " + s.typeKind() + " " + s.slot();
            case IncrementInstruction i -> "inc " + i.slot() + " " + i.constant();
            case LookupSwitchInstruction s -> "lookupswitch " + s.cases().stream()
                    .map(c -> c.caseValue() + ":" + label(c.target(), labels)).toList()
                    + " default:" + label(s.defaultTarget(), labels);
            case TableSwitchInstruction s -> "tableswitch " + s.lowValue() + "-" + s.highValue() + " "
                    + s.cases().stream().map(c -> c.caseValue() + ":" + label(c.target(), labels)).toList()
                    + " default:" + label(s.defaultTarget(), labels);
            case NewObjectInstruction n -> "new " + n.className().asInternalName();
            case NewPrimitiveArrayInstruction n -> "newarray " + n.typeKind();
            case NewReferenceArrayInstruction n -> "anewarray " + n.componentType().asInternalName();
            case NewMultiArrayInstruction n -> "multianewarray " + n.arrayType().asInternalName() + " "
                    + n.dimensions();
            case TypeCheckInstruction t -> t.opcode().name() + " " + t.type().asInternalName();
            default -> instruction.opcode().name();
        };
    }

    private static String label(Label label, Map<Label, Integer> labels) {
        Integer index = labels.get(label);
        return index == null ? "L?" : "L" + index;
    }

    private static String dotted(ClassEntry entry) {
        return entry.asInternalName().replace('/', '.');
    }
}
