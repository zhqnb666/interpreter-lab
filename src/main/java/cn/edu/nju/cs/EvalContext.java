package cn.edu.nju.cs;

import java.io.PrintStream;
import java.util.ArrayDeque;
import java.util.Deque;

public final class EvalContext {
    private final Deque<ScopeFrame> scopes = new ArrayDeque<>();
    private final Deque<MethodDecl> callStack = new ArrayDeque<>();
    private final Deque<Integer> loopDepthStack = new ArrayDeque<>();
    private final Deque<Integer> scopeBaseStack = new ArrayDeque<>();
    private final PrintStream out;
    private int loopDepth = 0;

    public EvalContext(PrintStream out) {
        this.out = out;
    }

    public PrintStream out() {
        return out;
    }

    public void enterScope() {
        scopes.push(new ScopeFrame());
    }

    public void exitScope() {
        if (scopes.isEmpty()) {
            throw new RuntimeEvalException("No scope to exit");
        }
        scopes.pop();
    }

    public void declare(String name, Type type, Value value) {
        currentScope().declare(name, type, value);
    }

    public MiniJavaObject resolve(String name) {
        int visibleDepth = scopes.size() - currentMethodScopeBase();
        for (ScopeFrame scope : scopes) {
            if (visibleDepth <= 0) {
                break;
            }
            MiniJavaObject obj = scope.get(name);
            if (obj != null) {
                return obj;
            }
            visibleDepth--;
        }
        throw new RuntimeEvalException("Undeclared identifier: " + name);
    }

    public void pushLoop() {
        loopDepth++;
    }

    public void popLoop() {
        loopDepth--;
    }

    public boolean inLoop() {
        return loopDepth > 0;
    }

    public void pushMethod(MethodDecl method) {
        callStack.push(method);
        scopeBaseStack.push(scopes.size());
        loopDepthStack.push(loopDepth);
        loopDepth = 0;
    }

    public void popMethod() {
        if (callStack.isEmpty()) {
            throw new RuntimeEvalException("No method context to pop");
        }
        callStack.pop();
        if (scopeBaseStack.isEmpty()) {
            throw new RuntimeEvalException("No scope base context to pop");
        }
        int scopeBase = scopeBaseStack.pop();
        while (scopes.size() > scopeBase) {
            scopes.pop();
        }
        if (loopDepthStack.isEmpty()) {
            throw new RuntimeEvalException("No loop depth context to pop");
        }
        loopDepth = loopDepthStack.pop();
    }

    public MethodDecl currentMethod() {
        if (callStack.isEmpty()) {
            throw new RuntimeEvalException("No current method");
        }
        return callStack.peek();
    }

    private ScopeFrame currentScope() {
        if (scopes.isEmpty()) {
            throw new RuntimeEvalException("No active scope");
        }
        return scopes.peek();
    }

    private int currentMethodScopeBase() {
        if (scopeBaseStack.isEmpty()) {
            return 0;
        }
        return scopeBaseStack.peek();
    }
}
