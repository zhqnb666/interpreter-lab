package cn.edu.nju.cs;

import java.util.HashMap;
import java.util.Map;

public final class ScopeFrame {
    private final Map<String, Variable> symbols = new HashMap<>();

    public void declare(String name, Type type, Value value) {
        if (symbols.containsKey(name)) {
            throw new RuntimeEvalException("Variable already declared: " + name);
        }
        symbols.put(name, new Variable(type, value));
    }

    public Variable get(String name) {
        return symbols.get(name);
    }
}
