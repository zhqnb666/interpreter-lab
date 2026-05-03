package cn.edu.nju.cs;

final class LValueResolver {
    interface LValue {
        Type type();

        Value get();

        void set(Value value);
    }

    private final InterpreterVisitor visitor;
    private final EvalContext context;

    LValueResolver(InterpreterVisitor visitor, EvalContext context) {
        this.visitor = visitor;
        this.context = context;
    }

    Value readArrayElement(MiniJavaParser.ExpressionContext ctx) {
        return resolveArrayLValue(ctx).get();
    }

    LValue resolveLValue(MiniJavaParser.ExpressionContext expr) {
        if (expr.primary() != null && expr.primary().identifier() != null) {
            String name = expr.primary().identifier().getText();
            Variable obj = context.resolve(name);
            return new VariableLValue(obj);
        }
        if (isArrayAccessExpr(expr)) {
            return resolveArrayLValue(expr);
        }
        throw new RuntimeEvalException("Left-hand side must be a variable or array element");
    }

    boolean isArrayAccessExpr(MiniJavaParser.ExpressionContext ctx) {
        return ctx.LBRACK() != null && ctx.expression().size() == 2 && ctx.bop == null;
    }

    private ArrayLValue resolveArrayLValue(MiniJavaParser.ExpressionContext expr) {
        Value arrayValue = visitor.visit(expr.expression(0)).requireNonVoid();
        if (arrayValue.isNull()) {
            throw new RuntimeEvalException("Null pointer");
        }
        if (!arrayValue.isArray()) {
            throw new RuntimeEvalException("Not an array");
        }
        int index = visitor.visit(expr.expression(1)).requireNonVoid().requireIndex();
        MiniJavaArray arr = arrayValue.asArray();
        Type elemType = arr.type().componentType();
        return new ArrayLValue(arr, index, elemType);
    }

    private static final class VariableLValue implements LValue {
        private final Variable variable;

        private VariableLValue(Variable variable) {
            this.variable = variable;
        }

        @Override
        public Type type() {
            return variable.declaredType();
        }

        @Override
        public Value get() {
            return variable.value();
        }

        @Override
        public void set(Value value) {
            variable.setValue(value);
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
}
