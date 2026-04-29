package cn.edu.nju.cs;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

public class InterpreterVisitor extends MiniJavaParserBaseVisitor<Value> {
    private final EvalContext context;
    private final MethodRegistry methodRegistry = new MethodRegistry();

    public InterpreterVisitor(PrintStream out) {
        this.context = new EvalContext(out);
    }

    public int execute(MiniJavaParser.CompilationUnitContext ctx) {
        visitCompilationUnit(ctx);
        MethodDecl entry = methodRegistry.resolveEntryMain();
        Value result = invokeUserMethod(entry, List.of());
        if (!result.isInt()) {
            throw new RuntimeEvalException("Entry main() must return int");
        }
        return result.asInt();
    }

    @Override
    public Value visitCompilationUnit(MiniJavaParser.CompilationUnitContext ctx) {
        for (MiniJavaParser.MethodDeclarationContext methodCtx : ctx.methodDeclaration()) {
            registerMethod(methodCtx);
        }
        return null;
    }

    private void registerMethod(MiniJavaParser.MethodDeclarationContext ctx) {
        Type returnType = ctx.VOID() != null ? Type.VOID : parseType(ctx.typeType());
        String name = ctx.identifier().getText();
        List<MethodDecl.Parameter> params = new ArrayList<>();
        MiniJavaParser.FormalParameterListContext plist = ctx.formalParameters().formalParameterList();
        if (plist != null) {
            for (MiniJavaParser.FormalParameterContext pctx : plist.formalParameter()) {
                Type paramType = parseType(pctx.typeType());
                params.add(new MethodDecl.Parameter(paramType, pctx.identifier().getText()));
            }
        }
        methodRegistry.register(new MethodDecl(name, returnType, params, ctx.block()));
    }

    @Override
    public Value visitBlock(MiniJavaParser.BlockContext ctx) {
        context.enterScope();
        try {
            for (MiniJavaParser.BlockStatementContext stmt : ctx.blockStatement()) {
                visit(stmt);
            }
            return null;
        } finally {
            context.exitScope();
        }
    }

    @Override
    public Value visitBlockStatement(MiniJavaParser.BlockStatementContext ctx) {
        if (ctx.localVariableDeclaration() != null) {
            return visit(ctx.localVariableDeclaration());
        }
        return visit(ctx.statement());
    }

    @Override
    public Value visitLocalVariableDeclaration(MiniJavaParser.LocalVariableDeclarationContext ctx) {
        if (ctx.VAR() != null) {
            String name = ctx.identifier().getText();
            Value init = requireNonVoid(visit(ctx.expression()));
            if (init.isNull()) {
                throw new RuntimeEvalException("Cannot infer type from null");
            }
            Type inferred = init.isDecimalLiteral() ? Type.INT : init.type();
            Value coerced = TypeSystem.coerceForAssignment(inferred, init);
            context.declare(name, inferred, coerced);
            return null;
        }

        Type declaredType = parseType(ctx.typeType());
        MiniJavaParser.VariableDeclaratorContext declarator = ctx.variableDeclarator();
        String name = declarator.identifier().getText();
        Value value;
        if (declarator.variableInitializer() == null) {
            value = TypeSystem.defaultValue(declaredType);
        } else {
            value = evalVariableInitializer(declarator.variableInitializer(), declaredType);
        }
        context.declare(name, declaredType, value);
        return null;
    }

    @Override
    public Value visitStatement(MiniJavaParser.StatementContext ctx) {
        if (ctx.block() != null) {
            return visit(ctx.block());
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
            visit(ctx.expression());
            return null;
        }
        return null;
    }

    @Override
    public Value visitForInit(MiniJavaParser.ForInitContext ctx) {
        if (ctx.localVariableDeclaration() != null) {
            return visit(ctx.localVariableDeclaration());
        }
        if (ctx.expressionList() != null) {
            return visit(ctx.expressionList());
        }
        return null;
    }

    @Override
    public Value visitExpressionList(MiniJavaParser.ExpressionListContext ctx) {
        Value last = null;
        for (MiniJavaParser.ExpressionContext expr : ctx.expression()) {
            last = visit(expr);
        }
        return last;
    }

