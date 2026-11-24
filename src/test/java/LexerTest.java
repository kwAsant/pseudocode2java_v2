import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.api.Test;
import java.util.Locale;

class LexerTest {
    @Test void decimalWithAlphabeticSuffixIsNotAnIdentifier() {
        String input = "x = 1.2abc\nprint(1)";
        var result = new Lexer().scan(input);
        report(input, "tokens [x, =]; invalid-number at offsets 4..10", result);
        assertTrue(result.hasErrors());
        assertEquals(List.of("x", "="), result.tokens().stream().map(Lexer.Token::text).toList());
        var diagnostic = result.diagnostics().getFirst();
        assertEquals(Lexer.Severity.ERROR, diagnostic.severity());
        assertEquals("invalid-number", diagnostic.category());
        assertEquals(new Lexer.Position(4, 1, 5), diagnostic.span().start());
        assertEquals(new Lexer.Position(10, 1, 11), diagnostic.span().end());
    }

    @Test void decimalLikeTextInsideStringRemainsText() {
        String input = "\"1.2abc\"";
        var result = new Lexer().scan(input);
        report(input, "STRING with value 1.2abc; no errors", result);
        assertFalse(result.hasErrors());
        assertEquals(Lexer.Kind.STRING, result.tokens().getFirst().kind());
        assertEquals("1.2abc", result.tokens().getFirst().value());
    }

    @Test void unsupportedComparisonRunIsAnErrorAndStopsScanning() {
        String input = "a<>b\nprint(1)";
        var result = new Lexer().scan(input);
        report(input, "token [a]; unknown-symbol at offsets 1..3", result);
        assertTrue(result.hasErrors());
        assertEquals(List.of("a"), result.tokens().stream().map(Lexer.Token::text).toList());
        var diagnostic = result.diagnostics().getFirst();
        assertEquals(Lexer.Severity.ERROR, diagnostic.severity());
        assertEquals("unknown-symbol", diagnostic.category());
        assertEquals(new Lexer.Position(1, 1, 2), diagnostic.span().start());
        assertEquals(new Lexer.Position(3, 1, 4), diagnostic.span().end());
    }

    record Case(String description, String input, Object expected, Function<Lexer.Result, Object> extract) {
        @Override public String toString() { return description; }
    }

