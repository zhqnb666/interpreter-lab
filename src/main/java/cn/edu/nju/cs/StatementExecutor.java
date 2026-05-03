package cn.edu.nju.cs;

final class StatementExecutor {
    private final InterpreterVisitor visitor;
    private final EvalContext context;

    StatementExecutor(InterpreterVisitor visitor, EvalContext context) {
        this.visitor = visitor;
        this.context = context;
    }

    Value executeStatement(MiniJavaParser.StatementContext ctx) {
        if (ctx.block() != null) {
            return visitor.visit(ctx.block());
        }
        if (ctx.IF() != null) {
            return execIf(ctx);
        }
        if (ctx.WHILE() != null) {
            return execWhile(ctx);
        }
        if (ctx.FOR() != null) {
            return execFor(ctx);
        }
        if (ctx.RETURN() != null) {
            return execReturn(ctx);
        }
        if (ctx.BREAK() != null) {
            if (!context.inLoop()) {
                throw new RuntimeEvalException("break outside loop");
            }
            throw new BreakSignal();
        }
        if (ctx.CONTINUE() != null) {
            if (!context.inLoop()) {
                throw new RuntimeEvalException("continue outside loop");
            }
            throw new ContinueSignal();
        }
        if (ctx.expression() != null) {
            visitor.visit(ctx.expression());
            return null;
        }
        return null;
    }

    private Value execIf(MiniJavaParser.StatementContext ctx) {
        boolean cond = visitor.evalBool(ctx.parExpression().expression());
        if (cond) {
            visitor.visit(ctx.statement(0));
        } else if (ctx.ELSE() != null) {
            visitor.visit(ctx.statement(1));
        }
        return null;
    }

    private Value execWhile(MiniJavaParser.StatementContext ctx) {
        context.pushLoop();
        try {
            while (visitor.evalBool(ctx.parExpression().expression())) {
                try {
                    visitor.visit(ctx.statement(0));
                } catch (ContinueSignal ignored) {
                } catch (BreakSignal ignored) {
                    break;
                }
            }
            return null;
        } finally {
            context.popLoop();
        }
    }

    private Value execFor(MiniJavaParser.StatementContext ctx) {
        MiniJavaParser.ForControlContext fc = ctx.forControl();
        boolean hasForScope = fc.forInit() != null && fc.forInit().localVariableDeclaration() != null;
        if (hasForScope) {
            context.enterScope();
        }
        context.pushLoop();
        try {
            if (fc.forInit() != null) {
                visitor.visit(fc.forInit());
            }
            while (true) {
                if (fc.expression() != null && !visitor.evalBool(fc.expression())) {
                    break;
                }
                try {
                    visitor.visit(ctx.statement(0));
                } catch (ContinueSignal ignored) {
                    if (fc.forUpdate != null) {
                        visitor.visit(fc.forUpdate);
                    }
                    continue;
                } catch (BreakSignal ignored) {
                    break;
                }
                if (fc.forUpdate != null) {
                    visitor.visit(fc.forUpdate);
                }
            }
            return null;
        } finally {
            context.popLoop();
            if (hasForScope) {
                context.exitScope();
            }
        }
    }

    private Value execReturn(MiniJavaParser.StatementContext ctx) {
        MethodDecl method = context.currentMethod();
        Type returnType = method.returnType();
        if (ctx.expression() == null) {
            if (returnType.equals(Type.VOID)) {
                throw new ReturnSignal();
            }
            throw new RuntimeEvalException("non-void method must return a value");
        }

        Value value = visitor.visit(ctx.expression()).requireNonVoid();
        if (returnType.equals(Type.VOID)) {
            throw new RuntimeEvalException("void method cannot return a value");
        }
        Value coerced = TypeSystem.coerceForAssignment(returnType, value);
        throw new ReturnSignal(coerced);
    }
}
