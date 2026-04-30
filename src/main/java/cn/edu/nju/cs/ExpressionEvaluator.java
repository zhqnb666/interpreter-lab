package cn.edu.nju.cs;

import java.util.ArrayList;
import java.util.List;

final class ExpressionEvaluator {
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
        if (visitor.isCastExpr(ctx)) {
            Type target = visitor.parseType(ctx.typeType());
            if (!target.equals(Type.INT) && !target.equals(Type.CHAR)) {
                throw new RuntimeEvalException("Unsupported cast target: " + target.keyword());
            }
            return visitor.requireNonVoid(visitor.visit(ctx.expression(0))).castTo(target);
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
        if (visitor.isAssignmentOperator(op)) {
            return evalAssignmentExpression(ctx, op);
        }
        return evalBinaryExpression(ctx, op);
    }

    private Value evalMethodCall(MiniJavaParser.MethodCallContext ctx) {
        String name = ctx.identifier().getText();
        List<Value> args = new ArrayList<>();
        if (ctx.arguments().expressionList() != null) {
            for (MiniJavaParser.ExpressionContext expr : ctx.arguments().expressionList().expression()) {
                args.add(visitor.requireNonVoid(visitor.visit(expr)));
            }
        }
        return callDispatcher.invokeByName(name, args);
    }

