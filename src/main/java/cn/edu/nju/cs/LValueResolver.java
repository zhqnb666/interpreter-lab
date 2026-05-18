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

    /**
     * Read an array element with its declared element-type as the static type.
     * Without this carry, downstream member access on the result would (wrongly)
     * use the value's runtime type as decl(arr[idx]) and resolve fields on the
     * wrong layer when the array holds a subclass instance.
     */
    ExprResult readArrayElement(MiniJavaParser.ExpressionContext ctx) {
        ArrayLValue lv = resolveArrayLValue(ctx);
        return ExprResult.of(lv.get(), lv.type());
    }

    LValue resolveLValue(MiniJavaParser.ExpressionContext expr) {
        if (expr.primary() != null && expr.primary().identifier() != null) {
            String name = expr.primary().identifier().getText();
            Variable obj = context.tryResolve(name);
            if (obj != null) {
                return new VariableLValue(obj);
            }
            // Fall back to `this.<name>` field assignment when inside a class method.
            EvalContext.ClassFrame frame = context.currentClassFrame();
            if (frame != null) {
                ClassRegistry.FieldOwner owner =
                        visitor.classRegistry().findField(frame.declaringClass(), name);
                if (owner != null) {
                    return new FieldLValue(frame.instance(), owner.className(),
                            name, owner.field().type());
                }
            }
            throw new RuntimeEvalException("Undeclared identifier: " + name);
        }
        if (isArrayAccessExpr(expr)) {
            return resolveArrayLValue(expr);
        }
        if (isFieldAccessExpr(expr)) {
            return resolveFieldLValue(expr);
        }
        throw new RuntimeEvalException("Left-hand side must be a variable, array element, or field");
    }

    boolean isArrayAccessExpr(MiniJavaParser.ExpressionContext ctx) {
        return ctx.LBRACK() != null && ctx.expression().size() == 2 && ctx.bop == null;
    }

    private static boolean isFieldAccessExpr(MiniJavaParser.ExpressionContext ctx) {
        return ctx.bop != null
                && ".".equals(ctx.bop.getText())
                && ctx.identifier() != null;
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

    private FieldLValue resolveFieldLValue(MiniJavaParser.ExpressionContext expr) {
        String fieldName = expr.identifier().getText();
        MiniJavaParser.ExpressionContext recvExpr = expr.expression(0);

        ClassInstance instance;
        String startClass;
        if (ExpressionEvaluator.isThisPrimary(recvExpr)) {
            EvalContext.ClassFrame frame = context.currentClassFrame();
            if (frame == null) {
                throw new RuntimeEvalException("'this' used outside class context");
            }
            instance = frame.instance();
            startClass = frame.declaringClass();
        } else if (ExpressionEvaluator.isSuperPrimary(recvExpr)) {
            EvalContext.ClassFrame frame = context.currentClassFrame();
            if (frame == null) {
                throw new RuntimeEvalException("'super' used outside class context");
            }
            ClassDecl decl = visitor.classRegistry().get(frame.declaringClass());
            if (decl.parentName() == null) {
                throw new RuntimeEvalException(frame.declaringClass() + " has no superclass");
            }
            instance = frame.instance();
            startClass = decl.parentName();
        } else {
            ExprResult r = visitor.evalExpr(recvExpr);
            Value v = r.valueNonVoid();
            if (v.isNull()) {
                throw new RuntimeEvalException("Null pointer");
            }
            if (!v.isClassInstance()) {
                throw new RuntimeEvalException("Field access on non-class type");
            }
            instance = v.asClassInstance();
            if (r.staticType() == null || !r.staticType().isClass()) {
                throw new RuntimeEvalException("Field access on non-class type");
            }
            startClass = r.staticType().className();
        }

        ClassRegistry.FieldOwner owner =
                visitor.classRegistry().findField(startClass, fieldName);
        if (owner == null) {
            throw new RuntimeEvalException(
                    "No field '" + fieldName + "' in class " + startClass);
        }
        return new FieldLValue(instance, owner.className(), fieldName, owner.field().type());
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

    private static final class FieldLValue implements LValue {
        private final ClassInstance instance;
        private final String ownerClass;
        private final String fieldName;
        private final Type fieldType;

        private FieldLValue(ClassInstance instance, String ownerClass, String fieldName, Type fieldType) {
            this.instance = instance;
            this.ownerClass = ownerClass;
            this.fieldName = fieldName;
            this.fieldType = fieldType;
        }

        @Override
        public Type type() {
            return fieldType;
        }

        @Override
        public Value get() {
            return instance.getField(ownerClass, fieldName);
        }

        @Override
        public void set(Value value) {
            instance.setField(ownerClass, fieldName, value);
        }
    }
}
