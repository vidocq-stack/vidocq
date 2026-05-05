package io.vidocq.mansart.data.core;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.List;

/**
 * M7-25 — generates a hidden class that implements a {@code @Repository} interface, replacing
 * the {@code java.lang.reflect.Proxy} fallback used by {@link RuntimeRepositoryProxy}. Each
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
 * repo interface's package. No global classloader entry, no leak.
 */
final class RuntimeRepositoryClassGenerator {

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

            // private final MansartCallback cb;
            cb.withField("cb", callbackCd, ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL);

            // public <init>(MansartCallback cb) { super(); this.cb = cb; }
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

        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(repoInterface, MethodHandles.lookup());
        Class<?> implClass = lookup.defineHiddenClass(bytes, true).lookupClass();
        return new GeneratedRepo(implClass, List.copyOf(methods));
    }

    private static void collectAbstractMethods(Class<?> itf, List<Method> out) {
        for (Method m : itf.getDeclaredMethods()) {
            if (m.isDefault()) continue;
            if (m.getDeclaringClass() == Object.class) continue;
            // Avoid duplicate signature when an interface re-declares an inherited method.
            boolean dup = false;
            for (Method o : out) {
                if (o.getName().equals(m.getName())
                        && java.util.Arrays.equals(o.getParameterTypes(), m.getParameterTypes())) {
                    dup = true; break;
                }
            }
            if (!dup) out.add(m);
        }
        for (Class<?> parent : itf.getInterfaces()) collectAbstractMethods(parent, out);
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
            code.iload(slot).invokestatic(ConstantDescs.CD_Boolean, "valueOf",
                    MethodTypeDesc.of(ConstantDescs.CD_Boolean, ConstantDescs.CD_boolean));
            return 1;
        }
        if (paramType == byte.class) {
            code.iload(slot).invokestatic(ConstantDescs.CD_Byte, "valueOf",
                    MethodTypeDesc.of(ConstantDescs.CD_Byte, ConstantDescs.CD_byte));
            return 1;
        }
        if (paramType == short.class) {
            code.iload(slot).invokestatic(ConstantDescs.CD_Short, "valueOf",
                    MethodTypeDesc.of(ConstantDescs.CD_Short, ConstantDescs.CD_short));
            return 1;
        }
        if (paramType == int.class) {
            code.iload(slot).invokestatic(ConstantDescs.CD_Integer, "valueOf",
                    MethodTypeDesc.of(ConstantDescs.CD_Integer, ConstantDescs.CD_int));
            return 1;
        }
        if (paramType == long.class) {
            code.lload(slot).invokestatic(ConstantDescs.CD_Long, "valueOf",
                    MethodTypeDesc.of(ConstantDescs.CD_Long, ConstantDescs.CD_long));
            return 2;
        }
        if (paramType == float.class) {
            code.fload(slot).invokestatic(ConstantDescs.CD_Float, "valueOf",
                    MethodTypeDesc.of(ConstantDescs.CD_Float, ConstantDescs.CD_float));
            return 1;
        }
        if (paramType == double.class) {
            code.dload(slot).invokestatic(ConstantDescs.CD_Double, "valueOf",
                    MethodTypeDesc.of(ConstantDescs.CD_Double, ConstantDescs.CD_double));
            return 2;
        }
        if (paramType == char.class) {
            code.iload(slot).invokestatic(ConstantDescs.CD_Character, "valueOf",
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
            case -1 -> { code.iconst_m1(); return; }
            case 0  -> { code.iconst_0(); return; }
            case 1  -> { code.iconst_1(); return; }
            case 2  -> { code.iconst_2(); return; }
            case 3  -> { code.iconst_3(); return; }
            case 4  -> { code.iconst_4(); return; }
            case 5  -> { code.iconst_5(); return; }
        }
        if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) code.bipush(v);
        else if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) code.sipush(v);
        else code.ldc(v);
    }
}
