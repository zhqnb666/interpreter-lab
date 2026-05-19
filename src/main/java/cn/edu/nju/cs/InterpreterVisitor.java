package cn.edu.nju.cs;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

public class InterpreterVisitor extends MiniJavaParserBaseVisitor<Value> {
    private final EvalContext context;
    private final MethodRegistry methodRegistry = new MethodRegistry();
    private final ClassRegistry classRegistry = new ClassRegistry();
    private final CallDispatcher callDispatcher;
    private final StatementExecutor statementExecutor;
    private final ExpressionEvaluator expressionEvaluator;

    public InterpreterVisitor(PrintStream out) {
        this.context = new EvalContext(out);
        TypeSystem.setClassRegistry(classRegistry);
        BuiltinLibrary builtinLibrary = new BuiltinLibrary(context);
        LValueResolver lvalueResolver = new LValueResolver(this, context);
        this.callDispatcher = new CallDispatcher(this, context, methodRegistry, builtinLibrary);
        this.callDispatcher.setClassRegistry(classRegistry);
        builtinLibrary.setDispatch(classRegistry, callDispatcher);
        this.statementExecutor = new StatementExecutor(this, context);
        this.expressionEvaluator = new ExpressionEvaluator(this, lvalueResolver, callDispatcher);
    }

    EvalContext context() {
        return context;
    }

    ClassRegistry classRegistry() {
        return classRegistry;
    }

    public int execute(MiniJavaParser.CompilationUnitContext ctx) {
        visitCompilationUnit(ctx);
        MethodDecl entry = methodRegistry.resolveEntryMain();
        ExprResult result = callDispatcher.invokeEntry(entry);
        Value value = result.value();
        if (!value.isInt()) {
            throw new RuntimeEvalException("Entry main() must return int");
        }
        return value.asInt();
    }

    @Override
    public Value visitCompilationUnit(MiniJavaParser.CompilationUnitContext ctx) {
        // Pass 1: register class names + parent links so types can resolve forward refs.
        for (MiniJavaParser.ClassDeclarationContext classCtx : ctx.classDeclaration()) {
            String name = classCtx.identifier().getText();
            String parent = classCtx.parentClassDeclaration() != null
                    ? classCtx.parentClassDeclaration().identifier().getText()
                    : null;
            classRegistry.register(new ClassDecl(name, parent));
        }
        classRegistry.validateInheritance();

        // Pass 2: parse class bodies (fields, methods, constructors).
        for (MiniJavaParser.ClassDeclarationContext classCtx : ctx.classDeclaration()) {
            populateClassBody(classCtx);
        }
        // Top-level methods.
        for (MiniJavaParser.MethodDeclarationContext methodCtx : ctx.methodDeclaration()) {
            methodRegistry.register(buildMethodDecl(methodCtx));
        }
        return null;
    }

    private void populateClassBody(MiniJavaParser.ClassDeclarationContext classCtx) {
        String className = classCtx.identifier().getText();
        ClassDecl decl = classRegistry.get(className);
        for (MiniJavaParser.ClassBodyDeclarationContext member : classCtx.classBody().classBodyDeclaration()) {
            if (member.fieldDeclaration() != null) {
                MiniJavaParser.FieldDeclarationContext fd = member.fieldDeclaration();
                Type fieldType = parseType(fd.typeType());
                if (fieldType.isVoid()) {
                    throw new RuntimeEvalException("Field cannot be void");
                }
                String fieldName = fd.variableDeclarator().identifier().getText();
                decl.addField(new ClassDecl.FieldDecl(fieldType, fieldName, fd.variableDeclarator()));
            } else if (member.methodDeclaration() != null) {
                decl.addMethod(buildMethodDecl(member.methodDeclaration()));
            } else if (member.constructorDeclaration() != null) {
                MiniJavaParser.ConstructorDeclarationContext cc = member.constructorDeclaration();
                String ctorName = cc.identifier().getText();
                if (!ctorName.equals(className)) {
                    throw new RuntimeEvalException(
                            "Constructor name " + ctorName + " does not match class " + className);
                }
                List<MethodDecl.Parameter> params = parseFormalParameters(cc.formalParameters());
                decl.addConstructor(new ConstructorDecl(className, params, cc.constructorBody));
            }
        }
        if (decl.constructors().isEmpty()) {
            decl.addConstructor(new ConstructorDecl(className, List.of(), null));
        }
    }

