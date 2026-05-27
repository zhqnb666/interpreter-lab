package cn.edu.nju.cs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ClassRegistry {

    public record FieldOwner(String className, ClassDecl.FieldDecl field) {
    }

    public record MethodDispatch(String declaringClass, MethodDecl method) {
    }

    private final Map<String, ClassDecl> classes = new HashMap<>();

    public void register(ClassDecl decl) {
        if (classes.containsKey(decl.name())) {
            throw new RuntimeEvalException("Duplicate class: " + decl.name());
        }
        classes.put(decl.name(), decl);
    }

    public boolean exists(String name) {
        return classes.containsKey(name);
    }

    public ClassDecl get(String name) {
        ClassDecl c = classes.get(name);
        if (c == null) {
            throw new RuntimeEvalException("Unknown class: " + name);
        }
        return c;
    }

    /** Validate that every declared parent exists and there are no cycles. */
    public void validateInheritance() {
        for (ClassDecl c : classes.values()) {
            if (c.parentName() != null && !classes.containsKey(c.parentName())) {
                throw new RuntimeEvalException(
                        "Class " + c.name() + " extends unknown class " + c.parentName());
            }
        }
        for (ClassDecl c : classes.values()) {
            Set<String> seen = new HashSet<>();
            String cur = c.name();
            while (cur != null) {
                if (!seen.add(cur)) {
                    throw new RuntimeEvalException("Inheritance cycle through class " + c.name());
                }
                cur = classes.get(cur).parentName();
            }
        }
    }

    /** Return all ancestors of `name`, starting from name itself, up to the root. */
    public List<String> chain(String name) {
        List<String> result = new ArrayList<>();
        String cur = name;
        while (cur != null) {
            result.add(cur);
            cur = classes.get(cur).parentName();
        }
        return result;
    }

    public boolean isSubclassOf(String sub, String sup) {
        if (sub == null || sup == null) {
            return false;
        }
        String cur = sub;
        while (cur != null) {
            if (cur.equals(sup)) {
                return true;
            }
            cur = classes.get(cur).parentName();
        }
        return false;
    }

    public boolean inSameHierarchy(String a, String b) {
        return isSubclassOf(a, b) || isSubclassOf(b, a);
    }

    /**
     * Walk from startClass up the chain and return the first class that declares a field
     * named `fieldName`, along with the field itself. Returns null if no class in the chain
     * declares it.
     */
    public FieldOwner findField(String startClass, String fieldName) {
        for (String cls : chain(startClass)) {
            for (ClassDecl.FieldDecl f : classes.get(cls).fields()) {
                if (f.name().equals(fieldName)) {
                    return new FieldOwner(cls, f);
                }
            }
        }
        return null;
    }

    /**
     * Collect all methods named {@code methodName} visible from {@code startClass} for
     * Phase 1 overload resolution. Walks from {@code startClass} up the chain.
     *
     * Dedupe rule (same as `MethodRegistry.register`): a method is shadowed by an
     * earlier-added one ONLY when name, parameter types, AND return type all match.
     * Same-(name,params) but different-return-type methods are both kept, so the call
     * site reports ambiguity (Lab 3 §1.4 Note 1).
     *
     * Within a single class, duplicate-signature methods are all kept without
     * deduplication — the call site will report ambiguity when they are invoked.
     *
     * Across the chain, a subclass with the same parameter types shadows the
     * parent's method(s) regardless of return type, so only the most-derived
     * occurrence(s) are kept. Unrelated overloads remain visible in parallel.
     */
    public List<MethodDecl> collectMethods(String startClass, String methodName) {
        List<MethodDecl> result = new ArrayList<>();
        // Sigs that a strictly more-derived class has already contributed.
        // Shadowing is keyed by parameter-type list only (not return type).
        Set<List<Type>> shadowedByDerived = new HashSet<>();
        for (String cls : chain(startClass)) {
            List<List<Type>> thisClassSigs = new ArrayList<>();
            for (MethodDecl m : classes.get(cls).methods()) {
                if (!m.name().equals(methodName)) {
                    continue;
                }
                List<Type> sig = canonicalSig(m);
                if (shadowedByDerived.contains(sig)) {
                    continue; // hidden by a more-derived class
                }
                result.add(m);
                thisClassSigs.add(sig);
            }
            shadowedByDerived.addAll(thisClassSigs);
        }
        return result;
    }

    private static List<Type> canonicalSig(MethodDecl m) {
        List<Type> sig = new ArrayList<>();
        for (MethodDecl.Parameter p : m.parameters()) {
            sig.add(p.type());
        }
        return sig;
    }

    /**
     * Allocate the per-class field layers for a `new` of {@code realClassName}, with each
     * field set to its type-default. Field initializers are NOT executed here — they belong
     * to constructor Step 3.
     */
    public Map<String, Map<String, Value>> buildInitialFieldLayers(String realClassName) {
        Map<String, Map<String, Value>> layers = new LinkedHashMap<>();
        List<String> ch = chain(realClassName);
        // Allocate from root to leaf so that layers map iterates in inheritance order.
        for (int i = ch.size() - 1; i >= 0; i--) {
            String cls = ch.get(i);
            Map<String, Value> layer = ClassInstance.newLayer();
            for (ClassDecl.FieldDecl f : classes.get(cls).fields()) {
                layer.put(f.name(), TypeSystem.defaultValue(f.type()));
            }
            layers.put(cls, layer);
        }
        return layers;
    }

    public ConstructorDecl resolveConstructor(String className, List<ExprResult> args) {
        ClassDecl decl = get(className);
        return MethodRegistry.selectBestOverload(
                decl.constructors(),
                ConstructorDecl::parameters,
                args,
                className + " constructor");
    }

    /**
     * Phase 2 of method dispatch: walk from {@code startClass} up the inheritance chain
     * and return the first class that declares a method with name {@code methodName} and
     * exactly the parameter types of {@code sigParams}.
     */
    public MethodDispatch findOverrideForSignature(
            String startClass, String methodName, List<MethodDecl.Parameter> sigParams) {
        for (String cls : chain(startClass)) {
            for (MethodDecl m : classes.get(cls).methods()) {
                if (m.name().equals(methodName) && sameParamTypes(m.parameters(), sigParams)) {
                    return new MethodDispatch(cls, m);
                }
            }
        }
        return null;
    }

    private static boolean sameParamTypes(
            List<MethodDecl.Parameter> a, List<MethodDecl.Parameter> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).type().equals(b.get(i).type())) {
                return false;
            }
        }
        return true;
    }
}
