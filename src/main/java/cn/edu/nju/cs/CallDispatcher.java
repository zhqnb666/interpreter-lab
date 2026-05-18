package cn.edu.nju.cs;

import java.util.ArrayList;
import java.util.List;

final class CallDispatcher {
    private final InterpreterVisitor visitor;
    private final EvalContext context;
    private final MethodRegistry methodRegistry;
    private final BuiltinLibrary builtins;
    private ClassRegistry classRegistry;

    CallDispatcher(InterpreterVisitor visitor, EvalContext context, MethodRegistry methodRegistry, BuiltinLibrary builtins) {
        this.visitor = visitor;
        this.context = context;
        this.methodRegistry = methodRegistry;
        this.builtins = builtins;
    }

    void setClassRegistry(ClassRegistry classRegistry) {
        this.classRegistry = classRegistry;
    }

    ExprResult invokeByName(String name, List<ExprResult> args) {
        BuiltinLibrary.BuiltinResult builtin = builtins.invokeIfMatched(name, args);
        if (builtin.matched()) {
            return ExprResult.of(builtin.value());
        }
        // Unqualified `foo(args)` inside a class method resolves as `this.foo(args)` only
        // when there is a *suitable* overload on the current class chain (an overload
        // whose parameters can accept the arguments). If no class candidate fits the
        // arguments — wrong arity, incompatible types, etc. — fall through to top-level,
        // per PDF §2.3 Note 1: "if no suitable class method is found, it is resolved as
        // a top-level method."
        EvalContext.ClassFrame frame = context.currentClassFrame();
        if (frame != null && classRegistry != null) {
            List<MethodDecl> classCandidates =
                    classRegistry.collectMethods(frame.declaringClass(), name);
            if (hasSuitableOverload(classCandidates, args)) {
                return invokeClassMethodOn(
                        frame.instance(), frame.declaringClass(), false, name, args);
            }
        }
        MethodDecl method = methodRegistry.resolveCall(name, args);
        return runUserMethod(method, args, null, null);
    }

    /**
     * Trial overload check: at least one candidate accepts the arguments with a
     * non-negative {@link TypeSystem#methodConversionCost}. Shares cost semantics with
     * {@link MethodRegistry#selectBestOverload} so a trial-true call won't surprise the
     * final selection (ties still surface as ambiguity errors there, by design).
     */
    private static boolean hasSuitableOverload(List<MethodDecl> candidates, List<ExprResult> args) {
        for (MethodDecl candidate : candidates) {
            if (candidate.parameters().size() != args.size()) {
                continue;
            }
            boolean ok = true;
            for (int i = 0; i < args.size(); i++) {
                if (TypeSystem.methodConversionCost(
                        candidate.parameters().get(i).type(), args.get(i)) < 0) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                return true;
            }
        }
        return false;
    }

    /**
     * Dot-form method invocation: {@code recv.foo(args)} / {@code this.foo(args)} /
     * {@code super.foo(args)}. Implements two-phase dispatch (overload by declared type,
     * override by real type). For {@code super.foo}, both phases start from the static
     * (parent) class.
     */
    ExprResult invokeClassMethodOn(
            ClassInstance instance,
            String staticClass,
            boolean viaSuper,
            String methodName,
            List<ExprResult> args) {
        // Phase 1: overload resolution on the declared-type chain.
        List<MethodDecl> candidates = classRegistry.collectMethods(staticClass, methodName);
        if (candidates.isEmpty()) {
            throw new RuntimeEvalException(
                    "No method '" + methodName + "' in class " + staticClass);
        }
        MethodDecl sig = MethodRegistry.selectBestOverload(
                candidates, MethodDecl::parameters, args, methodName);

        // Phase 2: virtual dispatch from real (or static class when viaSuper).
        String dispatchStart = viaSuper ? staticClass : instance.realClassName();
        ClassRegistry.MethodDispatch dispatch =
                classRegistry.findOverrideForSignature(dispatchStart, methodName, sig.parameters());
        if (dispatch == null) {
            throw new RuntimeEvalException(
                    "No matching override for " + methodName + " starting from " + dispatchStart);
        }
        return runUserMethod(dispatch.method(), args, instance, dispatch.declaringClass());
    }

    private ExprResult runUserMethod(
            MethodDecl method,
            List<ExprResult> args,
            ClassInstance instance,
            String declaringClass) {
        boolean withClassFrame = instance != null;
        context.pushMethod(method);
        context.enterScope();
        if (withClassFrame) {
            context.pushClassFrame(new EvalContext.ClassFrame(instance, declaringClass));
        }
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
                Value v = r.hasValue() ? r.value() : Value.voidValue();
                return ExprResult.of(v, method.returnType());
            }

