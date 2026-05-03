package cn.edu.nju.cs;

public final class Value {

    private enum Kind { CONCRETE, NULL }

    private final Kind kind;
    private final Type type;
    private final Object payload;
    private final boolean decimalLiteral;

    private Value(Kind kind, Type type, Object payload, boolean decimalLiteral) {
        this.kind = kind;
        this.type = type;
        this.payload = payload;
        this.decimalLiteral = decimalLiteral;
    }

    public static Value untypedNull() {
        return new Value(Kind.NULL, null, null, false);
    }

    public static Value typedNull(Type slotType) {
        if (slotType == null || !slotType.isArray()) {
            throw new RuntimeEvalException("typed null must carry an array (or class) type");
        }
        return new Value(Kind.NULL, slotType, null, false);
    }

    public static Value ofInt(int value) {
        return new Value(Kind.CONCRETE, Type.INT, value, false);
    }

    public static Value ofDecimalLiteral(int value) {
        return new Value(Kind.CONCRETE, Type.INT, value, true);
    }

    public static Value ofChar(int value) {
        return new Value(Kind.CONCRETE, Type.CHAR, (int) (byte) value, false);
    }

    public static Value ofBoolean(boolean value) {
        return new Value(Kind.CONCRETE, Type.BOOLEAN, value, false);
    }

    public static Value ofString(String value) {
        if (value == null) {
            throw new RuntimeEvalException("String value cannot be null");
        }
        return new Value(Kind.CONCRETE, Type.STRING, value, false);
    }

    public static Value ofArray(MiniJavaArray array) {
        if (array == null) {
            throw new RuntimeEvalException("Array value cannot be null");
        }
        return new Value(Kind.CONCRETE, array.type(), array, false);
    }

    public static Value voidValue() {
        return new Value(Kind.CONCRETE, Type.VOID, null, false);
    }

    public Value clearDecimalLiteral() {
        if (!decimalLiteral) {
            return this;
        }
        return new Value(kind, type, payload, false);
    }

    public boolean isNull() {
        return kind == Kind.NULL;
    }

    public boolean isVoid() {
        return kind == Kind.CONCRETE && type.equals(Type.VOID);
    }

    public Type type() {
        return type;
    }

    public boolean isDecimalLiteral() {
        return decimalLiteral;
    }

    public boolean isArray() {
        return kind == Kind.CONCRETE && type.isArray();
    }

    public boolean isInt() {
        return kind == Kind.CONCRETE && type.equals(Type.INT);
    }

    public boolean isChar() {
        return kind == Kind.CONCRETE && type.equals(Type.CHAR);
    }

    public boolean isBoolean() {
        return kind == Kind.CONCRETE && type.equals(Type.BOOLEAN);
    }

    public boolean isString() {
        return kind == Kind.CONCRETE && type.equals(Type.STRING);
    }

    public boolean isIntegral() {
        return kind == Kind.CONCRETE && type.isIntegral();
    }

    public int asInt() {
        if (!isInt()) {
            throw new RuntimeEvalException("Expected int, but got " + typeName());
        }
        return (Integer) payload;
    }

    public int asSignedCharInt() {
        if (!isChar()) {
            throw new RuntimeEvalException("Expected char, but got " + typeName());
        }
        return (Integer) payload;
    }

    public boolean asBoolean() {
        if (!isBoolean()) {
            throw new RuntimeEvalException("Expected boolean, but got " + typeName());
        }
        return (Boolean) payload;
    }

    public String asString() {
        if (!isString()) {
            throw new RuntimeEvalException("Expected string, but got " + typeName());
        }
        return (String) payload;
    }

    public MiniJavaArray asArray() {
        if (!isArray()) {
            throw new RuntimeEvalException("Expected array, but got " + typeName());
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
        throw new RuntimeEvalException("Expected integral type, but got " + typeName());
    }

    public Value requireNonVoid() {
        if (isVoid()) {
            throw new RuntimeEvalException("void value is not allowed here");
        }
        return this;
    }

    public boolean requireBoolean() {
        if (!isBoolean()) {
            throw new RuntimeEvalException("Expected boolean");
        }
        return asBoolean();
    }

    public int requireIntegral() {
        if (!isIntegral()) {
            throw new RuntimeEvalException("Expected integral type");
        }
        return toIntWithPromotion();
    }

    public int requireIndex() {
        if (isInt()) {
            return asInt();
        }
        if (isChar()) {
            return asSignedCharInt();
        }
        throw new RuntimeEvalException("Array index must be int");
    }

    public boolean isStringConcatOperand() {
        return isString() || isInt() || isChar() || isBoolean();
    }

    public static boolean equalsValue(Value left, Value right) {
        if (left.isNull() && right.isNull()) {
            if (left.type() != null && right.type() != null
                    && !left.type().equals(right.type())) {
                throw new RuntimeEvalException("Incompatible array types for equality");
            }
            return true;
        }
        if (left.isNull() || right.isNull()) {
            Value nullValue = left.isNull() ? left : right;
            Value nonNull = left.isNull() ? right : left;
            if (nonNull.isArray()) {
                if (nullValue.type() != null && !nullValue.type().equals(nonNull.type())) {
                    throw new RuntimeEvalException("Incompatible array types for equality");
                }
                return false;
            }
            throw new RuntimeEvalException("Incompatible types for equality");
        }
        if (left.isIntegral() && right.isIntegral()) {
            return left.toIntWithPromotion() == right.toIntWithPromotion();
        }
        if (left.isBoolean() && right.isBoolean()) {
            return left.asBoolean() == right.asBoolean();
        }
        if (left.isString() && right.isString()) {
            return left.asString().equals(right.asString());
        }
        if (left.isArray() && right.isArray()) {
            if (!left.type().equals(right.type())) {
                throw new RuntimeEvalException("Incompatible array types for equality");
            }
            return left.asArray() == right.asArray();
        }
        throw new RuntimeEvalException("Incompatible types for equality");
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
            return asArray().toOutputString();
        }
        throw new RuntimeEvalException("Unsupported value type: " + typeName());
    }

    public String typeName() {
        if (isNull()) {
            return "null";
        }
        return type.keyword();
    }

    @Override
    public String toString() {
        if (isVoid()) {
            return "<void>";
        }
        return toOutputString();
    }
}
