package cn.edu.nju.cs;

import java.util.ArrayList;
import java.util.List;

final class BuiltinLibrary {
    record BuiltinResult(boolean matched, Value value) {
        static BuiltinResult matched(Value value) {
            return new BuiltinResult(true, value);
        }

        static BuiltinResult notMatched() {
            return new BuiltinResult(false, null);
        }
    }

    private final EvalContext context;
    private ClassRegistry classRegistry;
    private CallDispatcher callDispatcher;

    BuiltinLibrary(EvalContext context) {
        this.context = context;
    }

    void setDispatch(ClassRegistry classRegistry, CallDispatcher callDispatcher) {
        this.classRegistry = classRegistry;
        this.callDispatcher = callDispatcher;
    }

    BuiltinResult invokeIfMatched(String name, List<ExprResult> args) {
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

    private BuiltinResult builtinPrint(List<ExprResult> args) {
        if (args.size() != 1) {
            return BuiltinResult.notMatched();
        }
        context.out().print(callDispatcher.stringifyForOutput(args.get(0)));
        return BuiltinResult.matched(Value.voidValue());
    }

    private BuiltinResult builtinPrintln(List<ExprResult> args) {
        if (args.isEmpty()) {
            context.out().println();
            return BuiltinResult.matched(Value.voidValue());
        }
        if (args.size() == 1) {
            context.out().println(callDispatcher.stringifyForOutput(args.get(0)));
            return BuiltinResult.matched(Value.voidValue());
        }
        return BuiltinResult.notMatched();
    }

    private BuiltinResult builtinAssert(List<ExprResult> args) {
        if (args.size() != 1) {
            return BuiltinResult.notMatched();
        }
        boolean cond = args.get(0).valueNonVoid().requireBoolean();
        if (!cond) {
            throw new ExitSignal(33);
        }
        return BuiltinResult.matched(Value.voidValue());
    }

    private BuiltinResult builtinLength(List<ExprResult> args) {
        if (args.size() != 1) {
            return BuiltinResult.notMatched();
        }
        Value arg = args.get(0).valueNonVoid();
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

    private BuiltinResult builtinToCharArray(List<ExprResult> args) {
        if (args.size() != 1) {
            return BuiltinResult.notMatched();
        }
        Value arg = args.get(0).valueNonVoid();
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

    private BuiltinResult builtinToString(List<ExprResult> args) {
        if (args.size() != 1) {
            return BuiltinResult.notMatched();
        }
        Value arg = args.get(0).valueNonVoid();
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

    private BuiltinResult builtinAtoi(List<ExprResult> args) {
        if (args.size() != 1) {
            return BuiltinResult.notMatched();
        }
        Value arg = args.get(0).valueNonVoid();
        if (!arg.isString()) {
            return BuiltinResult.notMatched();
        }
        try {
            return BuiltinResult.matched(Value.ofInt(Integer.parseInt(arg.asString())));
        } catch (NumberFormatException e) {
            throw new RuntimeEvalException("Invalid integer format", e);
        }
    }

    private BuiltinResult builtinItoa(List<ExprResult> args) {
        if (args.size() != 1) {
            return BuiltinResult.notMatched();
        }
        Value arg = args.get(0).valueNonVoid();
        if (arg.isInt()) {
            return BuiltinResult.matched(Value.ofString(String.valueOf(arg.asInt())));
        }
        if (arg.isChar()) {
            return BuiltinResult.matched(Value.ofString(String.valueOf(arg.asSignedCharInt())));
        }
        return BuiltinResult.notMatched();
    }
}
