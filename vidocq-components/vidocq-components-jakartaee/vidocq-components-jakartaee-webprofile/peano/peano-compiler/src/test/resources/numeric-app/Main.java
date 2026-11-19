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
package demo;
import jakarta.el.*;
import java.math.*;
import java.nio.file.*;
import java.util.*;

public class Main {
    static class Context extends ELContext {
        Object a,b;
        List<String> effects;
        public ELResolver getELResolver() { return new ELResolver() {
            public Object getValue(ELContext c,Object base,Object p) {
                if (effects!=null) effects.add("read:"+p);
                if (base==null && ("a".equals(p)||"b".equals(p))) { c.setPropertyResolved(base,p); return "a".equals(p)?a:b; }
                return null;
            }
            public Class<?> getType(ELContext c,Object b,Object p) { return Object.class; }
            public boolean isReadOnly(ELContext c,Object b,Object p) { return true; }
            public void setValue(ELContext c,Object b,Object p,Object v) {}
            public Class<?> getCommonPropertyType(ELContext c,Object b) { return String.class; }
        }; }
        public VariableMapper getVariableMapper() { return null; }
        public FunctionMapper getFunctionMapper() { return null; }
        public <T> T convertToType(Object value,Class<T> type) {
            if (effects!=null) effects.add("convert:"+type.getName());
            if (type==Object.class || type.isInstance(value)) return (T)value;
            try {
                if (type==String.class) return (T)(value==null ? "" : value.toString());
                if (type==Boolean.class || type==boolean.class) {
                    if (value==null || "".equals(value)) return (T)Boolean.FALSE;
                    if (value instanceof String s) return (T)Boolean.valueOf(s);
                    throw new ELException("Cannot coerce to "+type.getTypeName());
                }
                if (type.isEnum() && value instanceof String s) return s.isEmpty() ? null : (T)Enum.valueOf((Class)type,s);
                if (value==null || "".equals(value)) value=0L;
                if (value instanceof Character c) value=(short)c.charValue();
                if (value instanceof String s) {
                    if (type==Long.class) return (T)Long.valueOf(s);
                    if (type==Double.class) return (T)Double.valueOf(s);
                    if (type==BigInteger.class) return (T)new BigInteger(s);
                    if (type==BigDecimal.class) return (T)new BigDecimal(s);
                }
                if (value instanceof Number n) {
                    if (type==Long.class) return (T)Long.valueOf(n.longValue());
                    if (type==Double.class) return (T)Double.valueOf(n.doubleValue());
                    if (type==BigInteger.class) return (T)(n instanceof BigDecimal d ? d.toBigInteger() : BigInteger.valueOf(n.longValue()));
                    if (type==BigDecimal.class) return (T)(n instanceof BigInteger i ? new BigDecimal(i) : new BigDecimal(n.doubleValue()));
                }
                throw new ELException("Cannot coerce to "+type.getTypeName());
            } catch (ELException failure) { throw failure; }
            catch (RuntimeException failure) { throw new ELException("Cannot coerce to "+type.getTypeName(),failure); }
        }
    }
    record Token(int value) {}
    record AnyComparable(int result) implements Comparable<Object> {
        public int compareTo(Object other) { return result; }
    }
    static Object decode(String wire) {
        String[] parts=wire.split(":",2); String text=parts[1];
        return switch(parts[0]) {
            case "null"->null; case "Long"->Long.valueOf(text); case "Double"->Double.valueOf(text);
            case "BigInteger"->new BigInteger(text); case "BigDecimal"->new BigDecimal(text);
            case "Byte"->Byte.valueOf(text); case "Short"->Short.valueOf(text); case "Integer"->Integer.valueOf(text);
            case "Float"->Float.valueOf(text); case "Character"->Character.valueOf((char)Integer.parseInt(text));
            case "Boolean"->Boolean.valueOf(text);
            case "RoundingMode"->RoundingMode.valueOf(text);
            case "ArrayList"->new ArrayList<>(List.of(Integer.parseInt(text.substring(1,text.length()-1))));
            case "LinkedHashMap"->new LinkedHashMap<>(Map.of("x",Integer.parseInt(text.substring(3,text.length()-1))));
            case "LocalDate"->java.time.LocalDate.parse(text);
            case "Token"->new Token(Integer.parseInt(text)); case "AnyComparable"->new AnyComparable(Integer.parseInt(text));
            case "String"->new String(Base64.getDecoder().decode(text),java.nio.charset.StandardCharsets.UTF_8);
            default->throw new AssertionError(wire);
        };
    }
    static String outcome(java.util.function.Supplier<Object> operation) {
        try {
            Object value=operation.get();
            return value==null ? "null" : value instanceof Class<?> c ? c.getName() : value.getClass().getName()+":"+value;
        } catch (RuntimeException failure) {
            return "error:"+failure.getClass().getName()+":"+(failure.getCause()==null ? "none" : failure.getCause().getClass().getName());
        }
    }
    public static void main(String[] args) throws Exception {
        if (ModuleLayer.boot().findModule("io.vidocq.peano.core").isPresent()
                || ModuleLayer.boot().findModule("io.vidocq.peano.compiler").isPresent()) throw new AssertionError("Build modules resolved");
        Path root=Path.of(args[0]);
        var values=Files.readAllLines(root.resolve("values.txt")).stream().map(Main::decode).toList();
        var context=new Context();
        ValueExpression[] expressions={new N0(Object.class),new N1(Object.class),new N2(Object.class),
                new N3(Object.class),new N4(Object.class),new N5(Object.class)};
        int count=0;
        for (String row:Files.readAllLines(root.resolve("cases.txt"))) {
            String[] columns=row.split("\t",-1);
            int op=Integer.parseInt(columns[0]); context.a=values.get(Integer.parseInt(columns[1])); context.b=values.get(Integer.parseInt(columns[2]));
            String value=outcome(()->expressions[op].getValue(context)),type=outcome(()->expressions[op].getType(context));
            if (!value.equals(columns[3]) || !type.equals(columns[4]))
                throw new AssertionError("Numeric case "+row+"; inputs "+context.a+" / "+context.b+"; actual "+value+" | "+type);
            count++;
        }
        if (args[1].equals("numeric")) {
        var nullBig=new Context() {
            public <T> T convertToType(Object value,Class<T> type) {
                if (type==BigDecimal.class) { effects.add("convert:"+type.getName()); return null; }
                return super.convertToType(value,type);
            }
        };
        nullBig.a=new BigDecimal("7.0"); nullBig.b=2L; nullBig.effects=new ArrayList<>();
        try { expressions[0].getValue(nullBig); throw new AssertionError("Null decimal accepted"); }
        catch (ELException expected) {
            if (!(expected.getCause() instanceof NullPointerException)
                    || !nullBig.effects.equals(List.of("read:a","read:b","convert:java.math.BigDecimal","convert:java.math.BigDecimal")))
                throw new AssertionError("Decimal conversion effects: "+nullBig.effects,expected);
        }
        var nullDouble=new Context() {
            public <T> T convertToType(Object value,Class<T> type) {
                if (type==Double.class) { effects.add("convert:"+type.getName()); return null; }
                return super.convertToType(value,type);
            }
        };
        nullDouble.a=1.5; nullDouble.b=2L; nullDouble.effects=new ArrayList<>();
        try { expressions[0].getValue(nullDouble); throw new AssertionError("Null double accepted"); }
        catch (ELException expected) {
            if (!(expected.getCause() instanceof NullPointerException)
                    || !nullDouble.effects.equals(List.of("read:a","read:b","convert:java.lang.Double")))
                throw new AssertionError("Double conversion effects: "+nullDouble.effects,expected);
        }
        }
        System.out.println("PEANO_NUMERIC_CASES:"+count);
    }
}