    private MethodDecl buildMethodDecl(MiniJavaParser.MethodDeclarationContext ctx) {
        Type returnType = ctx.VOID() != null ? Type.VOID : parseType(ctx.typeType());
        String name = ctx.identifier().getText();
        List<MethodDecl.Parameter> params = parseFormalParameters(ctx.formalParameters());
        return new MethodDecl(name, returnType, params, ctx.block());
    }

    private List<MethodDecl.Parameter> parseFormalParameters(MiniJavaParser.FormalParametersContext ctx) {
        List<MethodDecl.Parameter> params = new ArrayList<>();
        MiniJavaParser.FormalParameterListContext plist = ctx.formalParameterList();
        if (plist != null) {
            for (MiniJavaParser.FormalParameterContext pctx : plist.formalParameter()) {
                Type paramType = parseType(pctx.typeType());
                if (paramType.isVoid()) {
                    throw new RuntimeEvalException("Parameter cannot be void");
                }
                params.add(new MethodDecl.Parameter(paramType, pctx.identifier().getText()));
            }
        }
        return params;
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
            ExprResult initR = evalExpr(ctx.expression());
            Value init = initR.valueNonVoid();
            // Inference order: decimal literal -> int; otherwise the expression's static
            // type (which is always set except for the bare null literal); only when the
            // null literal supplies no type info do we reject.
            Type inferred;
            if (init.isDecimalLiteral()) {
                inferred = Type.INT;
            } else if (initR.staticType() != null) {
                inferred = initR.staticType();
            } else if (init.isNull()) {
                throw new RuntimeEvalException("Cannot infer type from null");
            } else {
                inferred = init.type();
            }
            Value coerced = TypeSystem.coerceForAssignment(inferred, initR);
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
        return evalExpr(ctx).value();
    }

    ExprResult evalExpr(MiniJavaParser.ExpressionContext ctx) {
        return expressionEvaluator.evalExpression(ctx);
    }

    @Override
    public Value visitPrimary(MiniJavaParser.PrimaryContext ctx) {
        return evalPrimary(ctx).value();
    }

    ExprResult evalPrimary(MiniJavaParser.PrimaryContext ctx) {
        if (ctx.expression() != null) {
            return evalExpr(ctx.expression());
        }
        if (ctx.literal() != null) {
            return parseLiteral(ctx.literal());
        }
        if (ctx.THIS() != null) {
            throw new RuntimeEvalException(
                    "'this' may only appear in field access, method call, or constructor invocation");
        }
        if (ctx.SUPER() != null) {
            throw new RuntimeEvalException(
                    "'super' may only appear in field access, method call, or constructor invocation");
        }
        if (ctx.identifier() != null) {
            String name = ctx.identifier().getText();
            Variable variable = context.tryResolve(name);
            if (variable != null) {
                return ExprResult.of(variable.value(), variable.declaredType());
            }
            // Fall back to `this.<name>` field access when inside a class method.
            EvalContext.ClassFrame frame = context.currentClassFrame();
            if (frame != null) {
                ClassRegistry.FieldOwner owner = classRegistry.findField(frame.declaringClass(), name);
                if (owner != null) {
                    Value v = frame.instance().getField(owner.className(), name);
                    return ExprResult.of(v, owner.field().type());
                }
            }
            throw new RuntimeEvalException("Undeclared identifier: " + name);
        }
        throw new RuntimeEvalException("Invalid primary expression");
    }

