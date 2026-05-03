package cn.edu.nju.cs;

import java.util.List;

final class CallDispatcher {
    private final InterpreterVisitor visitor;
    private final EvalContext context;
    private final MethodRegistry methodRegistry;
    private final BuiltinLibrary builtins;

    CallDispatcher(InterpreterVisitor visitor, EvalContext context, MethodRegistry methodRegistry, BuiltinLibrary builtins) {
        this.visitor = visitor;
        this.context = context;
        this.methodRegistry = methodRegistry;
        this.builtins = builtins;
    }

    Value invokeEntry(MethodDecl entry) {
        return invokeUserMethod(entry, List.of());
    }

    Value invokeByName(String name, List<Value> args) {
        BuiltinLibrary.BuiltinResult builtin = builtins.invokeIfMatched(name, args);
        if (builtin.matched()) {
            return builtin.value();
        }
        MethodDecl method = methodRegistry.resolveCall(name, args);
        return invokeUserMethod(method, args);
    }

    private Value invokeUserMethod(MethodDecl method, List<Value> args) {
        context.pushMethod(method);
        context.enterScope();
        try {
            for (int i = 0; i < method.parameters().size(); i++) {
                MethodDecl.Parameter param = method.parameters().get(i);
                Value arg = TypeSystem.coerceForMethodParam(param.type(), args.get(i));
                context.declare(param.name(), param.type(), arg);
            }

            try {
                for (MiniJavaParser.BlockStatementContext stmt : method.body().blockStatement()) {
                    visitor.visit(stmt);
                }
            } catch (ReturnSignal r) {
                return r.hasValue() ? r.value() : Value.voidValue();
            }

            if (method.returnType().equals(Type.VOID)) {
                return Value.voidValue();
            }
            throw new RuntimeEvalException("Missing return statement in method: " + method.name());
        } finally {
            context.exitScope();
            context.popMethod();
        }
    }
}
