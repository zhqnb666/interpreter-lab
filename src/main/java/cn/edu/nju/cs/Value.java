package cn.edu.nju.cs;

import java.util.ArrayList;
import java.util.List;

public final class Value {
    private final Type type;
    private final Object payload;
    private final boolean decimalLiteral;
    private final Type nullTypeHint;

    private Value(Type type, Object payload, boolean decimalLiteral, Type nullTypeHint) {
        this.type = type;
        this.payload = payload;
        this.decimalLiteral = decimalLiteral;
        this.nullTypeHint = nullTypeHint;
    }

    public static Value nullValue() {
        return new Value(null, null, false, null);
    }

    public static Value nullValue(Type arrayType) {
        if (arrayType == null || !arrayType.isArray()) {
            throw new RuntimeEvalException("null type hint must be an array type");
        }
        return new Value(null, null, false, arrayType);
    }

    public static Value ofInt(int value) {
        return new Value(Type.INT, value, false, null);
    }

    public static Value ofDecimalLiteral(int value) {
        return new Value(Type.INT, value, true, null);
    }

    public static Value ofChar(int value) {
        return new Value(Type.CHAR, (int) (byte) value, false, null);
    }

    public static Value ofBoolean(boolean value) {
        return new Value(Type.BOOLEAN, value, false, null);
    }

    public static Value ofString(String value) {
        if (value == null) {
            throw new RuntimeEvalException("String value cannot be null");
        }
        return new Value(Type.STRING, value, false, null);
    }

    public static Value ofArray(MiniJavaArray array) {
        if (array == null) {
            throw new RuntimeEvalException("Array value cannot be null");
        }
        return new Value(array.type(), array, false, null);
    }

    public static Value voidValue() {
        return new Value(Type.VOID, null, false, null);
    }

    public Value clearDecimalLiteral() {
        if (!decimalLiteral) {
            return this;
        }
        return new Value(type, payload, false, nullTypeHint);
    }

    public boolean isNull() {
        return type == null;
    }

    public Type type() {
        if (isNull()) {
            throw new RuntimeEvalException("null has no intrinsic type");
        }
        return type;
    }

    public boolean isDecimalLiteral() {
        return decimalLiteral;
    }

    public boolean hasNullTypeHint() {
        return isNull() && nullTypeHint != null;
    }

    public Type nullTypeHint() {
        if (!hasNullTypeHint()) {
            throw new RuntimeEvalException("null has no type hint");
        }
        return nullTypeHint;
    }

    public boolean isArray() {
        return !isNull() && type.isArray();
    }

    public boolean isVoid() {
        return !isNull() && type.equals(Type.VOID);
    }

    public boolean isInt() {
        return !isNull() && type.equals(Type.INT);
    }

    public boolean isChar() {
        return !isNull() && type.equals(Type.CHAR);
    }

    public boolean isBoolean() {
        return !isNull() && type.equals(Type.BOOLEAN);
    }

    public boolean isString() {
        return !isNull() && type.equals(Type.STRING);
    }

    public boolean isIntegral() {
        return !isNull() && type.isIntegralScalar();
    }

    public int asInt() {
        if (!isInt()) {
            throw new RuntimeEvalException("Expected int, but got " + printableType());
        }
        return (Integer) payload;
    }

    public int asSignedCharInt() {
        if (!isChar()) {
            throw new RuntimeEvalException("Expected char, but got " + printableType());
        }
        return (Integer) payload;
    }

    public boolean asBoolean() {
        if (!isBoolean()) {
            throw new RuntimeEvalException("Expected boolean, but got " + printableType());
        }
        return (Boolean) payload;
    }

    public String asString() {
        if (!isString()) {
            throw new RuntimeEvalException("Expected string, but got " + printableType());
        }
        return (String) payload;
    }

    public MiniJavaArray asArray() {
        if (!isArray()) {
            throw new RuntimeEvalException("Expected array, but got " + printableType());
        }
        return (MiniJavaArray) payload;
    }

    public int toIntWithPromotion() {
        if (isInt()) {
            return asInt();
        }
        if (isChar()) {
            return asSignedCharInt();
        }
        throw new RuntimeEvalException("Expected integral type, but got " + printableType());
    }

    public Value castTo(Type target) {
        if (target.equals(Type.INT)) {
            return Value.ofInt(toIntWithPromotion());
        }
        if (target.equals(Type.CHAR)) {
            return Value.ofChar(toIntWithPromotion());
        }
        throw new RuntimeEvalException("Unsupported cast target: " + target.keyword());
    }

    public String toOutputString() {
        if (isNull()) {
            return "null";
        }
        if (isVoid()) {
            throw new RuntimeEvalException("void value cannot be printed");
        }
        if (isInt()) {
            return String.valueOf(asInt());
        }
        if (isChar()) {
            return String.valueOf((char) (asSignedCharInt() & 0xFF));
        }
        if (isBoolean()) {
            return String.valueOf(asBoolean());
        }
        if (isString()) {
            return asString();
        }
        if (isArray()) {
            return arrayToString(asArray());
        }
        throw new RuntimeEvalException("Unsupported value type: " + printableType());
    }

    private String arrayToString(MiniJavaArray array) {
        List<String> parts = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            Value elem = array.get(i);
            parts.add(elem.toOutputString());
        }
        return "[" + String.join(", ", parts) + "]";
    }

    private String printableType() {
        return isNull() ? "null" : type.keyword();
    }

    @Override
    public String toString() {
        return toOutputString();
    }
}
