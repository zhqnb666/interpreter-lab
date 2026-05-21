package cn.edu.nju.cs;

public final class TypeSystem {
    private static ClassRegistry classRegistry;

    private TypeSystem() {
    }

    public static void setClassRegistry(ClassRegistry registry) {
        classRegistry = registry;
    }

    public static ClassRegistry classRegistry() {
        return classRegistry;
    }

    public static Value defaultValue(Type type) {
        if (type.isArray() || type.isClass()) {
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

    public static Value coerceForAssignment(Type target, ExprResult source) {
        return coerceForAssignment(target, source.value(), source.staticType());
    }

    public static Value coerceForAssignment(Type target, Value source) {
        return coerceForAssignment(target, source, source.type());
    }

    private static Value coerceForAssignment(Type target, Value source, Type sourceStaticType) {
        if (target.isClass()) {
            if (source.isNull()) {
                // Typed null carries its declared type; it may only flow into a slot of
                // its own class or a supertype (else `B b = (A)null` would silently
                // succeed, contradicting Java's static-type rules).
                Type sourceType = sourceStaticType != null ? sourceStaticType : source.type();
                if (sourceType != null && sourceType.isClass()) {
                    if (!classRegistry.isSubclassOf(sourceType.className(), target.className())) {
                        throw new RuntimeEvalException(
                                "Cannot assign " + sourceType.keyword() + " to " + target.keyword());
                    }
                }
                return Value.typedNull(target);
            }
            if (source.isClassInstance()) {
                String srcCls = sourceStaticType != null && sourceStaticType.isClass()
                        ? sourceStaticType.className()
                        : source.asClassInstance().realClassName();
                if (!classRegistry.isSubclassOf(srcCls, target.className())) {
                    throw new RuntimeEvalException(
                            "Cannot assign " + srcCls + " to " + target.keyword());
                }
                return source;
            }
            throw new RuntimeEvalException(
                    "Cannot assign " + source.typeName() + " to " + target.keyword());
        }

        if (target.isArray()) {
            if (source.isNull()) {
                Type sourceType = sourceStaticType != null ? sourceStaticType : source.type();
                if (sourceType != null && !sourceType.equals(target)) {
                    throw new RuntimeEvalException("Cannot assign " + sourceType.keyword() + " to " + target.keyword());
                }
                return Value.typedNull(target);
            }
            Type sourceType = sourceStaticType != null ? sourceStaticType : source.type();
            if (source.isArray() && target.equals(sourceType)) {
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

    public static int methodConversionCost(Type target, ExprResult arg) {
        if (target.isClass()) {
            Value v = arg.value();
            if (v.isNull()) {
                if (v.type() == null) {
                    // Lab 3 §1.3 lists `null → Array` as an implicit conversion, and Lab 3
                    // §1.4 Note 2 caps the conversion-count at 0 or 1. Lab 4 adds class
                    // params on the same footing — so untyped null fitting any reference
                    // slot is a 1-cost conversion, matching the array path below.
                    return 1;
                }
                if (v.type().isClass()) {
                    if (v.type().className().equals(target.className())) {
                        return 0;
                    }
                    return classRegistry.isSubclassOf(v.type().className(), target.className())
                            ? 1
                            : -1;
                }
                return -1;
            }
            if (v.isClassInstance()) {
                String srcCls = arg.staticType() != null && arg.staticType().isClass()
                        ? arg.staticType().className()
                        : v.asClassInstance().realClassName();
                if (srcCls.equals(target.className())) {
                    return 0;
                }
                return classRegistry.isSubclassOf(srcCls, target.className()) ? 1 : -1;
            }
            return -1;
        }
        return methodConversionCost(target, arg.value());
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

    public static Value coerceForMethodParam(Type target, ExprResult arg) {
        int cost = methodConversionCost(target, arg);
        if (cost < 0) {
            throw new RuntimeEvalException(
                    "Incompatible argument type: " + arg.value().typeName() + " -> " + target.keyword());
        }
        Value v = arg.value();
        if (v.isNull()) {
            if (target.isArray() || target.isClass()) {
                return Value.typedNull(target);
            }
            return v;
        }
        if (target.equals(Type.INT) && v.isChar()) {
            return Value.ofInt(v.asSignedCharInt());
        }
        return v.clearDecimalLiteral();
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
