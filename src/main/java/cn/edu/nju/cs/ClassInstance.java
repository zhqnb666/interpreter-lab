package cn.edu.nju.cs;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ClassInstance {
    private final String realClassName;
    /** className -> fieldName -> Value (one map per class layer in the inheritance chain). */
    private final Map<String, Map<String, Value>> fieldsByClass;

    public ClassInstance(String realClassName, Map<String, Map<String, Value>> fieldsByClass) {
        this.realClassName = realClassName;
        this.fieldsByClass = fieldsByClass;
    }

    public String realClassName() {
        return realClassName;
    }

    public Map<String, Map<String, Value>> fieldsByClass() {
        return fieldsByClass;
    }

    public Value getField(String ownerClass, String fieldName) {
        Map<String, Value> layer = fieldsByClass.get(ownerClass);
        if (layer == null) {
            throw new RuntimeEvalException(
                    "No layer for class " + ownerClass + " in instance of " + realClassName);
        }
        if (!layer.containsKey(fieldName)) {
            throw new RuntimeEvalException(
                    "No field " + fieldName + " in class " + ownerClass);
        }
        return layer.get(fieldName);
    }

    public void setField(String ownerClass, String fieldName, Value value) {
        Map<String, Value> layer = fieldsByClass.get(ownerClass);
        if (layer == null) {
            throw new RuntimeEvalException(
                    "No layer for class " + ownerClass + " in instance of " + realClassName);
        }
        if (!layer.containsKey(fieldName)) {
            throw new RuntimeEvalException(
                    "No field " + fieldName + " in class " + ownerClass);
        }
        layer.put(fieldName, value);
    }

    static Map<String, Value> newLayer() {
        return new LinkedHashMap<>();
    }
}
