package cn.edu.nju.cs;

import java.util.Objects;

public final class Type {
    public enum Primitive {
        INT("int"),
        CHAR("char"),
        BOOLEAN("boolean"),
        STRING("string"),
        VOID("void");

        private final String keyword;

        Primitive(String keyword) {
            this.keyword = keyword;
        }

        public String keyword() {
            return keyword;
        }

        public static Primitive fromKeyword(String text) {
            return switch (text) {
                case "int" -> INT;
                case "char" -> CHAR;
                case "boolean" -> BOOLEAN;
                case "string" -> STRING;
                case "void" -> VOID;
                default -> throw new RuntimeEvalException("Unknown type: " + text);
            };
        }
    }

    public static final Type INT = new Type(Primitive.INT, 0);
    public static final Type CHAR = new Type(Primitive.CHAR, 0);
    public static final Type BOOLEAN = new Type(Primitive.BOOLEAN, 0);
    public static final Type STRING = new Type(Primitive.STRING, 0);
    public static final Type VOID = new Type(Primitive.VOID, 0);

    private final Primitive primitive;
    private final int arrayDepth;

    private Type(Primitive primitive, int arrayDepth) {
        if (arrayDepth < 0) {
            throw new IllegalArgumentException("arrayDepth must be non-negative");
        }
        if (primitive == Primitive.VOID && arrayDepth > 0) {
            throw new IllegalArgumentException("void[] is not allowed");
        }
        this.primitive = primitive;
        this.arrayDepth = arrayDepth;
    }

    public static Type fromKeyword(String keyword) {
        return switch (Primitive.fromKeyword(keyword)) {
            case INT -> INT;
            case CHAR -> CHAR;
            case BOOLEAN -> BOOLEAN;
            case STRING -> STRING;
            case VOID -> VOID;
        };
    }

    public Type arrayOf() {
        if (isVoid()) {
            throw new RuntimeEvalException("void array is not allowed");
        }
        return new Type(primitive, arrayDepth + 1);
    }

    public Type componentType() {
        if (!isArray()) {
            throw new RuntimeEvalException("Not an array type: " + keyword());
        }
        return new Type(primitive, arrayDepth - 1);
    }

    public Primitive primitive() {
        return primitive;
    }

    public int arrayDepth() {
        return arrayDepth;
    }

    public boolean isArray() {
        return arrayDepth > 0;
    }

    public boolean isVoid() {
        return primitive == Primitive.VOID && arrayDepth == 0;
    }

    public boolean isIntegral() {
        return arrayDepth == 0 && (primitive == Primitive.INT || primitive == Primitive.CHAR);
    }

    public String keyword() {
        StringBuilder sb = new StringBuilder(primitive.keyword());
        for (int i = 0; i < arrayDepth; i++) {
            sb.append("[]");
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return keyword();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Type type)) {
            return false;
        }
        return arrayDepth == type.arrayDepth && primitive == type.primitive;
    }

    @Override
    public int hashCode() {
        return Objects.hash(primitive, arrayDepth);
    }
}