            if (method.returnType().equals(Type.VOID)) {
                return ExprResult.of(Value.voidValue(), Type.VOID);
            }
            throw new RuntimeEvalException("Missing return statement in method: " + method.name());
        } finally {
            if (withClassFrame) {
                context.popClassFrame();
            }
            context.exitScope();
            context.popMethod();
        }
    }

    ExprResult invokeEntry(MethodDecl entry) {
        return runUserMethod(entry, List.of(), null, null);
    }

    // ===================== Lab 4: class construction =====================

    ExprResult invokeNewClass(String className, List<ExprResult> args) {
        ClassDecl decl = classRegistry.get(className);
        ClassInstance instance = new ClassInstance(
                className, classRegistry.buildInitialFieldLayers(className));
        ConstructorDecl ctor = classRegistry.resolveConstructor(className, args);
        invokeConstructor(instance, decl, ctor, args);
        return ExprResult.of(Value.ofClassInstance(instance), Type.ofClass(className));
    }

    private void invokeConstructor(
            ClassInstance instance,
            ClassDecl decl,
            ConstructorDecl ctor,
            List<ExprResult> args) {
        MethodDecl synthetic = new MethodDecl(
                "<init>:" + decl.name(),
                Type.VOID,
                ctor.parameters(),
                null);
        context.pushMethod(synthetic);
        context.enterScope();
        context.pushClassFrame(new EvalContext.ClassFrame(instance, decl.name()));
        try {
            for (int i = 0; i < ctor.parameters().size(); i++) {
                MethodDecl.Parameter param = ctor.parameters().get(i);
                Value arg = TypeSystem.coerceForMethodParam(param.type(), args.get(i));
                context.declare(param.name(), param.type(), arg);
            }
            executeConstructorFlow(instance, decl, ctor);
        } finally {
            context.popClassFrame();
            context.exitScope();
            context.popMethod();
        }
    }

    private void executeConstructorFlow(ClassInstance instance, ClassDecl decl, ConstructorDecl ctor) {
        int startIdx = 0;
        boolean delegatedToThis = false;
        boolean explicitSuper = false;

        if (!ctor.isImplicitDefault()) {
            List<MiniJavaParser.BlockStatementContext> stmts = ctor.body().blockStatement();
            if (!stmts.isEmpty()) {
                ChainKind kind = classifyChain(stmts.get(0));
                if (kind == ChainKind.THIS) {
                    invokeChainedThis(instance, decl, stmts.get(0));
                    delegatedToThis = true;
                    startIdx = 1;
                } else if (kind == ChainKind.SUPER) {
                    invokeChainedSuper(instance, decl, stmts.get(0));
                    explicitSuper = true;
                    startIdx = 1;
                }
            }
        }

        if (!delegatedToThis && !explicitSuper && decl.parentName() != null) {
            invokeImplicitSuper(instance, classRegistry.get(decl.parentName()));
        }

        if (!delegatedToThis) {
            initializeFields(instance, decl);
        }

        if (!ctor.isImplicitDefault()) {
            List<MiniJavaParser.BlockStatementContext> stmts = ctor.body().blockStatement();
            try {
                for (int i = startIdx; i < stmts.size(); i++) {
                    visitor.visit(stmts.get(i));
                }
            } catch (ReturnSignal r) {
                if (r.hasValue()) {
                    throw new RuntimeEvalException("Constructor cannot return a value");
                }
            }
        }
    }

    private void invokeChainedThis(ClassInstance instance, ClassDecl decl, MiniJavaParser.BlockStatementContext firstStmt) {
        MiniJavaParser.MethodCallContext mc = firstStmt.statement().expression().methodCall();
        List<ExprResult> args = evalArguments(mc.arguments());
        ConstructorDecl target = classRegistry.resolveConstructor(decl.name(), args);
        invokeConstructor(instance, decl, target, args);
    }

    private void invokeChainedSuper(ClassInstance instance, ClassDecl decl, MiniJavaParser.BlockStatementContext firstStmt) {
        if (decl.parentName() == null) {
            throw new RuntimeEvalException("super(...) but class " + decl.name() + " has no superclass");
        }
        MiniJavaParser.MethodCallContext mc = firstStmt.statement().expression().methodCall();
        List<ExprResult> args = evalArguments(mc.arguments());
        ClassDecl parent = classRegistry.get(decl.parentName());
        ConstructorDecl target = classRegistry.resolveConstructor(parent.name(), args);
        invokeConstructor(instance, parent, target, args);
    }

    private void invokeImplicitSuper(ClassInstance instance, ClassDecl parent) {
        ConstructorDecl target = classRegistry.resolveConstructor(parent.name(), List.of());
        invokeConstructor(instance, parent, target, List.of());
    }

    private void initializeFields(ClassInstance instance, ClassDecl decl) {
        for (ClassDecl.FieldDecl f : decl.fields()) {
            if (f.declarator().variableInitializer() != null) {
                Value v = visitor.evalFieldInitializer(f.declarator().variableInitializer(), f.type());
                instance.setField(decl.name(), f.name(), v);
            }
        }
    }

    private List<ExprResult> evalArguments(MiniJavaParser.ArgumentsContext ctx) {
        List<ExprResult> args = new ArrayList<>();
        if (ctx.expressionList() != null) {
            for (MiniJavaParser.ExpressionContext e : ctx.expressionList().expression()) {
                ExprResult r = visitor.evalExpr(e);
                r.valueNonVoid();
                args.add(r);
            }
        }
        return args;
    }

    private enum ChainKind { NONE, THIS, SUPER }

    /** Detect `this(args);` or `super(args);` as a standalone statement. */
    private static ChainKind classifyChain(MiniJavaParser.BlockStatementContext stmt) {
        if (stmt.statement() == null) {
            return ChainKind.NONE;
        }
        MiniJavaParser.StatementContext s = stmt.statement();
        if (s.expression() == null) {
            return ChainKind.NONE;
        }
        MiniJavaParser.ExpressionContext e = s.expression();
        if (e.methodCall() == null) {
            return ChainKind.NONE;
        }
        if (e.bop != null || e.prefix != null || e.postfix != null) {
            return ChainKind.NONE;
        }
        MiniJavaParser.MethodCallContext mc = e.methodCall();
        if (mc.THIS() != null) {
            return ChainKind.THIS;
        }
        if (mc.SUPER() != null) {
            return ChainKind.SUPER;
        }
        return ChainKind.NONE;
    }
}
