package cn.edu.nju.cs;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

public class Main {
    public static int execute(Path mjFile, PrintStream out) {
        try {
            MiniJavaParser.CompilationUnitContext tree = parse(mjFile);
            InterpreterVisitor visitor = new InterpreterVisitor(out);
            int code = visitor.execute(tree);
            out.println("Process exits with " + code + ".");
            return code;
        } catch (ExitSignal e) {
            System.err.println("Interpreter failed: " + e.getMessage());
            out.println("Process exits with " + e.exitCode() + ".");
            return e.exitCode();
        } catch (Exception e) {
            System.err.println("Interpreter failed: " + e.getMessage());
            out.println("Process exits with 34.");
            return 34;
        }
    }

    private static MiniJavaParser.CompilationUnitContext parse(Path mjFile) throws IOException {
        MiniJavaLexer lexer = new MiniJavaLexer(CharStreams.fromPath(mjFile));
        MiniJavaParser parser = new MiniJavaParser(new CommonTokenStream(lexer));

        ThrowingErrorListener listener = new ThrowingErrorListener();
        lexer.removeErrorListeners();
        parser.removeErrorListeners();
        lexer.addErrorListener(listener);
        parser.addErrorListener(listener);
        return parser.compilationUnit();
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("Process exits with 34.");
            System.exit(34);
        }
        int code = execute(Path.of(args[0]), System.out);
        System.exit(code);
    }

    private static final class ThrowingErrorListener extends BaseErrorListener {
        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine,
                                String msg, RecognitionException e) {
            throw new RuntimeEvalException("Syntax error");
        }
    }
}