    private Value evalPrefixExpression(MiniJavaParser.ExpressionContext ctx) {
        String op = ctx.prefix.getText();
        return switch (op) {
            case "+" -> Value.ofInt(visitor.requireIntegral(visitor.requireNonVoid(visitor.visit(ctx.expression(0)))));
            case "-" -> Value.ofInt(-visitor.requireIntegral(visitor.requireNonVoid(visitor.visit(ctx.expression(0)))));
            case "~" -> Value.ofInt(~visitor.requireIntegral(visitor.requireNonVoid(visitor.visit(ctx.expression(0)))));
            case "not" -> Value.ofBoolean(!visitor.requireBoolean(visitor.requireNonVoid(visitor.visit(ctx.expression(0)))));
            case "++", "--" -> {
                LValueResolver.LValue target = lvalueResolver.resolveLValue(ctx.expression(0));
                int old = visitor.requireIntegral(target.get());
                int next = op.equals("++") ? old + 1 : old - 1;
                Value assigned = visitor.assignIntegralBack(target.type(), next);
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
        int old = visitor.requireIntegral(oldValue);
        int next = op.equals("++") ? old + 1 : old - 1;
        Value assigned = visitor.assignIntegralBack(target.type(), next);
        target.set(assigned);
        return oldValue;
    }

    private Value evalTernaryExpression(MiniJavaParser.ExpressionContext ctx) {
        boolean cond = visitor.requireBoolean(visitor.requireNonVoid(visitor.visit(ctx.expression(0))));
        return cond ? visitor.requireNonVoid(visitor.visit(ctx.expression(1))) : visitor.requireNonVoid(visitor.visit(ctx.expression(2)));
    }

    private Value evalLogicalAnd(MiniJavaParser.ExpressionContext ctx) {
        Value left = visitor.requireNonVoid(visitor.visit(ctx.expression(0)));
        boolean lb = visitor.requireBoolean(left);
        if (!lb) {
            return Value.ofBoolean(false);
        }
        boolean rb = visitor.requireBoolean(visitor.requireNonVoid(visitor.visit(ctx.expression(1))));
        return Value.ofBoolean(rb);
    }

    private Value evalLogicalOr(MiniJavaParser.ExpressionContext ctx) {
        Value left = visitor.requireNonVoid(visitor.visit(ctx.expression(0)));
        boolean lb = visitor.requireBoolean(left);
        if (lb) {
            return Value.ofBoolean(true);
        }
        boolean rb = visitor.requireBoolean(visitor.requireNonVoid(visitor.visit(ctx.expression(1))));
        return Value.ofBoolean(rb);
    }

    private Value evalAssignmentExpression(MiniJavaParser.ExpressionContext ctx, String op) {
        LValueResolver.LValue target = lvalueResolver.resolveLValue(ctx.expression(0));
        Type targetType = target.type();

        Value assigned;
        if ("=".equals(op)) {
            Value right = visitor.requireNonVoid(visitor.visit(ctx.expression(1)));
            assigned = TypeSystem.coerceForAssignment(targetType, right);
            target.set(assigned);
            return assigned;
        }

        Value left = target.get();
        Value right = visitor.requireNonVoid(visitor.visit(ctx.expression(1)));
        if ("+=".equals(op) && targetType.equals(Type.STRING)) {
            if (!visitor.isStringConcatOperand(right)) {
                throw new RuntimeEvalException("Invalid string concatenation operand");
            }
            assigned = Value.ofString(left.asString() + right.toOutputString());
            target.set(assigned);
            return assigned;
        }

        if (!targetType.isIntegralScalar()) {
            throw new RuntimeEvalException("Unsupported assignment target for operator " + op);
        }

        int lv = visitor.requireIntegral(left);
        int rv = visitor.requireIntegral(right);
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

        assigned = visitor.assignIntegralBack(targetType, result);
        target.set(assigned);
        return assigned;
    }

    private Value evalBinaryExpression(MiniJavaParser.ExpressionContext ctx, String op) {
        Value left = visitor.requireNonVoid(visitor.visit(ctx.expression(0)));
        Value right = visitor.requireNonVoid(visitor.visit(ctx.expression(1)));

        return switch (op) {
            case "*" -> Value.ofInt(visitor.requireIntegral(left) * visitor.requireIntegral(right));
            case "/" -> {
                int rv = visitor.requireIntegral(right);
                if (rv == 0) {
                    throw new RuntimeEvalException("Division by zero");
                }
                yield Value.ofInt(visitor.requireIntegral(left) / rv);
            }
            case "%" -> {
                int rv = visitor.requireIntegral(right);
                if (rv == 0) {
                    throw new RuntimeEvalException("Division by zero");
                }
                yield Value.ofInt(visitor.requireIntegral(left) % rv);
            }
            case "+" -> {
                if (left.isString() || right.isString()) {
                    if (!visitor.isStringConcatOperand(left) || !visitor.isStringConcatOperand(right)) {
                        throw new RuntimeEvalException("Invalid string concatenation operands");
                    }
                    yield Value.ofString(left.toOutputString() + right.toOutputString());
                }
                yield Value.ofInt(visitor.requireIntegral(left) + visitor.requireIntegral(right));
            }
            case "-" -> Value.ofInt(visitor.requireIntegral(left) - visitor.requireIntegral(right));
            case "<<" -> Value.ofInt(visitor.requireIntegral(left) << visitor.requireIntegral(right));
            case ">>" -> Value.ofInt(visitor.requireIntegral(left) >> visitor.requireIntegral(right));
            case ">>>" -> Value.ofInt(visitor.requireIntegral(left) >>> visitor.requireIntegral(right));
            case "<" -> Value.ofBoolean(visitor.requireIntegral(left) < visitor.requireIntegral(right));
            case "<=" -> Value.ofBoolean(visitor.requireIntegral(left) <= visitor.requireIntegral(right));
            case ">" -> Value.ofBoolean(visitor.requireIntegral(left) > visitor.requireIntegral(right));
            case ">=" -> Value.ofBoolean(visitor.requireIntegral(left) >= visitor.requireIntegral(right));
            case "==" -> Value.ofBoolean(visitor.equalsValue(left, right));
            case "!=" -> Value.ofBoolean(!visitor.equalsValue(left, right));
            case "&" -> Value.ofInt(visitor.requireIntegral(left) & visitor.requireIntegral(right));
            case "^" -> Value.ofInt(visitor.requireIntegral(left) ^ visitor.requireIntegral(right));
            case "|" -> Value.ofInt(visitor.requireIntegral(left) | visitor.requireIntegral(right));
            default -> throw new RuntimeEvalException("Unsupported operator: " + op);
        };
    }
}
