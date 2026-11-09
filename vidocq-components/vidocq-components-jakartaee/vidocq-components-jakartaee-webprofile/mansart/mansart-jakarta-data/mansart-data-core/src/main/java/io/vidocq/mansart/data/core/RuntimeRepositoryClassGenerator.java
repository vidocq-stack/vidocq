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
package io.vidocq.mansart.data.core;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

/**
 * M7-25 — generates a hidden class that implements a {@code @Repository} interface for
 * {@link RuntimeRepositoryProxy} (there is no {@code java.lang.reflect.Proxy} fallback). Each
 * abstract method on the interface becomes a tiny stub that:
 *
 * <ol>
 *   <li>packs its arguments into {@code Object[]} (boxing primitives),</li>
 *   <li>calls {@code callback.dispatch(methodIndex, args)},</li>
 *   <li>unboxes / casts the result to the declared return type.</li>
 * </ol>
 *
 * <p>Default methods are NOT overridden; the JVM dispatches them through the standard
 * default-method shim. {@code java.lang.Object} methods (toString/equals/hashCode) are also
 * left alone — the generated class inherits them from {@link Object}.
 *
 * <p>The class is loaded via {@link MethodHandles.Lookup#defineHiddenClass}, scoped to the
 * repo interface's package. No global classloader entry, no leak. When the repository lives in
 * another module or loader than Mansart, where that lookup is not allowed, the class is defined in a
 * dedicated child loader of the repository's, which requires a public, exported repository.
 */
final class RuntimeRepositoryClassGenerator {

    private static final String VALUE_OF = "valueOf";

    private RuntimeRepositoryClassGenerator() {}

    /** Returned by {@link #generate} so callers can match a Method to its index. */
    record GeneratedRepo(Class<?> implClass, List<Method> methodsByIndex) {}

