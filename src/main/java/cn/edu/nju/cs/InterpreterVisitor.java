package cn.edu.nju.cs;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

public class InterpreterVisitor extends MiniJavaParserBaseVisitor<Value> {
    private final EvalContext context;
    private final MethodRegistry methodRegistry = new MethodRegistry();
    private final CallDispatcher callDispatcher;
    private final StatementExecutor statementExecutor;
    private final ExpressionEvaluator expressionEvaluator;

    public InterpreterVisitor(PrintStream out) {
        this.context = new EvalContext(out);
        BuiltinLibrary builtinLibrary = new BuiltinLibrary(context);
        LValueResolver lvalueResolver = new LValueResolver(this, context);
        this.callDispatcher = new CallDispatcher(this, context, methodRegistry, builtinLibrary);
        this.statementExecutor = new StatementExecutor(this, context);
        this.expressionEvaluator = new ExpressionEvaluator(this, lvalueResolver, callDispatcher);
    }

    public int execute(MiniJavaParser.CompilationUnitContext ctx) {
        visitCompilationUnit(ctx);
        MethodDecl entry = methodRegistry.resolveEntryMain();
        Value result = callDispatcher.invokeEntry(entry);
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
            Value init = visit(ctx.expression()).requireNonVoid();
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
        return statementExecutor.executeStatement(ctx);
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
        return expressionEvaluator.evalExpression(ctx);
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

    Value evalCreator(MiniJavaParser.CreatorContext ctx) {
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
            int size = evalInt(sizeExpr);
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
        return TypeSystem.coerceForAssignment(targetType, visit(ctx.expression()).requireNonVoid());
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
            return Value.untypedNull();
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

    Type parseType(MiniJavaParser.TypeTypeContext ctx) {
        Type type = Type.fromKeyword(ctx.primitiveType().getText());
        int dims = ctx.LBRACK().size();
        for (int i = 0; i < dims; i++) {
            type = type.arrayOf();
        }
        return type;
    }

    int evalInt(MiniJavaParser.ExpressionContext ctx) {
        return visit(ctx).requireNonVoid().requireIntegral();
    }

    boolean evalBool(MiniJavaParser.ExpressionContext ctx) {
        return visit(ctx).requireNonVoid().requireBoolean();
    }
}
