package cn.edu.nju.cs;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class ExpressionEvaluator {
    private static final Set<String> ASSIGNMENT_OPERATORS = Set.of(
            "=", "+=", "-=", "*=", "/=", "%=",
            "&=", "|=", "^=", "<<=", ">>=", ">>>=");

    private final InterpreterVisitor visitor;
    private final LValueResolver lvalueResolver;
    private final CallDispatcher callDispatcher;

    ExpressionEvaluator(InterpreterVisitor visitor, LValueResolver lvalueResolver, CallDispatcher callDispatcher) {
        this.visitor = visitor;
        this.lvalueResolver = lvalueResolver;
        this.callDispatcher = callDispatcher;
    }

    Value evalExpression(MiniJavaParser.ExpressionContext ctx) {
        if (ctx.primary() != null) {
            return visitor.visit(ctx.primary());
        }
        if (ctx.methodCall() != null) {
            return evalMethodCall(ctx.methodCall());
        }
        if (lvalueResolver.isArrayAccessExpr(ctx)) {
            return lvalueResolver.readArrayElement(ctx);
        }
        if (ctx.postfix != null) {
            return evalPostfixExpression(ctx);
        }
        if (ctx.prefix != null) {
            return evalPrefixExpression(ctx);
        }
        if (ctx.NEW() != null) {
            return visitor.evalCreator(ctx.creator());
        }
        if (isCastExpr(ctx)) {
            Type target = visitor.parseType(ctx.typeType());
            if (!target.equals(Type.INT) && !target.equals(Type.CHAR)) {
                throw new RuntimeEvalException("Unsupported cast target: " + target.keyword());
            }
            return visitor.visit(ctx.expression(0)).requireNonVoid().castTo(target);
        }

        String op = ctx.bop == null ? null : ctx.bop.getText();
        if (op == null) {
            throw new RuntimeEvalException("Invalid expression");
        }
        if ("?".equals(op)) {
            return evalTernaryExpression(ctx);
        }
        if ("and".equals(op)) {
            return evalLogicalAnd(ctx);
        }
        if ("or".equals(op)) {
            return evalLogicalOr(ctx);
        }
        if (ASSIGNMENT_OPERATORS.contains(op)) {
            return evalAssignmentExpression(ctx, op);
        }
        return evalBinaryExpression(ctx, op);
    }

    private static boolean isCastExpr(MiniJavaParser.ExpressionContext ctx) {
        return ctx.typeType() != null && ctx.expression().size() == 1 && ctx.bop == null;
    }

    private Value evalMethodCall(MiniJavaParser.MethodCallContext ctx) {
        String name = ctx.identifier().getText();
        List<Value> args = new ArrayList<>();
        if (ctx.arguments().expressionList() != null) {
            for (MiniJavaParser.ExpressionContext expr : ctx.arguments().expressionList().expression()) {
                args.add(visitor.visit(expr).requireNonVoid());
            }
        }
        return callDispatcher.invokeByName(name, args);
    }

    private Value evalPrefixExpression(MiniJavaParser.ExpressionContext ctx) {
        String op = ctx.prefix.getText();
        return switch (op) {
            case "+" -> Value.ofInt(visitor.evalInt(ctx.expression(0)));
            case "-" -> Value.ofInt(-visitor.evalInt(ctx.expression(0)));
            case "~" -> Value.ofInt(~visitor.evalInt(ctx.expression(0)));
            case "not" -> Value.ofBoolean(!visitor.evalBool(ctx.expression(0)));
            case "++", "--" -> {
                LValueResolver.LValue target = lvalueResolver.resolveLValue(ctx.expression(0));
                int old = target.get().requireIntegral();
                int next = op.equals("++") ? old + 1 : old - 1;
                Value assigned = TypeSystem.integralResult(target.type(), next);
                target.set(assigned);
                yield assigned;
            }
            default -> throw new RuntimeEvalException("Unsupported prefix operator: " + op);
        };
    }

    private Value evalPostfixExpression(MiniJavaParser.ExpressionContext ctx) {
        String op = ctx.postfix.getText();
        if (!op.equals("++") && !op.equals("--")) {
            throw new RuntimeEvalException("Unsupported postfix operator: " + op);
        }
        LValueResolver.LValue target = lvalueResolver.resolveLValue(ctx.expression(0));
        Value oldValue = target.get();
        int old = oldValue.requireIntegral();
        int next = op.equals("++") ? old + 1 : old - 1;
        Value assigned = TypeSystem.integralResult(target.type(), next);
        target.set(assigned);
        return oldValue;
    }

    private Value evalTernaryExpression(MiniJavaParser.ExpressionContext ctx) {
        boolean cond = visitor.evalBool(ctx.expression(0));
        return cond
                ? visitor.visit(ctx.expression(1)).requireNonVoid()
                : visitor.visit(ctx.expression(2)).requireNonVoid();
    }

    private Value evalLogicalAnd(MiniJavaParser.ExpressionContext ctx) {
        if (!visitor.evalBool(ctx.expression(0))) {
            return Value.ofBoolean(false);
        }
        return Value.ofBoolean(visitor.evalBool(ctx.expression(1)));
    }

    private Value evalLogicalOr(MiniJavaParser.ExpressionContext ctx) {
        if (visitor.evalBool(ctx.expression(0))) {
            return Value.ofBoolean(true);
        }
        return Value.ofBoolean(visitor.evalBool(ctx.expression(1)));
    }

    private Value evalAssignmentExpression(MiniJavaParser.ExpressionContext ctx, String op) {
        LValueResolver.LValue target = lvalueResolver.resolveLValue(ctx.expression(0));
        Type targetType = target.type();

        Value assigned;
        if ("=".equals(op)) {
            Value right = visitor.visit(ctx.expression(1)).requireNonVoid();
            assigned = TypeSystem.coerceForAssignment(targetType, right);
            target.set(assigned);
            return assigned;
        }

        Value left = target.get();
        Value right = visitor.visit(ctx.expression(1)).requireNonVoid();
        if ("+=".equals(op) && targetType.equals(Type.STRING)) {
            if (!right.isStringConcatOperand()) {
                throw new RuntimeEvalException("Invalid string concatenation operand");
            }
            assigned = Value.ofString(left.asString() + right.toOutputString());
            target.set(assigned);
            return assigned;
        }

        if (!targetType.isIntegral()) {
            throw new RuntimeEvalException("Unsupported assignment target for operator " + op);
        }

        int lv = left.requireIntegral();
        int rv = right.requireIntegral();
        int result = switch (op) {
            case "+=" -> lv + rv;
            case "-=" -> lv - rv;
            case "*=" -> lv * rv;
            case "/=" -> {
                if (rv == 0) {
                    throw new RuntimeEvalException("Division by zero");
                }
                yield lv / rv;
            }
            case "%=" -> {
                if (rv == 0) {
                    throw new RuntimeEvalException("Division by zero");
                }
                yield lv % rv;
            }
            case "&=" -> lv & rv;
            case "|=" -> lv | rv;
            case "^=" -> lv ^ rv;
            case "<<=" -> lv << rv;
            case ">>=" -> lv >> rv;
            case ">>>=" -> lv >>> rv;
            default -> throw new RuntimeEvalException("Unsupported assignment operator: " + op);
        };

        assigned = TypeSystem.integralResult(targetType, result);
        target.set(assigned);
        return assigned;
    }

    private Value evalBinaryExpression(MiniJavaParser.ExpressionContext ctx, String op) {
        Value left = visitor.visit(ctx.expression(0)).requireNonVoid();
        Value right = visitor.visit(ctx.expression(1)).requireNonVoid();

        return switch (op) {
            case "*" -> Value.ofInt(left.requireIntegral() * right.requireIntegral());
            case "/" -> {
                int rv = right.requireIntegral();
                if (rv == 0) {
                    throw new RuntimeEvalException("Division by zero");
                }
                yield Value.ofInt(left.requireIntegral() / rv);
            }
            case "%" -> {
                int rv = right.requireIntegral();
                if (rv == 0) {
                    throw new RuntimeEvalException("Division by zero");
                }
                yield Value.ofInt(left.requireIntegral() % rv);
            }
            case "+" -> {
                if (left.isString() || right.isString()) {
                    if (!left.isStringConcatOperand() || !right.isStringConcatOperand()) {
                        throw new RuntimeEvalException("Invalid string concatenation operands");
                    }
                    yield Value.ofString(left.toOutputString() + right.toOutputString());
                }
                yield Value.ofInt(left.requireIntegral() + right.requireIntegral());
            }
            case "-" -> Value.ofInt(left.requireIntegral() - right.requireIntegral());
            case "<<" -> Value.ofInt(left.requireIntegral() << right.requireIntegral());
            case ">>" -> Value.ofInt(left.requireIntegral() >> right.requireIntegral());
            case ">>>" -> Value.ofInt(left.requireIntegral() >>> right.requireIntegral());
            case "<" -> Value.ofBoolean(left.requireIntegral() < right.requireIntegral());
            case "<=" -> Value.ofBoolean(left.requireIntegral() <= right.requireIntegral());
            case ">" -> Value.ofBoolean(left.requireIntegral() > right.requireIntegral());
            case ">=" -> Value.ofBoolean(left.requireIntegral() >= right.requireIntegral());
            case "==" -> Value.ofBoolean(Value.equalsValue(left, right));
            case "!=" -> Value.ofBoolean(!Value.equalsValue(left, right));
            case "&" -> Value.ofInt(left.requireIntegral() & right.requireIntegral());
            case "^" -> Value.ofInt(left.requireIntegral() ^ right.requireIntegral());
            case "|" -> Value.ofInt(left.requireIntegral() | right.requireIntegral());
            default -> throw new RuntimeEvalException("Unsupported operator: " + op);
        };
    }
}
