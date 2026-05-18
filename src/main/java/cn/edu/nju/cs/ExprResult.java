package cn.edu.nju.cs;

public record ExprResult(Value value, Type staticType) {

    public static ExprResult of(Value value) {
        return new ExprResult(value, value.type());
    }

    public static ExprResult of(Value value, Type staticType) {
        return new ExprResult(value, staticType);
    }

    public Value valueNonVoid() {
        return value.requireNonVoid();
    }

    public boolean isNull() {
        return value.isNull();
    }
}
