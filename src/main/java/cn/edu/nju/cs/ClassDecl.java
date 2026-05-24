package cn.edu.nju.cs;

import java.util.ArrayList;
import java.util.List;

public final class ClassDecl {

    public record FieldDecl(
            Type type,
            String name,
            MiniJavaParser.VariableDeclaratorContext declarator) {
    }

    private final String name;
    private final String parentName;
    private final List<FieldDecl> fields = new ArrayList<>();
    private final List<MethodDecl> methods = new ArrayList<>();
    private final List<ConstructorDecl> constructors = new ArrayList<>();

    public ClassDecl(String name, String parentName) {
        this.name = name;
        this.parentName = parentName;
    }

    public String name() {
        return name;
    }

    public String parentName() {
        return parentName;
    }

    public List<FieldDecl> fields() {
        return fields;
    }

    public List<MethodDecl> methods() {
        return methods;
    }

    public List<ConstructorDecl> constructors() {
        return constructors;
    }

    public void addField(FieldDecl field) {
        for (FieldDecl existing : fields) {
            if (existing.name().equals(field.name())) {
                throw new RuntimeEvalException(
                        "Duplicate field " + field.name() + " in class " + name);
            }
        }
        fields.add(field);
    }

    public void addMethod(MethodDecl method) {
        // Spec is silent on intra-class duplicate-signature method declarations.
        // We accept them here; ClassRegistry.collectMethods filters duplicates
        // at dispatch time (first declared wins; later same-sig methods silently
        // shadowed — matches the reference implementation and OpenCase tests).
        methods.add(method);
    }

    public void addConstructor(ConstructorDecl ctor) {
        // Constructors have no collectMethods-style dedupe stage, so apply the
        // same "first wins" policy here directly.
        for (ConstructorDecl existing : constructors) {
            if (sameParamTypes(existing, ctor)) {
                return;
            }
        }
        constructors.add(ctor);
    }

    private static boolean sameParamTypes(MethodDecl a, MethodDecl b) {
        if (a.parameters().size() != b.parameters().size()) {
            return false;
        }
        for (int i = 0; i < a.parameters().size(); i++) {
            if (!a.parameters().get(i).type().equals(b.parameters().get(i).type())) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameParamTypes(ConstructorDecl a, ConstructorDecl b) {
        if (a.parameters().size() != b.parameters().size()) {
            return false;
        }
        for (int i = 0; i < a.parameters().size(); i++) {
            if (!a.parameters().get(i).type().equals(b.parameters().get(i).type())) {
                return false;
            }
        }
        return true;
    }
}
