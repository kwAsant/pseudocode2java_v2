import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParserTest {
    private List<AST.Statement> parse(String source) {
        System.out.println("Input: " + source);
        try {
            var result = new Parser(new Lexer().scan(source)).parse();
            System.out.println("Actual: " + result);
            return result;
        } catch (Parser.ParseError error) {
            System.out.println("Actual: " + error);
            throw error;
        }
    }

    @Test void parsesNestedBlocksAndRetainsSpans() {
        System.out.println("Expected: nested if/while, two branches, one else, lines 1..10");
        var program = parse("""
                if answer == "Yes" then
                  while count < 3
                    print(count)
                    count = count + 1
                  endwhile
                elseif answer == "No" then
                  print("Wrong")
                else
                  print("Error")
                endif
                """);
        var outer = assertInstanceOf(AST.If.class, program.getFirst());
        assertEquals(2, outer.branches().size());
        var loop = assertInstanceOf(AST.While.class, outer.branches().getFirst().body().getFirst());
        assertEquals(2, loop.body().size());
        assertEquals(1, outer.otherwise().size());
        assertEquals(1, outer.start().span().start().line());
        assertEquals(10, outer.end().span().start().line());
    }
    @Test void inlineIfAndAmbiguousExpressionsPreserveTokens() {
        System.out.println("Expected: inline if with grouped true and print; ambiguous tokens preserved");
        var parsed = assertInstanceOf(AST.If.class, parse("if (true) then print(1) endif").getFirst());
        var group = assertInstanceOf(AST.Group.class, parsed.branches().getFirst().condition());
        assertEquals(4, group.open().span().start().column());
        assertEquals(9, group.close().span().start().column());
        assertEquals("true", assertInstanceOf(AST.Atom.class, group.expression()).token().text());
        assertInstanceOf(AST.Print.class, parsed.branches().getFirst().body().getFirst());
        var assignment = assertInstanceOf(AST.Assign.class, parse("x = 1 + 2 * 3").getFirst());
        assertEquals(List.of("1", "+", "2", "*", "3"), assertInstanceOf(AST.Unresolved.class, assignment.expression()).tokens().stream().map(Lexer.Token::text).toList());
    }
    @Test void reportsMissingOrMismatchedBlockEnds() {
        System.out.println("Expected: parse errors mentioning endif, line 3, and then respectively");
        assertTrue(assertThrows(Parser.ParseError.class, () -> parse("if true then\nprint(1)\n")).getMessage().contains("endif"));
        assertTrue(assertThrows(Parser.ParseError.class, () -> parse("while true\nprint(1)\nendif")).getMessage().contains("line 3"));
        assertTrue(assertThrows(Parser.ParseError.class, () -> parse("if true\nprint(1)\nendif")).getMessage().contains("then"));
    }
    @Test void refusesTruncatedLexerOutput() {
        String input = "if true then\n@\nendif";
        var result = new Lexer().scan(input);
        System.out.println("Input: " + input);
        System.out.println("Expected: lexical errors and a parse error");
        System.out.println("Actual: " + result.diagnostics());
        assertTrue(result.hasErrors());
        var error = assertThrows(Parser.ParseError.class, () -> new Parser(result));
        System.out.println("Actual: " + error);
    }
    @Test void recognizesBothNewFileFormsAndPreservesSpanAndArguments() {
        System.out.println("Expected: newFile identifier, files receiver, preserved arguments and span; nested and mixed-case receiver calls");
        assertEquals(Lexer.Kind.IDENTIFIER, new Lexer().scan("newFile(\"sample.txt\")").tokens().getFirst().kind());
        assertEquals(Lexer.Kind.IDENTIFIER, new Lexer().scan("files.newFile(\"sample.txt\")").tokens().get(2).kind());
        var bare = assertInstanceOf(AST.Call.class, parse("newFile(\"sample.txt\")").getFirst());
        var explicit = assertInstanceOf(AST.Call.class, parse("files.newFile(\"sample.txt\")").getFirst());
        assertEquals("files", bare.receiver());
        assertEquals(bare.receiver(), explicit.receiver());
        assertEquals("newFile", explicit.method().text());
        assertEquals(bare.argumentTokens().stream().map(Lexer.Token::text).toList(), explicit.argumentTokens().stream().map(Lexer.Token::text).toList());
        assertEquals("sample.txt", explicit.argumentTokens().getFirst().value());
        assertEquals(1, explicit.span().start().column());
        assertEquals(28, explicit.span().end().column());
        var nested = assertInstanceOf(AST.Call.class, parse("newFile(str(1))").getFirst());
        assertEquals(List.of("str", "(", "1", ")"), nested.argumentTokens().stream().map(Lexer.Token::text).toList());
        assertInstanceOf(AST.Call.class, parse("FiLeS.newFile(\"x\")").getFirst());
    }
    @Test void doesNotMisidentifyOtherOrIncompleteCalls() {
        System.out.println("Expected: unrecognized or incomplete calls remain lines; lexical errors reject parsing");
        for (String source : List.of("newFile", "other.newFile(\"x\")", "files.newfile(\"x\")", "newFile(\"x\"", "newFile(\"x\") extra"))
            assertInstanceOf(AST.Line.class, parse(source).getFirst(), source);
        assertThrows(Parser.ParseError.class, () -> parse("newFile(\"x\") @"));
    }
}
