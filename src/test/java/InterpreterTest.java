import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class InterpreterTest {
    @Test void switchSelectsFirstCaseOrDefault() {
        String body = "switch day:\ncase \"Sat\":\nprint(\"Saturday\")\ncase \"Sun\":\nprint(\"Sunday\")\ndefault:\nprint(\"Weekday\")\nendswitch";
        assertEquals("Saturday\n", run("day=\"Sat\"\n" + body));
        assertEquals("Weekday\n", run("day=\"Mon\"\n" + body));
        assertEquals("", run("day=\"Mon\"\nswitch day:\ncase \"Sat\":\nprint(1)\nendswitch"));
        assertEquals("matched\n", run("switch -1:\ncase -1:\nprint(\"matched\")\nendswitch"));
        assertEquals("first\n", run("switch 1:\ncase 1:\nprint(\"first\")\ncase 1:\nprint(\"second\")\nendswitch"));
        assertEquals("default\n", run("switch 3:\ncase 1:\nprint(\"case\")\ndefault:\nprint(\"default\")\nbreak\nprint(\"after\")\nendswitch"));
    }
    @Test void breakStopsTheSelectedSwitchCase() {
        String source = "switch 1:\ncase 1:\nprint(\"first\")\nbreak\nprint(\"unreachable\")\ncase 2:\nprint(\"second\")\nendswitch\nprint(\"after\")";
        assertEquals("first\nafter\n", run(source));
        assertEquals("inner\nouter\n", run("switch 1:\ncase 1:\nswitch 2:\ncase 2:\nprint(\"inner\")\nbreak\nendswitch\nprint(\"outer\")\nendswitch"));
        assertTrue(assertThrows(Interpreter.RuntimeError.class, () -> run("break")).getMessage().contains("break outside switch"));
        var output = new ByteArrayOutputStream();
        try (var stream = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            Pipeline.compile(source).executeWithResults(stream);
        }
        assertFalse(output.toString(StandardCharsets.UTF_8).contains("[error:"));
    }
    @Test void functionAndProcedurePassArgumentsByValueAndReference() {
        String source = "function triple(number)\nreturn number*3\nendfunction\nprocedure update(x:byVal, y:byRef)\nx=99\ny=triple(y)\nendprocedure\na=2\nb=4\nupdate(a,b)\nprint(a)\nprint(b)";
        var program = Pipeline.compile(source);
        assertInstanceOf(AST.Function.class, program.statements().get(0));
        assertInstanceOf(AST.Procedure.class, program.statements().get(1));
        assertEquals("2\n12\n", run(source));
    }
    @Test void byReferenceRejectsNonVariablesAndReturnIsNotAnExecutionError() {
        String source = "procedure set(x:byRef)\nx=1\nendprocedure\nset(2)";
        assertTrue(assertThrows(Interpreter.RuntimeError.class, () -> run(source)).getMessage().contains("byRef requires a variable"));
        var output = new ByteArrayOutputStream();
        try (var out = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            Pipeline.compile("function f(x)\nreturn x\nendfunction\nprint(f(3))").executeWithResults(out);
        }
        assertTrue(output.toString(StandardCharsets.UTF_8).startsWith("3"));
        assertFalse(output.toString(StandardCharsets.UTF_8).contains("[error:"));
    }
    @Test void reportsRepeatedDistinctCasingWithoutChangingOutput() {
        String source = "Score = 2\nscore = Score + 3\nprint(SCORE)\nprint(score)";
        var output = new ByteArrayOutputStream();
        var warnings = new ByteArrayOutputStream();
        try (var out = new PrintStream(output, true, StandardCharsets.UTF_8);
             var err = new PrintStream(warnings, true, StandardCharsets.UTF_8)) {
            Pipeline.compile(source).execute(out, err);
        }
        String actual = output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
        String diagnostic = warnings.toString(StandardCharsets.UTF_8);
        System.out.println("Input: " + source + "\nExpected: output 5 twice and one casing warning\nActual: " + actual + diagnostic);
        assertEquals("5\n5\n", actual);
        assertEquals(1, diagnostic.lines().count());
        assertTrue(diagnostic.contains("Score"));
        assertTrue(diagnostic.contains("score"));
        assertTrue(diagnostic.contains("SCORE"));
        assertTrue(diagnostic.contains("line 3"));
        assertTrue(diagnostic.contains("column 7"));
    }
    @Test void doesNotWarnForConsistentOrSingleVariantSpelling() {
        for (String source : new String[] {"Score = 2\nprint(Score)", "Score = 2\nprint(score)\nprint(score)"}) {
            var warnings = new ByteArrayOutputStream();
            try (var out = new PrintStream(new ByteArrayOutputStream()); var err = new PrintStream(warnings)) {
                Pipeline.compile(source).execute(out, err);
            }
            System.out.println("Input: " + source + "\nExpected: no warnings\nActual: " + warnings);
            assertEquals("", warnings.toString());
        }
    }
    @Test void warningsAreFreshPerExecutionAndUndefinedNamesStillFail() {
        var program = Pipeline.compile("Score = 2\nscore = 3\nprint(SCORE)");
        for (int i = 0; i < 2; i++) {
            var warnings = new ByteArrayOutputStream();
            try (var out = new PrintStream(new ByteArrayOutputStream()); var err = new PrintStream(warnings)) {
                program.execute(out, err);
            }
            System.out.println("Input: Score / score / SCORE\nExpected: one warning per execution\nActual: " + warnings);
            assertEquals(1, warnings.toString().lines().count());
        }
        var warnings = new ByteArrayOutputStream();
        try (var out = new PrintStream(new ByteArrayOutputStream()); var err = new PrintStream(warnings)) {
            assertThrows(Interpreter.RuntimeError.class, () -> Pipeline.compile("print(missing)").execute(out, err));
        }
        assertEquals("", warnings.toString());
    }
    @Test void warnsPerBindingOnlyForExecutedAccessesEvenWithResultTracing() {
        String source = "Score = 1\nscore = 2\nSCORE = 3\nOther = 4\nother = 5\nOTHER = 6\nif false then\nunused = 1\nUNUSED = 2\nUnused = 3\nendif\nprint(Score)";
        var output = new ByteArrayOutputStream();
        var warnings = new ByteArrayOutputStream();
        try (var out = new PrintStream(output); var err = new PrintStream(warnings)) {
            Pipeline.compile(source).executeWithResults(out, err);
        }
        String actual = warnings.toString();
        System.out.println("Input: " + source + "\nExpected: two warnings, no warning for the unexecuted branch\nActual: " + actual);
        assertEquals(2, actual.lines().count());
        assertTrue(actual.contains("variable 'Score'"));
        assertTrue(actual.contains("variable 'Other'"));
        assertFalse(actual.contains("unused"));
        assertTrue(output.toString().startsWith("3"));
    }
    private String run(String source) {
        System.out.println("Input: " + source);
        var bytes = new ByteArrayOutputStream();
        try (var out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            Pipeline.compile(source).execute(out);
        } catch (RuntimeException error) {
            System.out.println("Actual: " + error);
            throw error;
        }
        String actual = bytes.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
        System.out.println("Actual: " + actual);
        return actual;
    }
    @Test void assignmentsUseCaseInsensitiveNamesAndPreserveSpelling() {
        System.out.println("Expected: 5");
        assertEquals("5\n", run("Score = 2\nscore = Score + 3\nprint(SCORE)"));
    }
    @Test void whileAndIfUseBooleanConditions() {
        System.out.println("Expected: 0, then 1");
        assertEquals("0\n1\n", run("x = 0\nwhile x < 2\nif x == 0 then\nprint(x)\nelse\nprint(x)\nendif\nx = x + 1\nendwhile"));
    }
    @Test void ocrSelectionParsesBranchesAndExecutesElseIf() {
        String source = "entry=\"b\"\nif entry==\"a\" then\nprint(\"A\")\nelseif entry==\"b\" then\nprint(\"B\")\nelse\nprint(\"Other\")\nendif";
        var program = Pipeline.compile(source);
        var selection = assertInstanceOf(AST.If.class, program.statements().get(1));
        assertEquals(2, selection.branches().size());
        assertInstanceOf(AST.Print.class, selection.branches().get(1).body().getFirst());
        assertInstanceOf(AST.Print.class, selection.otherwise().getFirst());
        assertEquals("B\n", run(source));
    }
    @Test void ambiguousOrUnsupportedSyntaxDoesNotExecute() {
        System.out.println("Expected: runtime errors mentioning ambiguous, Unsupported, and not implemented respectively");
        assertTrue(assertThrows(Interpreter.RuntimeError.class, () -> run("print(1 < 2 < 3)")).getMessage().contains("ambiguous"));
        assertThrows(Parser.ParseError.class, () -> run("for i = 1 to 2"));
        assertTrue(assertThrows(Interpreter.RuntimeError.class, () -> run("newFile(\"a\")")).getMessage().contains("not implemented"));
    }
    @Test void divisionRoundingAndDivTruncation() {
        assertEquals("0.333333\n0.000001\n-0.000001\n0\n0\n-3\n", run("print(1 / 3)\nprint(1 / 2000000)\nprint(-1 / 2000000)\nprint(1 DIV 3)\nprint(1 DIV 2000000)\nprint(-17 DIV 5)"));
        assertTrue(assertThrows(Interpreter.RuntimeError.class, () -> run("print(1 DIV 0)")).getMessage().contains("Division by zero"));
    }
    @Test void stringConcatenationCoercesWithoutChangingVariables() {
        assertEquals("Hello Noni\nHello 12\n13\ntrue!\na!\n", run("name=\"Noni\"\nprint(\"Hello \" + name)\nname=12\nprint(\"Hello \" + name)\nprint(name + 1)\nprint(true + \"!\")\nprint('a' + \"!\")"));
        assertTrue(assertThrows(Interpreter.RuntimeError.class, () -> run("print(true + false)")).getMessage().contains("numeric"));
        assertTrue(assertThrows(Interpreter.RuntimeError.class, () -> run("print('a' + 'b')")).getMessage().contains("numeric"));
    }
    @Test void evaluatesRepeatedUnaryAndBooleanNot() {
        assertEquals("2\ntrue\n", run("print(--2)\nprint(NOT false)"));
        assertThrows(Interpreter.RuntimeError.class, () -> run("print(NOT 2)"));
    }
    @Test void logicalOperatorsFollowBooleanTruthTables() {
        for (boolean a : new boolean[] {false, true}) for (boolean b : new boolean[] {false, true}) {
            for (String op : new String[] {"AND", "OR", "XOR"}) {
                boolean expected = switch (op) { case "AND" -> a && b; case "OR" -> a || b; default -> a ^ b; };
                assertEquals(expected + "\n", run("print(" + a + " " + op + " " + b + ")"));
            }
        }
        assertEquals("false\ntrue\n", run("print(NOT true)\nprint(NOT false)"));
    }
    @Test void logicalOperatorsAcceptComparisonResults() {
        String source = "print((1 == 1) AND (2 == 2))\nprint((1 == 2) OR (2 == 2))\nprint((1 == 1) XOR (2 == 2))\nprint(NOT (1 == 2))";
        assertEquals("true\ntrue\nfalse\ntrue\n", run(source));
        assertEquals("Correct\n", run("x=12 MOD 5\ny=17 DIV 5\nif x == 2 AND y == 3 then\nprint(\"Correct\")\nendif"));
    }
    @Test void logicalOperatorsRejectNonBooleanOperands() {
        for (String source : new String[] {"print(true AND 1)", "print(\"x\" OR false)", "print(true XOR 0)", "print(NOT 2)"}) {
            var error = assertThrows(Interpreter.RuntimeError.class, () -> run(source));
            assertTrue(error.getMessage().contains("Expected Boolean operand"), error.getMessage());
        }
    }
    @Test void evaluatesModAndReportsZeroDivisor() {
        assertEquals("2\n", run("x = 12 MOD 5\nprint(x)"));
        assertEquals("2\n", run("print(12 mod 5)"));
        var error = assertThrows(Interpreter.RuntimeError.class, () -> run("print(12 MOD 0)"));
        assertTrue(error.getMessage().contains("Division by zero"));
        assertTrue(error.getMessage().contains("line 1, column 10") || error.getMessage().contains("Line 1, column 10"));
        assertEquals("-2\n2\n-2\n2.5\n", run("print(-5 MOD 3)\nprint(5 MOD -3)\nprint(-5 MOD -3)\nprint(5.5 MOD 3)"));
    }
    @Test void evaluatesDivQuotientAndReportsZeroDivisor() {
        assertEquals("3\n", run("y = 17 DIV 5\nprint(y)"));
        assertEquals("3\n", run("print(17 div 5)"));
        var error = assertThrows(Interpreter.RuntimeError.class, () -> run("print(17 DIV 0)"));
        assertTrue(error.getMessage().contains("Division by zero"));
        assertTrue(error.getMessage().contains("Line 1, column 10"));
        assertEquals("-3\n-3\n3\n", run("print(-17 DIV 5)\nprint(17 DIV -5)\nprint(-17 DIV -5)"));
    }
    @Test void forLoopUsesInclusiveBoundsAndEvaluatesEndOnce() {
        String source = "limit=3\nfor i=1 to limit\nprint(i)\nlimit=1\nnext i";
        var program = Pipeline.compile(source);
        assertInstanceOf(AST.For.class, program.statements().get(1));
        assertEquals("1\n2\n3\n", run(source));
        assertEquals("4\n2\n0\n", run("for i=4 to 0 step -2\nprint(i)\nnext i"));
        assertEquals("1\n3\n5\n", run("stride=2\nfor i=1 to 5 step stride\nprint(i)\nstride=1\nnext i"));
        assertEquals("Hello\n".repeat(8), run("for i=0 to 7\nprint(\"Hello\")\nnext i"));
    }
    @Test void forLoopStopsBeforeStepPassesEndpoint() {
        assertEquals("1\n3\n5\n", run("for i=1 to 6 step 2\nprint(i)\nnext i"));
        assertEquals("6\n4\n2\n", run("for i=6 to 1 step -2\nprint(i)\nnext i"));
    }
    @Test void errorsIncludeSourceLocations() {
        System.out.println("Expected: runtime errors mentioning Line 2, missing, and Boolean respectively");
        assertTrue(assertThrows(Interpreter.RuntimeError.class, () -> run("\nprint(1 / 0)")).getMessage().contains("Line 2"));
        assertTrue(assertThrows(Interpreter.RuntimeError.class, () -> run("print(missing)")).getMessage().contains("missing"));
        assertTrue(assertThrows(Interpreter.RuntimeError.class, () -> run("if 0 then print(1) endif")).getMessage().contains("Boolean"));
    }
}
