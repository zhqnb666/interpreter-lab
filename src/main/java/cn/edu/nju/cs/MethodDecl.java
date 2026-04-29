package cn.edu.nju.cs;

import java.util.List;

public final class MethodDecl {
    public record Parameter(Type type, String name) {
    }

    private final MethodSignature signature;
    private final Type returnType;
    private final List<Parameter> parameters;
    private final MiniJavaParser.BlockContext body;

    public MethodDecl(String name, Type returnType, List<Parameter> parameters, MiniJavaParser.BlockContext body) {
        this.signature = new MethodSignature(name, parameters.stream().map(Parameter::type).toList());
        this.returnType = returnType;
        this.parameters = List.copyOf(parameters);
        this.body = body;
    }

    public MethodSignature signature() {
        return signature;
    }

    public String name() {
        return signature.name();
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
}