    @Override
    public Value visitExpression(MiniJavaParser.ExpressionContext ctx) {
        if (ctx.primary() != null) {
            return visit(ctx.primary());
        }
        if (ctx.methodCall() != null) {
            return evalMethodCall(ctx.methodCall());
        }
        if (isArrayAccessExpr(ctx)) {
            return readArrayElement(ctx);
        }
        if (ctx.postfix != null) {
            return evalPostfixExpression(ctx);
        }
        if (ctx.prefix != null) {
            return evalPrefixExpression(ctx);
        }
        if (ctx.NEW() != null) {
            return evalCreator(ctx.creator());
        }
        if (isCastExpr(ctx)) {
            Type target = parseType(ctx.typeType());
            if (!target.equals(Type.INT) && !target.equals(Type.CHAR)) {
                throw new RuntimeEvalException("Unsupported cast target: " + target.keyword());
            }
            return requireNonVoid(visit(ctx.expression(0))).castTo(target);
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
        if (isAssignmentOperator(op)) {
            return evalAssignmentExpression(ctx, op);
        }
        return evalBinaryExpression(ctx, op);
    }

    @Override
    public Value visitPrimary(MiniJavaParser.PrimaryContext ctx) {
        if (ctx.expression() != null) {
            return visit(ctx.expression());
        }
        if (ctx.literal() != null) {
            return parseLiteral(ctx.literal());
        }
        if (ctx.identifier() != null) {
            return context.resolve(ctx.identifier().getText()).value();
        }
        throw new RuntimeEvalException("Invalid primary expression");
    }

    private Value execIf(MiniJavaParser.StatementContext ctx) {
        boolean cond = requireBoolean(requireNonVoid(visit(ctx.parExpression().expression())));
        if (cond) {
            visit(ctx.statement(0));
        } else if (ctx.ELSE() != null) {
            visit(ctx.statement(1));
        }
        return null;
    }

    private Value execWhile(MiniJavaParser.StatementContext ctx) {
        context.pushLoop();
        try {
            while (requireBoolean(requireNonVoid(visit(ctx.parExpression().expression())))) {
                try {
                    visit(ctx.statement(0));
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
                visit(fc.forInit());
            }
            while (true) {
                if (fc.expression() != null) {
                    boolean cond = requireBoolean(requireNonVoid(visit(fc.expression())));
                    if (!cond) {
                        break;
                    }
                }
                try {
                    visit(ctx.statement(0));
                } catch (ContinueSignal ignored) {
                    if (fc.forUpdate != null) {
                        visit(fc.forUpdate);
                    }
                    continue;
                } catch (BreakSignal ignored) {
                    break;
                }
                if (fc.forUpdate != null) {
                    visit(fc.forUpdate);
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

        Value value = requireNonVoid(visit(ctx.expression()));
        if (returnType.equals(Type.VOID)) {
            throw new RuntimeEvalException("void method cannot return a value");
        }
        Value coerced = TypeSystem.coerceForAssignment(returnType, value);
        throw new ReturnSignal(coerced);
    }

    private Value evalPrefixExpression(MiniJavaParser.ExpressionContext ctx) {
        String op = ctx.prefix.getText();
        return switch (op) {
            case "+" -> Value.ofInt(requireIntegral(requireNonVoid(visit(ctx.expression(0)))));
            case "-" -> Value.ofInt(-requireIntegral(requireNonVoid(visit(ctx.expression(0)))));
            case "~" -> Value.ofInt(~requireIntegral(requireNonVoid(visit(ctx.expression(0)))));
            case "not" -> Value.ofBoolean(!requireBoolean(requireNonVoid(visit(ctx.expression(0)))));
            case "++", "--" -> {
                LValue target = resolveLValue(ctx.expression(0));
                int old = requireIntegral(target.get());
                int next = op.equals("++") ? old + 1 : old - 1;
                Value assigned = assignIntegralBack(target.type(), next);
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
        LValue target = resolveLValue(ctx.expression(0));
        Value oldValue = target.get();
        int old = requireIntegral(oldValue);
        int next = op.equals("++") ? old + 1 : old - 1;
        Value assigned = assignIntegralBack(target.type(), next);
        target.set(assigned);
        return oldValue;
    }

    private Value evalTernaryExpression(MiniJavaParser.ExpressionContext ctx) {
        boolean cond = requireBoolean(requireNonVoid(visit(ctx.expression(0))));
        return cond ? requireNonVoid(visit(ctx.expression(1))) : requireNonVoid(visit(ctx.expression(2)));
    }

    private Value evalLogicalAnd(MiniJavaParser.ExpressionContext ctx) {
        Value left = requireNonVoid(visit(ctx.expression(0)));
        boolean lb = requireBoolean(left);
        if (!lb) {
            return Value.ofBoolean(false);
        }
        boolean rb = requireBoolean(requireNonVoid(visit(ctx.expression(1))));
        return Value.ofBoolean(rb);
    }

    private Value evalLogicalOr(MiniJavaParser.ExpressionContext ctx) {
        Value left = requireNonVoid(visit(ctx.expression(0)));
        boolean lb = requireBoolean(left);
        if (lb) {
            return Value.ofBoolean(true);
        }
        boolean rb = requireBoolean(requireNonVoid(visit(ctx.expression(1))));
        return Value.ofBoolean(rb);
    }

    private Value evalAssignmentExpression(MiniJavaParser.ExpressionContext ctx, String op) {
        LValue target = resolveLValue(ctx.expression(0));
        Type targetType = target.type();

        Value assigned;
        if ("=".equals(op)) {
            Value right = requireNonVoid(visit(ctx.expression(1)));
            assigned = TypeSystem.coerceForAssignment(targetType, right);
            target.set(assigned);
            return assigned;
        }

        // Compound assignment follows Java-like evaluation order:
        // capture current LHS value before evaluating RHS side effects.
        Value left = target.get();
        Value right = requireNonVoid(visit(ctx.expression(1)));
        if ("+=".equals(op) && targetType.equals(Type.STRING)) {
            if (!isStringConcatOperand(right)) {
                throw new RuntimeEvalException("Invalid string concatenation operand");
            }
            assigned = Value.ofString(left.asString() + right.toOutputString());
            target.set(assigned);
            return assigned;
        }

        if (!targetType.isIntegralScalar()) {
            throw new RuntimeEvalException("Unsupported assignment target for operator " + op);
        }

        int lv = requireIntegral(left);
        int rv = requireIntegral(right);
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

        assigned = assignIntegralBack(targetType, result);
        target.set(assigned);
        return assigned;
    }

    private Value evalBinaryExpression(MiniJavaParser.ExpressionContext ctx, String op) {
        Value left = requireNonVoid(visit(ctx.expression(0)));
        Value right = requireNonVoid(visit(ctx.expression(1)));

        return switch (op) {
            case "*" -> Value.ofInt(requireIntegral(left) * requireIntegral(right));
            case "/" -> {
                int rv = requireIntegral(right);
                if (rv == 0) {
                    throw new RuntimeEvalException("Division by zero");
                }
                yield Value.ofInt(requireIntegral(left) / rv);
            }
            case "%" -> {
                int rv = requireIntegral(right);
                if (rv == 0) {
                    throw new RuntimeEvalException("Division by zero");
                }
                yield Value.ofInt(requireIntegral(left) % rv);
            }
            case "+" -> {
                if (left.isString() || right.isString()) {
                    if (!isStringConcatOperand(left) || !isStringConcatOperand(right)) {
                        throw new RuntimeEvalException("Invalid string concatenation operands");
                    }
                    yield Value.ofString(left.toOutputString() + right.toOutputString());
                }
                yield Value.ofInt(requireIntegral(left) + requireIntegral(right));
            }
            case "-" -> Value.ofInt(requireIntegral(left) - requireIntegral(right));
            case "<<" -> Value.ofInt(requireIntegral(left) << requireIntegral(right));
            case ">>" -> Value.ofInt(requireIntegral(left) >> requireIntegral(right));
            case ">>>" -> Value.ofInt(requireIntegral(left) >>> requireIntegral(right));
            case "<" -> Value.ofBoolean(requireIntegral(left) < requireIntegral(right));
            case "<=" -> Value.ofBoolean(requireIntegral(left) <= requireIntegral(right));
            case ">" -> Value.ofBoolean(requireIntegral(left) > requireIntegral(right));
            case ">=" -> Value.ofBoolean(requireIntegral(left) >= requireIntegral(right));
            case "==" -> Value.ofBoolean(equalsValue(left, right));
            case "!=" -> Value.ofBoolean(!equalsValue(left, right));
            case "&" -> Value.ofInt(requireIntegral(left) & requireIntegral(right));
            case "^" -> Value.ofInt(requireIntegral(left) ^ requireIntegral(right));
            case "|" -> Value.ofInt(requireIntegral(left) | requireIntegral(right));
            default -> throw new RuntimeEvalException("Unsupported operator: " + op);
        };
    }

    private boolean equalsValue(Value left, Value right) {
        if (left.isNull() && right.isNull()) {
            if (left.hasNullTypeHint() && right.hasNullTypeHint()
                    && !left.nullTypeHint().equals(right.nullTypeHint())) {
                throw new RuntimeEvalException("Incompatible array types for equality");
            }
            return true;
        }
        if (left.isNull() || right.isNull()) {
            Value nullValue = left.isNull() ? left : right;
            Value nonNull = left.isNull() ? right : left;
            if (nonNull.isArray()) {
                if (nullValue.hasNullTypeHint() && !nullValue.nullTypeHint().equals(nonNull.type())) {
                    throw new RuntimeEvalException("Incompatible array types for equality");
                }
                return false;
            }
            throw new RuntimeEvalException("Incompatible types for equality");
        }
        if (left.isIntegral() && right.isIntegral()) {
            return left.toIntWithPromotion() == right.toIntWithPromotion();
        }
        if (left.isBoolean() && right.isBoolean()) {
            return left.asBoolean() == right.asBoolean();
        }
        if (left.isString() && right.isString()) {
            return left.asString().equals(right.asString());
        }
        if (left.isArray() && right.isArray()) {
            if (!left.type().equals(right.type())) {
                throw new RuntimeEvalException("Incompatible array types for equality");
            }
            return left.asArray() == right.asArray();
        }
        throw new RuntimeEvalException("Incompatible types for equality");
    }

    private Value readArrayElement(MiniJavaParser.ExpressionContext ctx) {
        ArrayLValue lv = resolveArrayLValue(ctx);
        return lv.get();
    }

    private LValue resolveLValue(MiniJavaParser.ExpressionContext expr) {
        if (expr.primary() != null && expr.primary().identifier() != null) {
            String name = expr.primary().identifier().getText();
            MiniJavaObject obj = context.resolve(name);
            return new VariableLValue(obj);
        }
        if (isArrayAccessExpr(expr)) {
            return resolveArrayLValue(expr);
        }
        throw new RuntimeEvalException("Left-hand side must be a variable or array element");
    }

    private ArrayLValue resolveArrayLValue(MiniJavaParser.ExpressionContext expr) {
        Value arrayValue = requireNonVoid(visit(expr.expression(0)));
        if (arrayValue.isNull()) {
            throw new RuntimeEvalException("Null pointer");
        }
        if (!arrayValue.isArray()) {
            throw new RuntimeEvalException("Not an array");
        }
        int index = requireIndex(requireNonVoid(visit(expr.expression(1))));
        MiniJavaArray arr = arrayValue.asArray();
        Type elemType = arr.type().componentType();
        return new ArrayLValue(arr, index, elemType);
    }

    private Value evalMethodCall(MiniJavaParser.MethodCallContext ctx) {
        String name = ctx.identifier().getText();
        List<Value> args = new ArrayList<>();
        if (ctx.arguments().expressionList() != null) {
            for (MiniJavaParser.ExpressionContext expr : ctx.arguments().expressionList().expression()) {
                args.add(requireNonVoid(visit(expr)));
            }
        }

        BuiltinResult builtin = invokeBuiltinIfMatched(name, args);
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
                    visit(stmt);
                }
            } catch (ReturnSignal r) {
                return handleReturnSignal(method, r);
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

    private Value handleReturnSignal(MethodDecl method, ReturnSignal signal) {
        if (method.returnType().equals(Type.VOID)) {
            if (signal.hasValue()) {
                throw new RuntimeEvalException("void method cannot return a value");
            }
            return Value.voidValue();
        }
        if (!signal.hasValue()) {
            throw new RuntimeEvalException("non-void method must return a value");
        }
        return TypeSystem.coerceForAssignment(method.returnType(), signal.value());
    }

    private Value evalCreator(MiniJavaParser.CreatorContext ctx) {
        Type base = Type.fromKeyword(ctx.createdName().primitiveType().getText());
        MiniJavaParser.ArrayCreatorRestContext rest = ctx.arrayCreatorRest();

        int totalDims = rest.LBRACK().size();
        Type arrayType = base;
        for (int i = 0; i < totalDims; i++) {
            arrayType = arrayType.arrayOf();
        }

        if (rest.arrayInitializer() != null) {
            return evalArrayInitializer(rest.arrayInitializer(), arrayType);
        }

        List<Integer> sizes = new ArrayList<>();
        for (MiniJavaParser.ExpressionContext sizeExpr : rest.expression()) {
            int size = requireIntegral(requireNonVoid(visit(sizeExpr)));
            if (size < 0) {
                throw new RuntimeEvalException("Negative array size");
            }
            sizes.add(size);
        }
        if (sizes.isEmpty()) {
            throw new RuntimeEvalException("Invalid array creation");
        }
        MiniJavaArray arr = createArrayByDimensions(arrayType, sizes, 0);
        return Value.ofArray(arr);
    }

    private MiniJavaArray createArrayByDimensions(Type arrayType, List<Integer> sizes, int depth) {
        int size = sizes.get(depth);
        MiniJavaArray arr = new MiniJavaArray(arrayType, size);
        if (depth == sizes.size() - 1) {
            return arr;
        }
        Type childType = arrayType.componentType();
        for (int i = 0; i < size; i++) {
            MiniJavaArray child = createArrayByDimensions(childType, sizes, depth + 1);
            arr.set(i, Value.ofArray(child));
        }
        return arr;
    }

    private Value evalVariableInitializer(MiniJavaParser.VariableInitializerContext ctx, Type targetType) {
        if (ctx.arrayInitializer() != null) {
            return evalArrayInitializer(ctx.arrayInitializer(), targetType);
        }
        return TypeSystem.coerceForAssignment(targetType, requireNonVoid(visit(ctx.expression())));
    }

    private Value evalArrayInitializer(MiniJavaParser.ArrayInitializerContext ctx, Type targetType) {
        if (!targetType.isArray()) {
            throw new RuntimeEvalException("Array initializer requires array type");
        }
        Type elementType = targetType.componentType();
        List<Value> values = new ArrayList<>();
        for (MiniJavaParser.VariableInitializerContext child : ctx.variableInitializer()) {
            values.add(evalVariableInitializer(child, elementType));
        }
        return Value.ofArray(new MiniJavaArray(targetType, values));
    }

    private Value parseLiteral(MiniJavaParser.LiteralContext literal) {
        if (literal.DECIMAL_LITERAL() != null) {
            String text = literal.DECIMAL_LITERAL().getText();
            text = text.replace("_", "");
            if (text.endsWith("l") || text.endsWith("L")) {
                text = text.substring(0, text.length() - 1);
            }
            try {
                return Value.ofDecimalLiteral(Integer.parseInt(text));
            } catch (NumberFormatException e) {
                throw new RuntimeEvalException("Invalid integer literal: " + text, e);
            }
        }
        if (literal.CHAR_LITERAL() != null) {
            return Value.ofChar(parseCharLiteral(literal.CHAR_LITERAL().getText()));
        }
        if (literal.STRING_LITERAL() != null) {
            return Value.ofString(parseStringLiteral(literal.STRING_LITERAL().getText()));
        }
        if (literal.BOOL_LITERAL() != null) {
            return Value.ofBoolean(Boolean.parseBoolean(literal.BOOL_LITERAL().getText()));
        }
        if (literal.NULL_LITERAL() != null) {
            return Value.nullValue();
        }
        throw new RuntimeEvalException("Unsupported literal");
    }

    private int parseCharLiteral(String text) {
        if (text.length() < 3 || text.charAt(0) != '\'' || text.charAt(text.length() - 1) != '\'') {
            throw new RuntimeEvalException("Invalid char literal");
        }
        String core = text.substring(1, text.length() - 1);
        if (core.length() != 1) {
            throw new RuntimeEvalException("Escaped char is unsupported");
        }
        return core.charAt(0);
    }

    private String parseStringLiteral(String text) {
        if (text.length() < 2 || text.charAt(0) != '"' || text.charAt(text.length() - 1) != '"') {
            throw new RuntimeEvalException("Invalid string literal");
        }
        return text.substring(1, text.length() - 1);
    }

    private Type parseType(MiniJavaParser.TypeTypeContext ctx) {
        Type type = Type.fromKeyword(ctx.primitiveType().getText());
        int dims = ctx.LBRACK().size();
        for (int i = 0; i < dims; i++) {
            type = type.arrayOf();
        }
        return type;
    }

    private boolean isArrayAccessExpr(MiniJavaParser.ExpressionContext ctx) {
        return ctx.LBRACK() != null && ctx.expression().size() == 2 && ctx.bop == null;
    }

    private boolean isCastExpr(MiniJavaParser.ExpressionContext ctx) {
        return ctx.typeType() != null && ctx.expression().size() == 1 && ctx.bop == null;
    }

    private boolean isAssignmentOperator(String op) {
        return "=".equals(op)
                || "+=".equals(op)
                || "-=".equals(op)
                || "*=".equals(op)
                || "/=".equals(op)
                || "%=".equals(op)
                || "&=".equals(op)
                || "|=".equals(op)
                || "^=".equals(op)
                || "<<=".equals(op)
                || ">>=".equals(op)
                || ">>>=".equals(op);
    }

    private Value requireNonVoid(Value value) {
        if (value == null) {
            throw new RuntimeEvalException("Invalid void context");
        }
        if (value.isVoid()) {
            throw new RuntimeEvalException("void value is not allowed here");
        }
        return value;
    }

    private boolean requireBoolean(Value value) {
        if (!value.isBoolean()) {
            throw new RuntimeEvalException("Expected boolean");
        }
        return value.asBoolean();
    }

    private int requireIntegral(Value value) {
        if (!value.isIntegral()) {
            throw new RuntimeEvalException("Expected integral type");
        }
        return value.toIntWithPromotion();
    }

    private int requireIndex(Value value) {
        if (value.isInt()) {
            return value.asInt();
        }
        if (value.isChar()) {
            return value.asSignedCharInt();
        }
        throw new RuntimeEvalException("Array index must be int");
    }

    private boolean isStringConcatOperand(Value value) {
        return value.isString() || value.isInt() || value.isChar() || value.isBoolean();
    }

    private Value assignIntegralBack(Type targetType, int value) {
        if (targetType.equals(Type.INT)) {
            return Value.ofInt(value);
        }
        if (targetType.equals(Type.CHAR)) {
            return Value.ofChar(value);
        }
        throw new RuntimeEvalException("Integral assignment target must be int or char");
    }

    private BuiltinResult invokeBuiltinIfMatched(String name, List<Value> args) {
        return switch (name) {
            case "print" -> builtinPrint(args);
            case "println" -> builtinPrintln(args);
            case "assert" -> builtinAssert(args);
            case "length" -> builtinLength(args);
            case "to_char_array" -> builtinToCharArray(args);
            case "to_string" -> builtinToString(args);
            case "atoi" -> builtinAtoi(args);
            case "itoa" -> builtinItoa(args);
            default -> BuiltinResult.notMatched();
        };
    }

    private BuiltinResult builtinPrint(List<Value> args) {
        if (args.size() != 1) {
            return BuiltinResult.notMatched();
        }
        context.out().print(requireNonVoid(args.get(0)).toOutputString());
        return BuiltinResult.matched(Value.voidValue());
    }

    private BuiltinResult builtinPrintln(List<Value> args) {
        if (args.isEmpty()) {
            context.out().println();
            return BuiltinResult.matched(Value.voidValue());
        }
        if (args.size() == 1) {
            context.out().println(requireNonVoid(args.get(0)).toOutputString());
            return BuiltinResult.matched(Value.voidValue());
        }
        return BuiltinResult.notMatched();
    }

    private BuiltinResult builtinAssert(List<Value> args) {
        if (args.size() != 1) {
            return BuiltinResult.notMatched();
        }
        boolean cond = requireBoolean(requireNonVoid(args.get(0)));
        if (!cond) {
            throw new ExitSignal(33);
        }
        return BuiltinResult.matched(Value.voidValue());
    }

    private BuiltinResult builtinLength(List<Value> args) {
        if (args.size() != 1) {
            return BuiltinResult.notMatched();
        }
        Value arg = requireNonVoid(args.get(0));
        if (arg.isString()) {
            return BuiltinResult.matched(Value.ofInt(arg.asString().length()));
        }
        if (arg.isNull()) {
            throw new RuntimeEvalException("Null pointer");
        }
        if (arg.isArray()) {
            return BuiltinResult.matched(Value.ofInt(arg.asArray().length()));
        }
        return BuiltinResult.notMatched();
    }

    private BuiltinResult builtinToCharArray(List<Value> args) {
        if (args.size() != 1) {
            return BuiltinResult.notMatched();
        }
        Value arg = requireNonVoid(args.get(0));
        if (!arg.isString()) {
            return BuiltinResult.notMatched();
        }
        String s = arg.asString();
        List<Value> elems = new ArrayList<>(s.length());
        for (int i = 0; i < s.length(); i++) {
            elems.add(Value.ofChar(s.charAt(i)));
        }
        return BuiltinResult.matched(Value.ofArray(new MiniJavaArray(Type.CHAR.arrayOf(), elems)));
    }

    private BuiltinResult builtinToString(List<Value> args) {
        if (args.size() != 1) {
            return BuiltinResult.notMatched();
        }
        Value arg = requireNonVoid(args.get(0));
        if (arg.isNull()) {
            throw new RuntimeEvalException("Null pointer");
        }
        if (!arg.isArray() || !arg.type().equals(Type.CHAR.arrayOf())) {
            return BuiltinResult.notMatched();
        }
        MiniJavaArray arr = arg.asArray();
        StringBuilder sb = new StringBuilder(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            Value elem = arr.get(i);
            if (!elem.isChar()) {
                throw new RuntimeEvalException("to_string expects char[]");
            }
            sb.append((char) (elem.asSignedCharInt() & 0xFF));
        }
        return BuiltinResult.matched(Value.ofString(sb.toString()));
    }

    private BuiltinResult builtinAtoi(List<Value> args) {
        if (args.size() != 1) {
            return BuiltinResult.notMatched();
        }
        Value arg = requireNonVoid(args.get(0));
        if (!arg.isString()) {
            return BuiltinResult.notMatched();
        }
        try {
            return BuiltinResult.matched(Value.ofInt(Integer.parseInt(arg.asString())));
        } catch (NumberFormatException e) {
            throw new RuntimeEvalException("Invalid integer format", e);
        }
    }

    private BuiltinResult builtinItoa(List<Value> args) {
        if (args.size() != 1) {
            return BuiltinResult.notMatched();
        }
        Value arg = requireNonVoid(args.get(0));
        if (arg.isInt()) {
            return BuiltinResult.matched(Value.ofString(String.valueOf(arg.asInt())));
        }
        if (arg.isChar()) {
            return BuiltinResult.matched(Value.ofString(String.valueOf(arg.asSignedCharInt())));
        }
        return BuiltinResult.notMatched();
    }

    private interface LValue {
        Type type();

        Value get();

        void set(Value value);
    }

    private static final class VariableLValue implements LValue {
        private final MiniJavaObject object;

        private VariableLValue(MiniJavaObject object) {
            this.object = object;
        }

        @Override
        public Type type() {
            return object.declaredType();
        }

        @Override
        public Value get() {
            return object.value();
        }

        @Override
        public void set(Value value) {
            object.setValue(value);
        }
    }

    private static final class ArrayLValue implements LValue {
        private final MiniJavaArray array;
        private final int index;
        private final Type elementType;

        private ArrayLValue(MiniJavaArray array, int index, Type elementType) {
            this.array = array;
            this.index = index;
            this.elementType = elementType;
        }

        @Override
        public Type type() {
            return elementType;
        }

        @Override
        public Value get() {
            return array.get(index);
        }

        @Override
        public void set(Value value) {
            array.set(index, value);
        }
    }

    private record BuiltinResult(boolean matched, Value value) {
        static BuiltinResult matched(Value value) {
            return new BuiltinResult(true, value);
        }

        static BuiltinResult notMatched() {
            return new BuiltinResult(false, null);
        }
    }
}
