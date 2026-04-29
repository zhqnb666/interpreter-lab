package cn.edu.nju.cs;

import java.util.ArrayList;
import java.util.List;

public final class MiniJavaArray {
    private final Type type;
    private final List<Value> elements;

    public MiniJavaArray(Type type, int size) {
        if (!type.isArray()) {
            throw new RuntimeEvalException("Not an array type: " + type.keyword());
        }
        if (size < 0) {
            throw new RuntimeEvalException("Negative array size");
        }
        this.type = type;
        this.elements = new ArrayList<>(size);
        Type elemType = type.componentType();
        Value defaultElem = TypeSystem.defaultValue(elemType);
        for (int i = 0; i < size; i++) {
            elements.add(defaultElem);
        }
    }

    public MiniJavaArray(Type type, List<Value> elements) {
        if (!type.isArray()) {
            throw new RuntimeEvalException("Not an array type: " + type.keyword());
        }
        this.type = type;
        this.elements = new ArrayList<>(elements);
    }

    public Type type() {
        return type;
    }

    public int length() {
        return elements.size();
    }

    public Value get(int index) {
        rangeCheck(index);
        return elements.get(index);
    }

    public void set(int index, Value value) {
        rangeCheck(index);
        elements.set(index, value);
    }

    private void rangeCheck(int index) {
        if (index < 0 || index >= elements.size()) {
            throw new RuntimeEvalException("Array out-of-bounds");
        }
    }
}
