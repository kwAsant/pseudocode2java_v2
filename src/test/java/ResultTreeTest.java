import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ResultTreeTest {
    private static final Path FIXTURES = Path.of("src/test/resources/result-trees");
    @ParameterizedTest
    @ValueSource(strings = {"simple", "control", "error"})
    void outputMatchesGoldenFixture(String fixture) throws Exception {
        String input = Files.readString(FIXTURES.resolve(fixture + ".src"));
        var program = Pipeline.compile(input);
        var bytes = new ByteArrayOutputStream();
        try (var out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            var interpreter = new Interpreter(out);
            if (fixture.equals("error")) assertThrows(Interpreter.RuntimeError.class, () -> interpreter.interpretWithResults(program.statements(), false));
            else interpreter.interpretWithResults(program.statements(), false);
        }
        String expected = Files.readString(FIXTURES.resolve(fixture + ".out")).replace("\r\n", "\n");
        String actual = bytes.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
        System.out.println("Input: " + input);
        System.out.println("Expected: " + expected);
        System.out.println("Actual: " + actual);
        assertEquals(expected, actual);
    }
    @ParameterizedTest
    @ValueSource(strings = {"simple", "control", "error"})
    void colorDoesNotChangeRenderedTree(String fixture) throws Exception {
        String input = Files.readString(FIXTURES.resolve(fixture + ".src"));
        var program = Pipeline.compile(input);
        var bytes = new ByteArrayOutputStream();
        try (var out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            var interpreter = new Interpreter(out);
            if (fixture.equals("error")) assertThrows(Interpreter.RuntimeError.class, () -> interpreter.interpretWithResults(program.statements(), true));
            else interpreter.interpretWithResults(program.statements(), true);
        }
        String actual = bytes.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
        System.out.println("Input: " + input);
        System.out.println("Expected: cyan and green ANSI output, plus red for errors; uncolored output matches " + fixture + ".out");
        System.out.println("Actual: " + actual.replace("\u001B", "<ESC>"));
        assertTrue(actual.contains("\u001B["));
        assertTrue(actual.contains("\u001B[36m"));
        assertTrue(actual.contains("\u001B[32m"));
        if (fixture.equals("error")) assertTrue(actual.contains("\u001B[31m"));
        String expected = Files.readString(FIXTURES.resolve(fixture + ".out")).replace("\r\n", "\n");
        String uncolored = actual.replaceAll("\\u001B\\[[0-9;]*m", "");
        System.out.println("Expected uncolored: " + expected);
        System.out.println("Actual uncolored: " + uncolored);
        assertEquals(expected, uncolored);
    }
    @Test void colorRequiresTerminalAndRespectsEnvironment() {
        assertTrue(reportColor("terminal=true, systemOutput=true, NO_COLOR=null, TERM=xterm", true, Interpreter.supportsColor(true, true, null, "xterm")));
        assertFalse(reportColor("terminal=false, systemOutput=true, NO_COLOR=null, TERM=xterm", false, Interpreter.supportsColor(false, true, null, "xterm")));
        assertFalse(reportColor("terminal=true, systemOutput=false, NO_COLOR=null, TERM=xterm", false, Interpreter.supportsColor(true, false, null, "xterm")));
        assertFalse(reportColor("terminal=true, systemOutput=true, NO_COLOR='', TERM=xterm", false, Interpreter.supportsColor(true, true, "", "xterm")));
        assertFalse(reportColor("terminal=true, systemOutput=true, NO_COLOR=null, TERM=dumb", false, Interpreter.supportsColor(true, true, null, "dumb")));
    }
    private static boolean reportColor(String input, boolean expected, boolean actual) {
        System.out.println("Input: " + input);
        System.out.println("Expected: " + expected);
        System.out.println("Actual: " + actual);
        return actual;
    }
    @Test void longLoopsKeepExecutionOrder() {
        String input = "x = 0\nwhile x < 2000\nx = x + 1\nendwhile\nprint(x)";
        var program = Pipeline.compile(input);
        var bytes = new ByteArrayOutputStream();
        try (var out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) { new Interpreter(out).interpretWithResults(program.statements(), false); }
        String actual = bytes.toString(StandardCharsets.UTF_8);
        System.out.println("Input: " + input);
        System.out.println("Expected: output starts with 2000; trace visits x = 1999 before x = 2000");
        System.out.println("Actual: starts with 2000 = " + actual.startsWith("2000")
                + "; trace positions: 1999 = " + actual.indexOf("variable x = 1999")
                + ", 2000 = " + actual.indexOf("variable x = 2000"));
        assertTrue(actual.startsWith("2000"));
        int beforeLast = actual.indexOf("variable x = 1999");
        assertTrue(beforeLast >= 0);
        assertTrue(actual.indexOf("variable x = 2000", beforeLast) > beforeLast);
    }
}
