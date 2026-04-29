package cn.edu.nju.cs;

public final class ExitSignal extends RuntimeException {
    private final int exitCode;

    public ExitSignal(int exitCode) {
        this.exitCode = exitCode;
    }

    public int exitCode() {
        return exitCode;
    }
}