    ExprResult evalCreator(MiniJavaParser.CreatorContext ctx) {
        // Class instance creation: `new C(args)`
        if (ctx.classCreatorRest() != null) {
            String name;
            if (ctx.createdName().identifier() != null) {
                name = ctx.createdName().identifier().getText();
            } else {
                throw new RuntimeEvalException("Cannot construct primitive via new");
            }
            if (!classRegistry.exists(name)) {
                throw new RuntimeEvalException("Unknown class: " + name);
            }
            List<ExprResult> args = new ArrayList<>();
            if (ctx.classCreatorRest().expressionList() != null) {
                for (MiniJavaParser.ExpressionContext e : ctx.classCreatorRest().expressionList().expression()) {
                    ExprResult r = evalExpr(e);
                    r.valueNonVoid();
                    args.add(r);
                }
            }
            return callDispatcher.invokeNewClass(name, args);
        }

        // Array creation: `new T[N]` / `new T[]{...}` for primitive *or* class element type.
        Type base;
        if (ctx.createdName().primitiveType() != null) {
            base = Type.fromKeyword(ctx.createdName().primitiveType().getText());
        } else {
            String name = ctx.createdName().identifier().getText();
            if (!classRegistry.exists(name)) {
                throw new RuntimeEvalException("Unknown class: " + name);
            }
            base = Type.ofClass(name);
        }
        MiniJavaParser.ArrayCreatorRestContext rest = ctx.arrayCreatorRest();

        int totalDims = rest.LBRACK().size();
        Type arrayType = base;
        for (int i = 0; i < totalDims; i++) {
            arrayType = arrayType.arrayOf();
        }

        if (rest.arrayInitializer() != null) {
            return ExprResult.of(evalArrayInitializer(rest.arrayInitializer(), arrayType), arrayType);
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
        return ExprResult.of(Value.ofArray(arr), arrayType);
    }

    Value evalFieldInitializer(MiniJavaParser.VariableInitializerContext ctx, Type targetType) {
        return evalVariableInitializer(ctx, targetType);
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
        ExprResult r = evalExpr(ctx.expression());
        r.valueNonVoid();
        return TypeSystem.coerceForAssignment(targetType, r);
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

    private ExprResult parseLiteral(MiniJavaParser.LiteralContext literal) {
        if (literal.DECIMAL_LITERAL() != null) {
            String text = literal.DECIMAL_LITERAL().getText();
            text = text.replace("_", "");
            if (text.endsWith("l") || text.endsWith("L")) {
                text = text.substring(0, text.length() - 1);
            }
            try {
                return ExprResult.of(Value.ofDecimalLiteral(Integer.parseInt(text)));
            } catch (NumberFormatException e) {
                throw new RuntimeEvalException("Invalid integer literal: " + text, e);
            }
        }
        if (literal.CHAR_LITERAL() != null) {
            return ExprResult.of(Value.ofChar(parseCharLiteral(literal.CHAR_LITERAL().getText())));
        }
        if (literal.STRING_LITERAL() != null) {
            return ExprResult.of(Value.ofString(parseStringLiteral(literal.STRING_LITERAL().getText())));
        }
        if (literal.BOOL_LITERAL() != null) {
            return ExprResult.of(Value.ofBoolean(Boolean.parseBoolean(literal.BOOL_LITERAL().getText())));
        }
        if (literal.NULL_LITERAL() != null) {
            return ExprResult.of(Value.untypedNull(), null);
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
        Type type;
        if (ctx.primitiveType() != null) {
            type = Type.fromKeyword(ctx.primitiveType().getText());
        } else if (ctx.identifier() != null) {
            String name = ctx.identifier().getText();
            if (!classRegistry.exists(name)) {
                throw new RuntimeEvalException("Unknown type: " + name);
            }
            type = Type.ofClass(name);
        } else {
            throw new RuntimeEvalException("Invalid type");
        }
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
