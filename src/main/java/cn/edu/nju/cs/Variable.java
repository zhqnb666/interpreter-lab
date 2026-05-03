package cn.edu.nju.cs;

public final class Variable {
    private final Type declaredType;
    private Value value;

    public Variable(Type declaredType, Value value) {
        this.declaredType = declaredType;
        this.value = value;
    }

    public Type declaredType() {
        return declaredType;
    }

    public Value value() {
        return value;
    }

    public void setValue(Value value) {
        this.value = value;
    }
}