    static GeneratedRepo generate(Class<?> repoInterface) throws ReflectiveOperationException {
        // Collect abstract methods (skip default + Object). Order matters — the index is
        // baked into the bytecode and used by MansartCallback at call-time.
        List<Method> methods = new java.util.ArrayList<>();
        collectAbstractMethods(repoInterface, methods);

        ClassDesc thisCd = ClassDesc.of(repoInterface.getPackageName(),
                repoInterface.getSimpleName() + "$$MansartImpl");
        ClassDesc itfCd       = ClassDesc.ofDescriptor(repoInterface.descriptorString());
        ClassDesc callbackCd  = ClassDesc.ofDescriptor(MansartCallback.class.descriptorString());

        byte[] bytes = ClassFile.of().build(thisCd, cb -> {
            cb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SYNTHETIC);
            cb.withInterfaceSymbols(itfCd);

            // The callback every method delegates to, held in a private final field named "cb".
            cb.withField("cb", callbackCd, ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL);

            // A public constructor taking that callback: it calls Object's constructor, then stores the callback.
            cb.withMethodBody(ConstantDescs.INIT_NAME,
                    MethodTypeDesc.of(ConstantDescs.CD_void, callbackCd),
                    ClassFile.ACC_PUBLIC,
                    code -> code
                            .aload(0)
                            .invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME,
                                    MethodTypeDesc.of(ConstantDescs.CD_void))
                            .aload(0)
                            .aload(1)
                            .putfield(thisCd, "cb", callbackCd)
                            .return_());

            for (int i = 0; i < methods.size(); i++) {
                emitMethod(cb, thisCd, callbackCd, methods.get(i), i);
            }
        });

        return new GeneratedRepo(define(repoInterface, thisCd, bytes), List.copyOf(methods));
    }

    /**
     * Defines the generated class. Next to the repository, as a hidden class, when Mansart can get a
     * full-privilege lookup in its package (same module: any repository on the class path, in the same loader).
     * Otherwise, the repository lives in another module or loader (a named application module, a web application
     * loader): the lookup then lacks the module mode a hidden class needs, so the class is defined in a loader of
     * its own, a child of the repository's, and implements the repository through its public API.
     */
    private static Class<?> define(Class<?> repoInterface, ClassDesc thisCd, byte[] bytes)
            throws ReflectiveOperationException {
        MethodHandles.Lookup lookup;
        try {
            lookup = MethodHandles.privateLookupIn(repoInterface, MethodHandles.lookup());
        } catch (IllegalAccessException e) {
            throw new IllegalAccessException(OpensHint.message(repoInterface));
        }
        if ((lookup.lookupModes() & MethodHandles.Lookup.MODULE) != 0) {
            return lookup.defineHiddenClass(bytes, true).lookupClass();
        }
        requirePublicApi(repoInterface);
        return new GeneratedLoader(repoInterface.getClassLoader())
                .define(thisCd.packageName().isEmpty() ? thisCd.displayName()
                        : thisCd.packageName() + "." + thisCd.displayName(), bytes);
    }

    /** A repository in another module can only be implemented through its exported, public types. */
    private static void requirePublicApi(Class<?> repoInterface) throws IllegalAccessException {
        Class<?> notPublic = firstNotPublic(repoInterface);
        if (notPublic != null) {
            throw new IllegalAccessException(notPublic.getName() + " is not public: Mansart, in another module or"
                    + " loader, cannot implement it");
        }
        if (!repoInterface.getModule().isExported(repoInterface.getPackageName())) {
            throw new IllegalAccessException("package " + repoInterface.getPackageName() + " of module "
                    + repoInterface.getModule() + " is not exported: Mansart cannot implement "
                    + repoInterface.getName());
        }
    }

    /** The first type, from the repository outwards, that is not public; {@code null} if all are. */
    private static Class<?> firstNotPublic(Class<?> type) {
        Class<?> current = type;
        while (current != null) {
            if (!Modifier.isPublic(current.getModifiers())) {
                return current;
            }
            current = current.getEnclosingClass();
        }
        return null;
    }

    /** One generated class, one loader: nothing is kept once the repository is unreachable. */
    private static final class GeneratedLoader extends ClassLoader {

        GeneratedLoader(ClassLoader parent) {
            super(parent);
        }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    /**
     * The methods the class implements: the abstract ones of the repository's public methods, the rule the annotation
     * processor applies. A default body that implements an inherited method hides it, and so does a bridge javac adds;
     * static, private and synthetic methods are not the repository's.
     */
    private static void collectAbstractMethods(Class<?> itf, List<Method> out) {
        for (Method m : itf.getMethods()) {
            // Avoid duplicate signature when an interface re-declares an inherited method.
            if (Modifier.isAbstract(m.getModifiers()) && !isCollected(m, out)) out.add(m);
        }
    }

    /** Whether {@code out} already holds a method with the name and parameter types of {@code m}. */
    private static boolean isCollected(Method m, List<Method> out) {
        for (Method o : out) {
            if (o.getName().equals(m.getName())
                    && java.util.Arrays.equals(o.getParameterTypes(), m.getParameterTypes())) {
                return true;
            }
        }
        return false;
    }

    private static void emitMethod(java.lang.classfile.ClassBuilder cb, ClassDesc thisCd,
                                   ClassDesc callbackCd, Method m, int methodIndex) {
        Class<?>[] paramTypes = m.getParameterTypes();
        Class<?> retType = m.getReturnType();
        ClassDesc[] paramDescs = new ClassDesc[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            paramDescs[i] = ClassDesc.ofDescriptor(paramTypes[i].descriptorString());
        }
        ClassDesc retDesc = ClassDesc.ofDescriptor(retType.descriptorString());
        MethodTypeDesc mtd = MethodTypeDesc.of(retDesc, paramDescs);

        int flags = ClassFile.ACC_PUBLIC;
        cb.withMethodBody(m.getName(), mtd, flags, code -> {
            // 1) this.cb -> stack
            code.aload(0).getfield(thisCd, "cb", callbackCd);

            // 2) push methodIndex (int)
            pushInt(code, methodIndex);

            // 3) Object[] args = new Object[N]
            pushInt(code, paramTypes.length);
            code.anewarray(ConstantDescs.CD_Object);

            // 4) for each param: dup, index, load arg (boxing if primitive), aastore
            int slot = 1;
            for (int i = 0; i < paramTypes.length; i++) {
                code.dup();
                pushInt(code, i);
                slot += loadAndBox(code, paramTypes[i], slot);
                code.aastore();
            }

            // 5) MansartCallback.dispatch(int, Object[]) -> Object
            code.invokevirtual(callbackCd, "dispatch",
                    MethodTypeDesc.of(ConstantDescs.CD_Object,
                            ConstantDescs.CD_int, ConstantDescs.CD_Object.arrayType()));

            // 6) unbox / cast / void
            emitReturn(code, retType, retDesc);
        });
    }

    /** Loads parameter {@code slot} of type {@code paramType} onto the stack, boxing if
     *  primitive. Returns the number of local-variable slots consumed (2 for long/double, 1
     *  for everything else). */
    private static int loadAndBox(java.lang.classfile.CodeBuilder code, Class<?> paramType, int slot) {
        if (paramType == boolean.class) {
            code.iload(slot).invokestatic(ConstantDescs.CD_Boolean, VALUE_OF,
                    MethodTypeDesc.of(ConstantDescs.CD_Boolean, ConstantDescs.CD_boolean));
            return 1;
        }
        if (paramType == byte.class) {
            code.iload(slot).invokestatic(ConstantDescs.CD_Byte, VALUE_OF,
                    MethodTypeDesc.of(ConstantDescs.CD_Byte, ConstantDescs.CD_byte));
            return 1;
        }
        if (paramType == short.class) {
            code.iload(slot).invokestatic(ConstantDescs.CD_Short, VALUE_OF,
                    MethodTypeDesc.of(ConstantDescs.CD_Short, ConstantDescs.CD_short));
            return 1;
        }
        if (paramType == int.class) {
            code.iload(slot).invokestatic(ConstantDescs.CD_Integer, VALUE_OF,
                    MethodTypeDesc.of(ConstantDescs.CD_Integer, ConstantDescs.CD_int));
            return 1;
        }
        if (paramType == long.class) {
            code.lload(slot).invokestatic(ConstantDescs.CD_Long, VALUE_OF,
                    MethodTypeDesc.of(ConstantDescs.CD_Long, ConstantDescs.CD_long));
            return 2;
        }
        if (paramType == float.class) {
            code.fload(slot).invokestatic(ConstantDescs.CD_Float, VALUE_OF,
                    MethodTypeDesc.of(ConstantDescs.CD_Float, ConstantDescs.CD_float));
            return 1;
        }
        if (paramType == double.class) {
            code.dload(slot).invokestatic(ConstantDescs.CD_Double, VALUE_OF,
                    MethodTypeDesc.of(ConstantDescs.CD_Double, ConstantDescs.CD_double));
            return 2;
        }
        if (paramType == char.class) {
            code.iload(slot).invokestatic(ConstantDescs.CD_Character, VALUE_OF,
                    MethodTypeDesc.of(ConstantDescs.CD_Character, ConstantDescs.CD_char));
            return 1;
        }
        // Reference type
        code.aload(slot);
        return 1;
    }

    private static void emitReturn(java.lang.classfile.CodeBuilder code, Class<?> retType, ClassDesc retDesc) {
        if (retType == void.class) {
            code.pop().return_();
            return;
        }
        if (retType.isPrimitive()) {
            // Unbox: ((Boxed) result).xxxValue()
            ClassDesc boxed = primitiveBoxed(retType);
            String unboxMethod = unboxMethodName(retType);
            code.checkcast(boxed)
                .invokevirtual(boxed, unboxMethod, MethodTypeDesc.of(retDesc));
            switch (retType.getName()) {
                case "long"   -> code.lreturn();
                case "float"  -> code.freturn();
                case "double" -> code.dreturn();
                default       -> code.ireturn(); // boolean/byte/short/int/char
            }
            return;
        }
        // Reference: cast to declared return type, areturn.
        code.checkcast(retDesc).areturn();
    }

    private static ClassDesc primitiveBoxed(Class<?> primitive) {
        if (primitive == boolean.class) return ConstantDescs.CD_Boolean;
        if (primitive == byte.class)    return ConstantDescs.CD_Byte;
        if (primitive == short.class)   return ConstantDescs.CD_Short;
        if (primitive == int.class)     return ConstantDescs.CD_Integer;
        if (primitive == long.class)    return ConstantDescs.CD_Long;
        if (primitive == float.class)   return ConstantDescs.CD_Float;
        if (primitive == double.class)  return ConstantDescs.CD_Double;
        if (primitive == char.class)    return ConstantDescs.CD_Character;
        throw new IllegalArgumentException("not a primitive: " + primitive);
    }

    private static String unboxMethodName(Class<?> primitive) {
        if (primitive == boolean.class) return "booleanValue";
        if (primitive == byte.class)    return "byteValue";
        if (primitive == short.class)   return "shortValue";
        if (primitive == int.class)     return "intValue";
        if (primitive == long.class)    return "longValue";
        if (primitive == float.class)   return "floatValue";
        if (primitive == double.class)  return "doubleValue";
        if (primitive == char.class)    return "charValue";
        throw new IllegalArgumentException("not a primitive: " + primitive);
    }

    private static void pushInt(java.lang.classfile.CodeBuilder code, int v) {
        switch (v) {
            case -1 -> code.iconst_m1();
            case 0  -> code.iconst_0();
            case 1  -> code.iconst_1();
            case 2  -> code.iconst_2();
            case 3  -> code.iconst_3();
            case 4  -> code.iconst_4();
            case 5  -> code.iconst_5();
            default -> pushWideInt(code, v);
        }
    }

    private static void pushWideInt(java.lang.classfile.CodeBuilder code, int v) {
        if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) code.bipush(v);
        else if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) code.sipush(v);
        else code.ldc(v);
    }
}
