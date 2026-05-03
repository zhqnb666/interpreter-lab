package cn.edu.nju.cs;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class InterpreterRegressionTest {
    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();
    private static final Path TESTCASE_DIR = PROJECT_ROOT.resolve("testcases");
    private static final Duration CASE_TIMEOUT = Duration.ofSeconds(30);

    @ParameterizedTest(name = "{0}")
    @MethodSource("testcaseProvider")
    void runOfficialTestcases(String caseName, Path mjFile, String expectedStdout, int expectedExitCode) {
        RunResult result = runProgram(mjFile);
        assertEquals(expectedExitCode, result.exitCode, "exit code mismatch: " + caseName);
        assertEquals(normalize(expectedStdout), normalize(result.stdout), "stdout mismatch: " + caseName);
    }

    static Stream<Arguments> testcaseProvider() throws IOException {
        List<Arguments> cases = new ArrayList<>();
        try (Stream<Path> files = Files.list(TESTCASE_DIR)) {
            files.filter(p -> p.toString().endsWith(".mj"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(mj -> {
                        String base = mj.getFileName().toString().replaceFirst("\\.mj$", "");
                        Path out = TESTCASE_DIR.resolve(base + ".output");
                        if (!Files.exists(out)) {
                            throw new RuntimeEvalException("Missing .output for testcase: " + base);
                        }
                        try {
                            OutputExpectation exp = parseExpectation(out);
                            cases.add(Arguments.of(base, mj, exp.stdout, exp.exitCode));
                        } catch (IOException e) {
                            throw new RuntimeEvalException("Failed to parse testcase output: " + out, e);
                        }
                    });
        }
        return cases.stream();
    }

    @Test
    void assertFailureExitsWith33() throws Exception {
        String program = """
                int main() {
                    assert(false);
                    return 0;
                }
                """;
        RunResult result = runProgram(writeTempProgram("assert33.mj", program));
        assertEquals(33, result.exitCode);
        assertEquals("Process exits with 33.", normalize(result.stdout));
    }

    @Test
    void ambiguousEntryMainExitsWith34() throws Exception {
        String program = """
                int main() {
                    return 0;
                }

                void main() {
                    return;
                }
                """;
        RunResult result = runProgram(writeTempProgram("amb_main.mj", program));
        assertEquals(34, result.exitCode);
        assertEquals("Process exits with 34.", normalize(result.stdout));
    }

    @Test
    void missingReturnInNonVoidMethodExitsWith34() throws Exception {
        String program = """
                int foo() {
                    int x = 1;
                }

                int main() {
                    return foo();
                }
                """;
        RunResult result = runProgram(writeTempProgram("missing_return.mj", program));
        assertEquals(34, result.exitCode);
        assertEquals("Process exits with 34.", normalize(result.stdout));
    }

    private static RunResult runProgram(Path mjFile) {
        return assertTimeoutPreemptively(CASE_TIMEOUT, () -> {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8)) {
                int code = Main.execute(mjFile, out);
                // Match the testcase .output convention, which records the POSIX
                // unsigned-8-bit exit code (e.g. `return -1;` -> 255). The previous
                // ProcessBuilder runner got this truncation from the OS for free.
                return new RunResult(buf.toString(StandardCharsets.UTF_8), code & 0xFF);
            }
        }, () -> "Process timed out for: " + mjFile);
    }

    private static OutputExpectation parseExpectation(Path outputFile) throws IOException {
        String raw = Files.readString(outputFile, StandardCharsets.UTF_8).replace("\r\n", "\n").replace("\r", "\n");
        List<String> lines = new ArrayList<>(List.of(raw.split("\n", -1)));
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        if (lines.size() < 2) {
            throw new RuntimeEvalException("Invalid testcase output format: " + outputFile);
        }

        String exitCodeLine = lines.remove(lines.size() - 1);
        int exitCode;
        try {
            exitCode = Integer.parseInt(exitCodeLine.trim());
        } catch (NumberFormatException e) {
            throw new RuntimeEvalException("Invalid exit code line in " + outputFile + ": " + exitCodeLine, e);
        }

        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        String stdout = String.join("\n", lines);
        return new OutputExpectation(stdout, exitCode);
    }

    private static String normalize(String text) {
        String s = text.replace("\r\n", "\n").replace("\r", "\n");
        while (s.endsWith("\n")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static Path writeTempProgram(String fileName, String code) throws IOException {
        Path dir = PROJECT_ROOT.resolve("target").resolve("test-programs");
        Files.createDirectories(dir);
        Path file = dir.resolve(fileName);
        Files.writeString(file, code, StandardCharsets.UTF_8);
        return file;
    }

    private record OutputExpectation(String stdout, int exitCode) {
    }

    private record RunResult(String stdout, int exitCode) {
    }
}
