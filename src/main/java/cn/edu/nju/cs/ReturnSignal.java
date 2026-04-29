package cn.edu.nju.cs;

public final class ReturnSignal extends RuntimeException {
    private final Value value;
    private final boolean hasValue;

    public ReturnSignal() {
        this.value = null;
        this.hasValue = false;
    }

    public ReturnSignal(Value value) {
        this.value = value;
        this.hasValue = true;
    }

    public boolean hasValue() {
        return hasValue;
    }

    public Value value() {
        return value;
    }
}