    static Stream<Case> lexerCases() {
        return Stream.of(
            new Case("print call token kinds", "print(1)", List.of(Lexer.Kind.BUILTIN_PRINT, Lexer.Kind.LPAREN, Lexer.Kind.NUMBER, Lexer.Kind.RPAREN), r -> r.tokens().stream().map(Lexer.Token::kind).toList()),
            new Case("identifier spellings", "count Count", List.of("count", "Count"), r -> r.tokens().stream().map(Lexer.Token::text).toList()),
            new Case("case-insensitive identifier keys", "count Count", List.of("count", "count"), r -> r.tokens().stream().map(Lexer.Token::key).toList()),
            new Case("digit and underscore identifiers", "123abc student_name", List.of("123abc", "student_name"), r -> r.tokens().stream().map(Lexer.Token::text).toList()),
            new Case("non-ASCII identifier error", "café", List.of("unknown-symbol"), LexerTest::errors),
            new Case("backslash error", "name\\thing", List.of("unknown-symbol"), LexerTest::errors),
            new Case("keyword and annotation casing", "for FOR For fOR print PRINT PrInT byRef byref", List.of(Lexer.Kind.KEYWORD_FOR, Lexer.Kind.KEYWORD_FOR, Lexer.Kind.IDENTIFIER, Lexer.Kind.IDENTIFIER, Lexer.Kind.BUILTIN_PRINT, Lexer.Kind.BUILTIN_PRINT, Lexer.Kind.IDENTIFIER, Lexer.Kind.ANNOTATION, Lexer.Kind.IDENTIFIER), r -> r.tokens().stream().map(Lexer.Token::kind).toList()),
            new Case("member casing", "s.length s.LENGTH s.writeLine s.writeline", List.of(Lexer.Kind.MEMBER, Lexer.Kind.IDENTIFIER, Lexer.Kind.MEMBER, Lexer.Kind.IDENTIFIER), r -> List.of(r.tokens().get(2).kind(), r.tokens().get(5).kind(), r.tokens().get(8).kind(), r.tokens().get(11).kind())),
            new Case("alphabetic operator boundaries", "12MOD5 xMODy MOD5 DiV", List.of("12", "MOD", "5", "xMODy", "MOD", "5", "DiV"), r -> r.tokens().stream().map(Lexer.Token::text).toList()),
            new Case("mixed-case operator identifier", "DiV", Lexer.Kind.IDENTIFIER, r -> r.tokens().getFirst().kind()),
            new Case("mod DIV warning", "x = 12 mod 5\ny = 17 DIV 5", List.of("mixed-operator-case"), LexerTest::warnings),
            new Case("leading-zero warning", "007", List.of("leading-zero"), LexerTest::warnings),
            new Case("leading-zero value", "007", "7", r -> r.tokens().getFirst().value()),
            new Case("decimal normalisation", ".5 3.", List.of("0.5", "3.0"), r -> r.tokens().stream().map(Lexer.Token::value).toList()),
            new Case("missing-zero warnings", ".5 3.", List.of("missing-zero", "missing-zero"), LexerTest::warnings),
            new Case("minus operator", "-1", List.of("-", "1"), r -> r.tokens().stream().map(Lexer.Token::text).toList()),
            new Case("numeric separator spelling", "1_000", "1_000", r -> r.tokens().getFirst().text()),
            new Case("malformed number", "12.3.4", List.of("invalid-number"), LexerTest::errors),
            new Case("malformed number span", "12.3.4", 6, r -> r.diagnostics().getFirst().span().end().offset()),
            new Case("exponent-name overlap", "1e3", Lexer.Kind.IDENTIFIER, r -> r.tokens().getFirst().kind()),
            new Case("Unicode string", "\"café\"", "café", r -> r.tokens().getFirst().value()),
            new Case("empty string", "\"\"", false, Lexer.Result::hasErrors),
            new Case("empty character", "''", List.of("invalid-character"), LexerTest::errors),
            new Case("escaped quotation", "\"say \\\"hi\\\"\"", "say \"hi\"", r -> r.tokens().getFirst().value()),
            new Case("multiline string", "\"first\nsecond\"", "first\nsecond", r -> r.tokens().getFirst().value()),
            new Case("unterminated string", "\"unfinished", List.of("unterminated-string"), LexerTest::errors),
            new Case("line comment", "x=1 // note\ny=2", "// note", r -> r.trivia().getFirst().text()),
            new Case("comment at EOF", "// note", "// note", r -> r.trivia().getFirst().text()),
            new Case("comment marker inside string", "\"A//B\"", true, r -> r.trivia().isEmpty()),
            new Case("byte order mark", "\ufeffx=1", List.of("x", "=", "1"), r -> r.tokens().stream().map(Lexer.Token::text).toList()),
            new Case("typographic quotes", "“hello”", List.of("unknown-symbol"), LexerTest::errors),
            new Case("error line and column", "x=1\n@", List.of(2, 1), r -> List.of(r.diagnostics().getFirst().span().start().line(), r.diagnostics().getFirst().span().start().column())),
            new Case("CRLF string has no errors", "\"first\r\nsecond\"", false, Lexer.Result::hasErrors),
            new Case("CRLF string value", "\"first\r\nsecond\"", "first\r\nsecond", r -> r.tokens().getFirst().value()),
            new Case("CRLF string end line", "\"first\r\nsecond\"", 2, r -> r.tokens().getFirst().span().end().line()),
            new Case("operator spellings", "== != < <= > >= + - * / ^ MOD DIV AND OR NOT", List.of("==", "!=", "<", "<=", ">", ">=", "+", "-", "*", "/", "^", "MOD", "DIV", "AND", "OR", "NOT"), r -> r.tokens().stream().map(Lexer.Token::text).toList()),
            new Case("punctuation spellings", "( ) [ ] , : .", List.of("(", ")", "[", "]", ",", ":", "."), r -> r.tokens().stream().map(Lexer.Token::text).toList()),
            new Case("unsupported operator", "===", List.of("unknown-symbol"), LexerTest::errors),
            new Case("line ending count", "x=1\n\ty=2\r\n", 2L, r -> r.tokens().stream().filter(t -> t.kind() == Lexer.Kind.NEWLINE).count()),
            new Case("if statement", "if x==1 then\n  print(x)\nendif", false, Lexer.Result::hasErrors),
            new Case("untyped assignment", "x=3", false, Lexer.Result::hasErrors),
            new Case("type keyword", "int x=3", Lexer.Kind.KEYWORD_INT, r -> r.tokens().getFirst().kind()),
            new Case("mixed-case type name", "Int x=3", Lexer.Kind.IDENTIFIER, r -> r.tokens().getFirst().kind())
        );
    }

