package cn.edu.nju.cs;

import java.util.List;
import java.util.stream.Collectors;

public final class MethodDecl {
    public record Parameter(Type type, String name) {
    }

    private final String name;
    private final Type returnType;
    private final List<Parameter> parameters;
    private final MiniJavaParser.BlockContext body;

    public MethodDecl(String name, Type returnType, List<Parameter> parameters, MiniJavaParser.BlockContext body) {
        this.name = name;
        this.returnType = returnType;
        this.parameters = List.copyOf(parameters);
        this.body = body;
    }

    public String name() {
        return name;
    }

    public Type returnType() {
        return returnType;
    }

    public List<Parameter> parameters() {
        return parameters;
    }

    public MiniJavaParser.BlockContext body() {
        return body;
    }

    public String formatSignature() {
        String paramText = parameters.stream()
                .map(p -> p.type().keyword())
                .collect(Collectors.joining(", "));
        return name + "(" + paramText + ")";
    }
}
