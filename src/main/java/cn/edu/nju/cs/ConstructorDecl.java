package cn.edu.nju.cs;

import java.util.List;

public final class ConstructorDecl {
    private final String className;
    private final List<MethodDecl.Parameter> parameters;
    private final MiniJavaParser.BlockContext body;

    public ConstructorDecl(String className,
                           List<MethodDecl.Parameter> parameters,
                           MiniJavaParser.BlockContext body) {
        this.className = className;
        this.parameters = List.copyOf(parameters);
        this.body = body;
    }

    public String className() {
        return className;
    }

    public List<MethodDecl.Parameter> parameters() {
        return parameters;
    }

    public MiniJavaParser.BlockContext body() {
        return body;
    }

    public boolean isImplicitDefault() {
        return body == null;
    }
}
