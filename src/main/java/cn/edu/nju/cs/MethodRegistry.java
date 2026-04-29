package cn.edu.nju.cs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class MethodRegistry {
    private final Map<String, List<MethodDecl>> methodsByName = new HashMap<>();
    private final Map<MethodSignature, MethodDecl> methodsBySignature = new HashMap<>();

    public void register(MethodDecl method) {
        if (methodsBySignature.containsKey(method.signature())) {
            throw new RuntimeEvalException("Method redefinition: " + method.signature());
        }
        methodsBySignature.put(method.signature(), method);
        methodsByName.computeIfAbsent(method.name(), k -> new ArrayList<>()).add(method);
    }

    public MethodDecl resolveEntryMain() {
        List<MethodDecl> mains = methodsByName.getOrDefault("main", List.of());
        MethodDecl intMain = null;
        MethodDecl voidMain = null;
        for (MethodDecl m : mains) {
            if (!m.parameters().isEmpty()) {
                continue;
            }
            if (m.returnType().equals(Type.INT)) {
                intMain = m;
            } else if (m.returnType().equals(Type.VOID)) {
                voidMain = m;
            }
        }
        if (intMain != null && voidMain != null) {
            throw new RuntimeEvalException("Ambiguous entry method main()");
        }
        if (intMain == null) {
            throw new RuntimeEvalException("Missing int main()");
        }
        return intMain;
    }

    public MethodDecl resolveCall(String name, List<Value> args) {
        List<MethodDecl> candidates = methodsByName.getOrDefault(name, List.of());
        MethodDecl best = null;
        int bestCost = Integer.MAX_VALUE;
        boolean tie = false;

        for (MethodDecl candidate : candidates) {
            if (candidate.parameters().size() != args.size()) {
                continue;
            }
            int totalCost = 0;
            boolean ok = true;
            for (int i = 0; i < args.size(); i++) {
                Type paramType = candidate.parameters().get(i).type();
                int cost = TypeSystem.methodConversionCost(paramType, args.get(i));
                if (cost < 0) {
                    ok = false;
                    break;
                }
                totalCost += cost;
            }
            if (!ok) {
                continue;
            }
            if (totalCost < bestCost) {
                best = candidate;
                bestCost = totalCost;
                tie = false;
            } else if (totalCost == bestCost) {
                tie = true;
            }
        }

        if (best == null) {
            throw new RuntimeEvalException("No matching method: " + name);
        }
        if (tie) {
            throw new RuntimeEvalException("Ambiguous call: " + name);
        }
        return best;
    }
}
