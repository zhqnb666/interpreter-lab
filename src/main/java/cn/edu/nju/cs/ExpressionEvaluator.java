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

    ExprResult evalExpression(MiniJavaParser.ExpressionContext ctx) {
        if (ctx.primary() != null) {
            return visitor.evalPrimary(ctx.primary());
        }
        if (ctx.methodCall() != null && ctx.bop == null) {
            return evalMethodCall(ctx.methodCall());
        }
        if (lvalueResolver.isArrayAccessExpr(ctx)) {
            return ExprResult.of(lvalueResolver.readArrayElement(ctx));
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
        if (ctx.INSTANCEOF() != null) {
            return evalInstanceof(ctx);
        }
        if (isCastExpr(ctx)) {
            Type target = visitor.parseType(ctx.typeType());
            ExprResult sourceResult = visitor.evalExpr(ctx.expression(0));
            Value source = sourceResult.valueNonVoid();
            if (target.isClass()) {
                return castToClass(target, source, sourceResult.staticType());
            }
            if (!target.equals(Type.INT) && !target.equals(Type.CHAR)) {
                throw new RuntimeEvalException("Unsupported cast target: " + target.keyword());
            }
            Value casted = source.castTo(target);
            return ExprResult.of(casted, target);
        }

        String op = ctx.bop == null ? null : ctx.bop.getText();
        if (op == null) {
            throw new RuntimeEvalException("Invalid expression");
        }
        if (".".equals(op)) {
            return evalMemberAccess(ctx);
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

    private ExprResult castToClass(Type target, Value source, Type sourceStaticType) {
        // null → typedNull(target), legal for any class target
        if (source.isNull()) {
            return ExprResult.of(Value.typedNull(target), target);
        }
        if (!source.isClassInstance()) {
            throw new RuntimeEvalException(
                    "Cannot cast " + source.typeName() + " to class " + target.keyword());
        }
        ClassRegistry reg = visitor.classRegistry();
        String targetCls = target.className();
        String declCls = sourceStaticType != null && sourceStaticType.isClass()
                ? sourceStaticType.className()
                : source.asClassInstance().realClassName();
        // declared type and target must belong to the same inheritance tree
        if (!reg.inSameHierarchy(declCls, targetCls)) {
            throw new RuntimeEvalException(
                    "Cannot cast " + declCls + " to unrelated class " + targetCls);
        }
        // Upcast or equal: always legal
        if (reg.isSubclassOf(declCls, targetCls)) {
            return ExprResult.of(source, target);
        }
        // Downcast: runtime check on real type
        String realCls = source.asClassInstance().realClassName();
        if (!reg.isSubclassOf(realCls, targetCls)) {
            throw new RuntimeEvalException(
                    "Bad downcast: real type " + realCls + " is not a subclass of " + targetCls);
        }
        return ExprResult.of(source, target);
    }

    /** Receiver description for member access (`recv.field` or `recv.method(...)`). */
    private record Receiver(ClassInstance instance, Type staticType, boolean viaSuper) {
    }

    static boolean isThisPrimary(MiniJavaParser.ExpressionContext e) {
        return e.primary() != null && e.primary().THIS() != null;
    }

    static boolean isSuperPrimary(MiniJavaParser.ExpressionContext e) {
        return e.primary() != null && e.primary().SUPER() != null;
    }

    Receiver evalReceiver(MiniJavaParser.ExpressionContext recvExpr) {
        if (isThisPrimary(recvExpr)) {
            EvalContext.ClassFrame frame = visitor.context().currentClassFrame();
            if (frame == null) {
                throw new RuntimeEvalException("'this' used outside a class method");
            }
            return new Receiver(frame.instance(),
                    Type.ofClass(frame.declaringClass()), false);
        }
        if (isSuperPrimary(recvExpr)) {
            EvalContext.ClassFrame frame = visitor.context().currentClassFrame();
            if (frame == null) {
                throw new RuntimeEvalException("'super' used outside a class method");
            }
            ClassDecl decl = visitor.classRegistry().get(frame.declaringClass());
            if (decl.parentName() == null) {
                throw new RuntimeEvalException(frame.declaringClass() + " has no superclass");
            }
            return new Receiver(frame.instance(),
                    Type.ofClass(decl.parentName()), true);
        }
        ExprResult r = visitor.evalExpr(recvExpr);
        Value v = r.valueNonVoid();
        Type st = r.staticType();
        if (v.isNull()) {
            return new Receiver(null, st, false);
        }
        if (st == null || !st.isClass()) {
            throw new RuntimeEvalException("Member access on non-class type: "
                    + (st == null ? "untyped null" : st.keyword()));
        }
        return new Receiver(v.asClassInstance(), st, false);
    }

    private ExprResult evalMemberAccess(MiniJavaParser.ExpressionContext ctx) {
        if (ctx.identifier() != null) {
            String fieldName = ctx.identifier().getText();
            Receiver recv = evalReceiver(ctx.expression(0));
            if (recv.instance() == null) {
                throw new RuntimeEvalException("Null pointer");
            }
            String startClass = recv.staticType().className();
            ClassRegistry.FieldOwner owner =
                    visitor.classRegistry().findField(startClass, fieldName);
            if (owner == null) {
                throw new RuntimeEvalException(
                        "No field '" + fieldName + "' in class " + startClass);
            }
            Value v = recv.instance().getField(owner.className(), fieldName);
            return ExprResult.of(v, owner.field().type());
        }
        if (ctx.methodCall() != null) {
            MiniJavaParser.MethodCallContext mc = ctx.methodCall();
            if (mc.identifier() == null) {
                throw new RuntimeEvalException(
                        "this()/super() can only appear as the first statement of a constructor");
            }
            String methodName = mc.identifier().getText();
            Receiver recv = evalReceiver(ctx.expression(0));
            if (recv.instance() == null) {
                throw new RuntimeEvalException("Null pointer");
            }
            List<ExprResult> args = new ArrayList<>();
            if (mc.arguments().expressionList() != null) {
                for (MiniJavaParser.ExpressionContext e : mc.arguments().expressionList().expression()) {
                    ExprResult r = visitor.evalExpr(e);
                    r.valueNonVoid();
                    args.add(r);
                }
            }
            return callDispatcher.invokeClassMethodOn(
                    recv.instance(),
                    recv.staticType().className(),
                    recv.viaSuper(),
                    methodName,
                    args);
        }
        throw new RuntimeEvalException("Invalid member access");
    }

    private ExprResult evalMethodCall(MiniJavaParser.MethodCallContext ctx) {
        if (ctx.THIS() != null || ctx.SUPER() != null) {
            // Standalone this(...) / super(...) are only valid as the first statement of
            // a constructor body and are handled by the constructor dispatcher before
            // reaching expression evaluation.
            String which = ctx.THIS() != null ? "this" : "super";
            throw new RuntimeEvalException(
                    which + "(...) is only valid as the first statement of a constructor");
        }
        String name = ctx.identifier().getText();
        List<ExprResult> args = new ArrayList<>();
        if (ctx.arguments().expressionList() != null) {
            for (MiniJavaParser.ExpressionContext expr : ctx.arguments().expressionList().expression()) {
                ExprResult r = visitor.evalExpr(expr);
                r.valueNonVoid();
                args.add(r);
            }
        }
        return callDispatcher.invokeByName(name, args);
    }

    private ExprResult evalPrefixExpression(MiniJavaParser.ExpressionContext ctx) {
        String op = ctx.prefix.getText();
        return switch (op) {
            case "+" -> ExprResult.of(Value.ofInt(visitor.evalInt(ctx.expression(0))));
            case "-" -> ExprResult.of(Value.ofInt(-visitor.evalInt(ctx.expression(0))));
            case "~" -> ExprResult.of(Value.ofInt(~visitor.evalInt(ctx.expression(0))));
            case "not" -> ExprResult.of(Value.ofBoolean(!visitor.evalBool(ctx.expression(0))));
            case "++", "--" -> {
                LValueResolver.LValue target = lvalueResolver.resolveLValue(ctx.expression(0));
                int old = target.get().requireIntegral();
                int next = op.equals("++") ? old + 1 : old - 1;
                Value assigned = TypeSystem.integralResult(target.type(), next);
                target.set(assigned);
                yield ExprResult.of(assigned, target.type());
            }
            default -> throw new RuntimeEvalException("Unsupported prefix operator: " + op);
        };
    }

    private ExprResult evalPostfixExpression(MiniJavaParser.ExpressionContext ctx) {
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
        return ExprResult.of(oldValue, target.type());
    }

    private ExprResult evalTernaryExpression(MiniJavaParser.ExpressionContext ctx) {
        boolean cond = visitor.evalBool(ctx.expression(0));
        ExprResult chosen = cond
                ? visitor.evalExpr(ctx.expression(1))
                : visitor.evalExpr(ctx.expression(2));
        chosen.valueNonVoid();
        return chosen;
    }

    private ExprResult evalLogicalAnd(MiniJavaParser.ExpressionContext ctx) {
        if (!visitor.evalBool(ctx.expression(0))) {
            return ExprResult.of(Value.ofBoolean(false));
        }
        return ExprResult.of(Value.ofBoolean(visitor.evalBool(ctx.expression(1))));
    }

    private ExprResult evalLogicalOr(MiniJavaParser.ExpressionContext ctx) {
        if (visitor.evalBool(ctx.expression(0))) {
            return ExprResult.of(Value.ofBoolean(true));
        }
        return ExprResult.of(Value.ofBoolean(visitor.evalBool(ctx.expression(1))));
    }

    private ExprResult evalAssignmentExpression(MiniJavaParser.ExpressionContext ctx, String op) {
        LValueResolver.LValue target = lvalueResolver.resolveLValue(ctx.expression(0));
        Type targetType = target.type();

        Value assigned;
        if ("=".equals(op)) {
            Value right = visitor.evalExpr(ctx.expression(1)).valueNonVoid();
            assigned = TypeSystem.coerceForAssignment(targetType, right);
            target.set(assigned);
            return ExprResult.of(assigned, targetType);
        }

        Value left = target.get();
        Value right = visitor.evalExpr(ctx.expression(1)).valueNonVoid();
        if ("+=".equals(op) && targetType.equals(Type.STRING)) {
            if (!right.isStringConcatOperand()) {
                throw new RuntimeEvalException("Invalid string concatenation operand");
            }
            assigned = Value.ofString(left.asString() + right.toOutputString());
            target.set(assigned);
            return ExprResult.of(assigned, targetType);
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
        return ExprResult.of(assigned, targetType);
    }

    private ExprResult evalInstanceof(MiniJavaParser.ExpressionContext ctx) {
        ExprResult lhs = visitor.evalExpr(ctx.expression(0));
        Type target = visitor.parseType(ctx.typeType());
        // Static checks.
        if (!target.isClass()) {
            throw new RuntimeEvalException(
                    "instanceof target must be a class type, got " + target.keyword());
        }
        Type lhsType = lhs.staticType();
        if (lhsType == null || !lhsType.isClass()) {
            throw new RuntimeEvalException(
                    "instanceof requires a class-typed left operand");
        }
        ClassRegistry reg = visitor.classRegistry();
        if (!reg.inSameHierarchy(lhsType.className(), target.className())) {
            throw new RuntimeEvalException(
                    "instanceof: " + lhsType.className() + " and " + target.className()
                            + " are not in the same inheritance tree");
        }
        Value v = lhs.valueNonVoid();
        if (v.isNull()) {
            return ExprResult.of(Value.ofBoolean(false));
        }
        String realCls = v.asClassInstance().realClassName();
        return ExprResult.of(Value.ofBoolean(reg.isSubclassOf(realCls, target.className())));
    }

    private boolean classAwareEquals(ExprResult left, ExprResult right) {
        Value lv = left.value();
        Value rv = right.value();
        Type lt = left.staticType();
        Type rt = right.staticType();
        boolean lIsClassSlot = (lt != null && lt.isClass()) || lv.isClassInstance();
        boolean rIsClassSlot = (rt != null && rt.isClass()) || rv.isClassInstance();
        if (!lIsClassSlot && !rIsClassSlot) {
            return Value.equalsValue(lv, rv);
        }
        // At least one operand is in a class slot.
        // Per PDF §6.4: when both operands are typed class slots, decl(obj1) and
        // decl(obj2) must lie in the same inheritance tree (even when both are null).
        // Comparison with the null literal — staticType == null — is unconditionally
        // legal for any class type.
        String lc = (lt != null && lt.isClass()) ? lt.className() : null;
        String rc = (rt != null && rt.isClass()) ? rt.className() : null;
        if (lc != null && rc != null
                && !visitor.classRegistry().inSameHierarchy(lc, rc)) {
            throw new RuntimeEvalException(
                    "Type error: " + lc + " and " + rc + " are not in the same inheritance tree");
        }
        if (lv.isNull() && rv.isNull()) {
            return true;
        }
        if (lv.isNull() || rv.isNull()) {
            return false;
        }
        if (!lv.isClassInstance() || !rv.isClassInstance()) {
            throw new RuntimeEvalException("Type error: cannot compare class type with non-class");
        }
        return lv.asClassInstance() == rv.asClassInstance();
    }

    private ExprResult evalBinaryExpression(MiniJavaParser.ExpressionContext ctx, String op) {
        ExprResult leftR = visitor.evalExpr(ctx.expression(0));
        ExprResult rightR = visitor.evalExpr(ctx.expression(1));
        Value left = leftR.valueNonVoid();
        Value right = rightR.valueNonVoid();

        if ("==".equals(op) || "!=".equals(op)) {
            boolean eq = classAwareEquals(leftR, rightR);
            return ExprResult.of(Value.ofBoolean(op.equals("==") == eq));
        }

        return switch (op) {
            case "*" -> ExprResult.of(Value.ofInt(left.requireIntegral() * right.requireIntegral()));
            case "/" -> {
                int rv = right.requireIntegral();
                if (rv == 0) {
                    throw new RuntimeEvalException("Division by zero");
                }
                yield ExprResult.of(Value.ofInt(left.requireIntegral() / rv));
            }
            case "%" -> {
                int rv = right.requireIntegral();
                if (rv == 0) {
                    throw new RuntimeEvalException("Division by zero");
                }
                yield ExprResult.of(Value.ofInt(left.requireIntegral() % rv));
            }
            case "+" -> {
                if (left.isString() || right.isString()) {
                    if (!left.isStringConcatOperand() || !right.isStringConcatOperand()) {
                        throw new RuntimeEvalException("Invalid string concatenation operands");
                    }
                    yield ExprResult.of(Value.ofString(left.toOutputString() + right.toOutputString()));
                }
                yield ExprResult.of(Value.ofInt(left.requireIntegral() + right.requireIntegral()));
            }
            case "-" -> ExprResult.of(Value.ofInt(left.requireIntegral() - right.requireIntegral()));
            case "<<" -> ExprResult.of(Value.ofInt(left.requireIntegral() << right.requireIntegral()));
            case ">>" -> ExprResult.of(Value.ofInt(left.requireIntegral() >> right.requireIntegral()));
            case ">>>" -> ExprResult.of(Value.ofInt(left.requireIntegral() >>> right.requireIntegral()));
            case "<" -> ExprResult.of(Value.ofBoolean(left.requireIntegral() < right.requireIntegral()));
            case "<=" -> ExprResult.of(Value.ofBoolean(left.requireIntegral() <= right.requireIntegral()));
            case ">" -> ExprResult.of(Value.ofBoolean(left.requireIntegral() > right.requireIntegral()));
            case ">=" -> ExprResult.of(Value.ofBoolean(left.requireIntegral() >= right.requireIntegral()));
            case "==" -> ExprResult.of(Value.ofBoolean(Value.equalsValue(left, right)));
            case "!=" -> ExprResult.of(Value.ofBoolean(!Value.equalsValue(left, right)));
            case "&" -> ExprResult.of(Value.ofInt(left.requireIntegral() & right.requireIntegral()));
            case "^" -> ExprResult.of(Value.ofInt(left.requireIntegral() ^ right.requireIntegral()));
            case "|" -> ExprResult.of(Value.ofInt(left.requireIntegral() | right.requireIntegral()));
            default -> throw new RuntimeEvalException("Unsupported operator: " + op);
        };
    }
}