    private static List<String> errors(Lexer.Result result) {
        return result.diagnostics().stream().filter(d -> d.severity() == Lexer.Severity.ERROR).map(Lexer.Diagnostic::category).toList();
    }
    private static List<String> warnings(Lexer.Result result) {
        return result.diagnostics().stream().filter(d -> d.severity() == Lexer.Severity.WARNING).map(Lexer.Diagnostic::category).toList();
    }

    static Stream<Arguments> distinctKinds() {
        var keywords = "for to step next while endwhile do until if then elseif else endif switch case default endswitch array global const function endfunction procedure endprocedure return int float str bool real string integer boolean true false".split(" ");
        var builtins = "print input random open newfile asc chr".split(" ");
        var operators = "mod div and or not".split(" ");
        return Stream.of(wordCases(keywords, "KEYWORD_"), wordCases(builtins, "BUILTIN_"), wordCases(operators, "OP_"),
            Stream.of(Arguments.of("=", Lexer.Kind.OP_ASSIGN), Arguments.of("==", Lexer.Kind.OP_EQ), Arguments.of("!=", Lexer.Kind.OP_NE),
                Arguments.of("<", Lexer.Kind.OP_LT), Arguments.of("<=", Lexer.Kind.OP_LE), Arguments.of(">", Lexer.Kind.OP_GT), Arguments.of(">=", Lexer.Kind.OP_GE),
                Arguments.of("+", Lexer.Kind.OP_PLUS), Arguments.of("-", Lexer.Kind.OP_MINUS), Arguments.of("*", Lexer.Kind.OP_MULTIPLY),
                Arguments.of("/", Lexer.Kind.OP_DIVIDE), Arguments.of("^", Lexer.Kind.OP_POWER))).flatMap(s -> s);
    }

    private static Stream<Arguments> wordCases(String[] words, String prefix) {
        return Stream.of(words).flatMap(word -> {
            var expected = Lexer.Kind.valueOf(prefix + word.toUpperCase(Locale.ROOT));
            String mixed = Character.toUpperCase(word.charAt(0)) + word.substring(1);
            return Stream.of(Arguments.of(word, expected), Arguments.of(word.toUpperCase(Locale.ROOT), expected), Arguments.of(mixed, Lexer.Kind.IDENTIFIER));
        });
    }

    @ParameterizedTest(name = "{index}: {0} as {1}")
    @MethodSource("distinctKinds")
    void distinctKindSpellingAndSpan(String spelling, Lexer.Kind expected) {
        String input = " " + spelling;
        var result = new Lexer().scan(input);
        report(input, expected + " " + spelling + " at [1," + (1 + spelling.length()) + ")", result);
        assertFalse(result.hasErrors(), () -> result.diagnostics().toString());
        assertEquals(1, result.tokens().size());
        var token = result.tokens().getFirst();
        System.out.println("Input: " + input);
        System.out.println("Expected: " + expected + " " + spelling + " at [1," + (1 + spelling.length()) + ")");
        System.out.println("Actual: " + token);
        assertEquals(expected, token.kind());
        assertEquals(spelling, token.text());
        assertEquals(new Lexer.Position(1, 1, 2), token.span().start());
        assertEquals(new Lexer.Position(1 + spelling.length(), 1, 2 + spelling.length()), token.span().end());
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("lexerCases")
    void lexerBehaviour(Case example) {
        var actual = example.extract().apply(new Lexer().scan(example.input()));
        report(example.input(), example.expected(), actual);
        assertEquals(example.expected(), actual);
    }

    static Stream<Path> exampleFiles() {
        return Stream.of("warnings.ocr", "error.ocr").map(name -> Path.of("src/test/resources/examples", name));
    }

    @ParameterizedTest(name = "{index}: fixture {0}")
    @MethodSource("exampleFiles")
    void fileDrivenOutput(Path file) throws Exception {
        String input = Files.readString(file);
        var result = new Lexer().scan(input);
        boolean warningFile = file.getFileName().toString().equals("warnings.ocr");
        Object expected = warningFile ? List.of(List.of("leading-zero"), true) : List.of(true, true);
        Object actual = warningFile
            ? List.of(warnings(result), result.tokens().stream().anyMatch(t -> t.text().equals("y")))
            : List.of(result.hasErrors(), result.tokens().stream().noneMatch(t -> t.text().equals("y")));
        report(input, expected, actual);
        System.out.println("Tokens: " + result.tokens());
        System.out.println("Diagnostics: " + result.diagnostics());
        assertEquals(expected, actual);
    }

    private static void report(String input, Object expected, Object actual) {
        System.out.println("Input: " + input);
        System.out.println("Expected: " + expected);
        System.out.println("Actual: " + actual);
    }
}
