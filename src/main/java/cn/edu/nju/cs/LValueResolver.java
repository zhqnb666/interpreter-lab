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
            MiniJavaObject obj = context.resolve(name);
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
        Value arrayValue = visitor.requireNonVoid(visitor.visit(expr.expression(0)));
        if (arrayValue.isNull()) {
            throw new RuntimeEvalException("Null pointer");
        }
        if (!arrayValue.isArray()) {
            throw new RuntimeEvalException("Not an array");
        }
        int index = visitor.requireIndex(visitor.requireNonVoid(visitor.visit(expr.expression(1))));
        MiniJavaArray arr = arrayValue.asArray();
        Type elemType = arr.type().componentType();
        return new ArrayLValue(arr, index, elemType);
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
}
