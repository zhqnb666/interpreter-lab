package cn.edu.nju.cs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class MethodRegistry {
    private final Map<String, List<MethodDecl>> methodsByName = new HashMap<>();

    public void register(MethodDecl method) {
        // Allow all declarations; same-(name,params) top-level methods are all kept.
        // Ambiguity is deferred to the call site (Lab 3 §1.4 Note 1 — tied
        // conversion-count → "Ambiguous call").
        methodsByName.computeIfAbsent(method.name(), k -> new ArrayList<>()).add(method);
    }

    public MethodDecl resolveEntryMain() {
        MethodDecl intMain = null;
        boolean hasVoidMain = false;

        for (MethodDecl m : methodsByName.getOrDefault("main", List.of())) {
            if (!m.parameters().isEmpty()) {
                continue;
            }
            if (m.returnType().equals(Type.INT)) {
                intMain = m;
            } else if (m.returnType().equals(Type.VOID)) {
                hasVoidMain = true;
            }
        }

        if (intMain == null) {
            throw new RuntimeEvalException("Missing int main()");
        }
        if (hasVoidMain) {
            throw new RuntimeEvalException("Ambiguous entry method main()");
        }
        return intMain;
    }

    public boolean hasOverloadsOf(String name) {
        return methodsByName.containsKey(name);
    }

    public List<MethodDecl> overloadsOf(String name) {
        return methodsByName.getOrDefault(name, List.of());
    }

    public MethodDecl resolveCall(String name, List<ExprResult> args) {
        List<MethodDecl> candidates = overloadsOf(name);
        return selectBestOverload(candidates, MethodDecl::parameters, args, name);
    }

    /**
     * Generic best-match overload resolution by per-argument
     * {@link TypeSystem#methodConversionCost} (lower total cost wins; ties report ambiguity;
     * no match throws). Used for top-level methods, class methods, and class constructors.
     */
    public static <T> T selectBestOverload(
            List<T> candidates,
            Function<T, List<MethodDecl.Parameter>> paramsOf,
            List<ExprResult> args,
            String contextName) {
        T best = null;
        int bestCost = Integer.MAX_VALUE;
        boolean tie = false;
        for (T candidate : candidates) {
            List<MethodDecl.Parameter> params = paramsOf.apply(candidate);
            if (params.size() != args.size()) {
                continue;
            }
            int totalCost = 0;
            boolean ok = true;
            for (int i = 0; i < args.size(); i++) {
                int cost = TypeSystem.methodConversionCost(params.get(i).type(), args.get(i));
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
            throw new RuntimeEvalException("No matching method: " + contextName);
        }
        if (tie) {
            throw new RuntimeEvalException("Ambiguous call: " + contextName);
        }
        return best;
    }
}
