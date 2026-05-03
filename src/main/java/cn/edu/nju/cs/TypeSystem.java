package cn.edu.nju.cs;

public final class TypeSystem {
    private TypeSystem() {
    }

    public static Value defaultValue(Type type) {
        if (type.isArray()) {
            return Value.typedNull(type);
        }
        if (type.equals(Type.INT)) {
            return Value.ofInt(0);
        }
        if (type.equals(Type.CHAR)) {
            return Value.ofChar(0);
        }
        if (type.equals(Type.BOOLEAN)) {
            return Value.ofBoolean(false);
        }
        if (type.equals(Type.STRING)) {
            return Value.ofString("");
        }
        throw new RuntimeEvalException("Unsupported default value type: " + type.keyword());
    }

    public static Value coerceForAssignment(Type target, Value source) {
        if (target.isArray()) {
            if (source.isNull()) {
                if (source.type() != null && !source.type().equals(target)) {
                    throw new RuntimeEvalException("Cannot assign " + source.typeName() + " to " + target.keyword());
                }
                return Value.typedNull(target);
            }
            if (source.isArray() && source.type().equals(target)) {
                return source.clearDecimalLiteral();
            }
            throw new RuntimeEvalException("Cannot assign " + source.typeName() + " to " + target.keyword());
        }

        if (target.equals(Type.INT)) {
            if (source.isInt()) {
                return Value.ofInt(source.asInt());
            }
            if (source.isChar()) {
                return Value.ofInt(source.asSignedCharInt());
            }
            throw new RuntimeEvalException("Cannot assign " + source.typeName() + " to int");
        }

        if (target.equals(Type.CHAR)) {
            if (source.isChar()) {
                return Value.ofChar(source.asSignedCharInt());
            }
            if (source.isInt() && source.isDecimalLiteral() && inCharRange(source.asInt())) {
                return Value.ofChar(source.asInt());
            }
            throw new RuntimeEvalException("Cannot assign " + source.typeName() + " to char");
        }

        if (target.equals(Type.BOOLEAN)) {
            if (source.isBoolean()) {
                return Value.ofBoolean(source.asBoolean());
            }
            throw new RuntimeEvalException("Cannot assign " + source.typeName() + " to boolean");
        }

        if (target.equals(Type.STRING)) {
            if (source.isString()) {
                return Value.ofString(source.asString());
            }
            throw new RuntimeEvalException("Cannot assign " + source.typeName() + " to string");
        }

        throw new RuntimeEvalException("Unsupported assignment target type: " + target.keyword());
    }

    public static int methodConversionCost(Type target, Value arg) {
        if (arg.isNull()) {
            if (!target.isArray()) {
                return -1;
            }
            if (arg.type() != null) {
                return arg.type().equals(target) ? 0 : -1;
            }
            return 1;
        }
        if (target.isArray()) {
            if (arg.isArray() && arg.type().equals(target)) {
                return 0;
            }
            return -1;
        }
        if (target.equals(Type.INT)) {
            if (arg.isInt()) {
                return 0;
            }
            if (arg.isChar()) {
                return 1;
            }
            return -1;
        }
        if (target.equals(Type.CHAR)) {
            return arg.isChar() ? 0 : -1;
        }
        if (target.equals(Type.BOOLEAN)) {
            return arg.isBoolean() ? 0 : -1;
        }
        if (target.equals(Type.STRING)) {
            return arg.isString() ? 0 : -1;
        }
        return -1;
    }

    public static Value coerceForMethodParam(Type target, Value arg) {
        int cost = methodConversionCost(target, arg);
        if (cost < 0) {
            throw new RuntimeEvalException("Incompatible argument type: " + arg.typeName() + " -> " + target.keyword());
        }
        if (arg.isNull()) {
            return Value.typedNull(target);
        }
        if (target.equals(Type.INT) && arg.isChar()) {
            return Value.ofInt(arg.asSignedCharInt());
        }
        return arg.clearDecimalLiteral();
    }

    public static boolean inCharRange(int value) {
        return value >= -128 && value <= 127;
    }

    public static Value integralResult(Type targetType, int value) {
        if (targetType.equals(Type.INT)) {
            return Value.ofInt(value);
        }
        if (targetType.equals(Type.CHAR)) {
            return Value.ofChar(value);
        }
        throw new RuntimeEvalException("Integral assignment target must be int or char");
    }
}
