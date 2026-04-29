package cn.edu.nju.cs;

import java.util.HashMap;
import java.util.Map;

public final class ScopeFrame {
    private final Map<String, MiniJavaObject> symbols = new HashMap<>();

    public void declare(String name, Type type, Value value) {
        if (symbols.containsKey(name)) {
            throw new RuntimeEvalException("Variable already declared: " + name);
        }
        symbols.put(name, new MiniJavaObject(type, value));
    }

    public MiniJavaObject get(String name) {
        return symbols.get(name);
    }
}
