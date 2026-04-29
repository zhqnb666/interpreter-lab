package cn.edu.nju.cs;

public class RuntimeEvalException extends RuntimeException {
    public RuntimeEvalException(String message) {
        super(message);
    }

    public RuntimeEvalException(String message, Throwable cause) {
        super(message, cause);
    }
}
