import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParserTest {
    @Test void reportsMissingAssignmentValueAndRecovers() {
        assertMalformedAndRecovers("x =\ny = 2", "Expected expression", new Lexer.Position(3, 1, 4));
    }
    @Test void reportsMissingBinaryOperandAndRecovers() {
        assertMalformedAndRecovers("x = 1 +\ny = 2", "Expected expression", new Lexer.Position(7, 1, 8));
    }
    @Test void reportsUnclosedPrintAndRecovers() {
        assertMalformedAndRecovers("print(1\ny = 2", "Expected ')'", new Lexer.Position(7, 1, 8));
    }
    @Test void reportsMissingNestedOperandAndRecovers() {
        assertMalformedAndRecovers("x = (1 + )\ny = 2", "Expected expression", new Lexer.Position(9, 1, 10));
    }
    @Test void reportsMissingOperandInNestedCallArgumentAndRecovers() {
        assertMalformedAndRecovers("x = f(1 +,2)\ny = 2", "Expected expression", new Lexer.Position(9, 1, 10));
    }
    private void assertMalformedAndRecovers(String source, String message, Lexer.Position position) {
        var result = new Parser(new Lexer().scan(source)).parse();
        assertEquals(1, result.diagnostics().size());
        assertEquals(message, result.diagnostics().getFirst().message());
        assertEquals(position, result.diagnostics().getFirst().position());
        var recovered = assertInstanceOf(AST.Assign.class, result.statements().getLast());
        assertEquals("y", recovered.name().text());
        assertEquals(2, recovered.name().span().start().line());
        var error = assertThrows(Parser.ParseError.class, () -> Pipeline.compile(source));
        assertTrue(error.getMessage().contains(message + " at line " + position.line() + ", column " + position.column()));
    }
    @Test void reportsMalformedExpressionsAtEndOfInput() {
        for (String source : List.of("x =", "x = 1 +", "print(1", "x = 1 +  ")) {
            var result = new Parser(new Lexer().scan(source)).parse();
            assertEquals(1, result.diagnostics().size(), source);
            var diagnostic = result.diagnostics().getFirst();
            assertEquals(source.startsWith("print") ? "Expected ')'" : "Expected expression", diagnostic.message(), source);
            assertEquals(new Lexer.Position(source.length(), 1, source.length() + 1), diagnostic.position(), source);
            assertThrows(Parser.ParseError.class, () -> Pipeline.compile(source), source);
        }
        var nested = new Parser(new Lexer().scan("x = (1 + )")).parse();
        assertEquals(1, nested.diagnostics().size());
        assertEquals(new Lexer.Position(9, 1, 10), nested.diagnostics().getFirst().position());
        assertThrows(Parser.ParseError.class, () -> Pipeline.compile("x = (1 + )"));
    }
    @Test void validAssignmentAndPrintHaveNoDiagnostics() {
        for (String source : List.of("x = 1 + 2", "print(1)")) {
            assertTrue(new Parser(new Lexer().scan(source)).parse().diagnostics().isEmpty(), source);
            assertDoesNotThrow(() -> Pipeline.compile(source), source);
        }
    }
    @Test void parsesInitialisedArraysAndIndexedAssignments() {
        var array = assertInstanceOf(AST.ArrayDeclaration.class, parse("array colours = [\"Blue\",\"Pink\"]").getFirst());
        assertEquals("colours", array.name().text());
        assertEquals(2, array.initializer().elements().size());
        assertEquals("Blue", assertInstanceOf(AST.Atom.class, array.initializer().elements().getFirst()).token().value());
        assertEquals(17, array.initializer().open().span().start().column());
        var assignment = assertInstanceOf(AST.IndexAssignment.class, parse("board[1,0] = \"Pawn\"").getFirst());
        assertEquals(2, assignment.target().indices().size());
        assertEquals("Pawn", assertInstanceOf(AST.Atom.class, assignment.value()).token().value());
        assertEquals(12, assignment.assignment().span().start().column());
    }
    @Test void parsesFileOperationStatements() {
        for (String source : List.of("myFile.close()", "myFile.writeLine(\"Hello\")")) {
            var statement = assertInstanceOf(AST.ExpressionStatement.class, parse(source).getFirst());
            var member = assertInstanceOf(AST.MemberAccess.class, statement.call().callee());
            assertEquals("myFile", assertInstanceOf(AST.Atom.class, member.receiver()).token().text());
            assertEquals(source.contains("writeLine") ? "writeLine" : "close", member.member().text());
            assertEquals(source.contains("writeLine") ? 1 : 0, statement.call().arguments().size());
        }
        for (String source : List.of("f=open(\"a\")", "f=openRead(\"a\")", "f=openWrite(\"a\")", "x=f.readLine()", "x=f.endOfFile()")) {
            var assign = assertInstanceOf(AST.Assign.class, parse(source).getFirst());
            assertInstanceOf(AST.CallExpression.class, assign.expression());
        }
        var create = assertInstanceOf(AST.Call.class, parse("newFile(str(1))").getFirst());
        assertInstanceOf(AST.CallExpression.class, create.arguments().getFirst());
    }
    @Test void reportsMalformedArrayInitialisersAndIndexedAssignments() {
        var initialiser = new Parser(new Lexer().scan("array a = [1,]")).parse();
        assertEquals("Expected expression", initialiser.diagnostics().getFirst().message());
        assertEquals(new Lexer.Position(13, 1, 14), initialiser.diagnostics().getFirst().position());
        var index = new Parser(new Lexer().scan("a[1,] = 2")).parse();
        assertEquals("Expected expression", index.diagnostics().getFirst().message());
        assertEquals(new Lexer.Position(4, 1, 5), index.diagnostics().getFirst().position());
        var call = new Parser(new Lexer().scan("f.writeLine(,1)")).parse();
        assertEquals("Expected expression", call.diagnostics().getFirst().message());
        assertEquals(new Lexer.Position(12, 1, 13), call.diagnostics().getFirst().position());
        var create = new Parser(new Lexer().scan("newFile(,1)")).parse();
        assertEquals("Expected expression", create.diagnostics().getFirst().message());
        assertEquals(new Lexer.Position(8, 1, 9), create.diagnostics().getFirst().position());
        var missingValue = new Parser(new Lexer().scan("board[1,0] =")).parse();
        assertEquals("Expected expression", missingValue.diagnostics().getFirst().message());
        assertEquals(new Lexer.Position(12, 1, 13), missingValue.diagnostics().getFirst().position());
    }
    @Test void parsesArrayDeclarationsAndNestedIndexArguments() {
        var declaration = parse("array board[8,8]").getFirst();
        assertEquals("ArrayDeclaration", declaration.getClass().getSimpleName());
        var assign = assertInstanceOf(AST.Assign.class, parse("x=f(board[1,2],3)").getFirst());
        var call = assertInstanceOf(AST.FunctionCall.class, assign.expression());
        assertEquals(2, call.arguments().size());
        assertEquals("Index", call.arguments().getFirst().getClass().getSimpleName());
    }
    @Test void parsesIndexBuiltinMemberAndDoUntil() {
        var array = assertInstanceOf(AST.ArrayDeclaration.class, parse("array board[8,8]").getFirst());
        assertEquals(2, array.dimensions().size());
        var index = assertInstanceOf(AST.Index.class, assertInstanceOf(AST.Assign.class, parse("x=board[1,2]").getFirst()).expression());
        assertEquals(2, index.indices().size());
        var indexedSum = assertInstanceOf(AST.Binary.class, assertInstanceOf(AST.Assign.class, parse("x=board[1+2,3]+1").getFirst()).expression());
        assertEquals(2, assertInstanceOf(AST.Index.class, indexedSum.left()).indices().size());
        var call = assertInstanceOf(AST.CallExpression.class, assertInstanceOf(AST.Assign.class, parse("x=random(1,6)").getFirst()).expression());
        assertEquals(2, call.arguments().size());
        var memberCall = assertInstanceOf(AST.CallExpression.class, assertInstanceOf(AST.Assign.class, parse("x=s.substring(3,5)").getFirst()).expression());
        assertInstanceOf(AST.MemberAccess.class, memberCall.callee());
        assertEquals(2, memberCall.arguments().size());
        var loop = assertInstanceOf(AST.DoUntil.class, parse("do\nx=1\nuntil x==1").getFirst());
        assertEquals(1, loop.body().size());
        assertInstanceOf(AST.Binary.class, loop.condition());
    }
    @Test void collectsMissingArgumentsAndIndicesWithLocations() {
        var parsed = new Parser(new Lexer().scan("x=random(,6)\ny=a[1,]")).parse();
        assertEquals(2, parsed.diagnostics().size());
        assertEquals("Expected expression", parsed.diagnostics().get(0).message());
        assertEquals(new Lexer.Position(9, 1, 10), parsed.diagnostics().get(0).position());
        assertEquals("Expected expression", parsed.diagnostics().get(1).message());
        assertEquals(new Lexer.Position(19, 2, 7), parsed.diagnostics().get(1).position());
        var empty = new Parser(new Lexer().scan("x=a[]\nf(1,)")).parse();
        assertEquals(2, empty.diagnostics().size());
        assertEquals("Expected expression", empty.diagnostics().getFirst().message());
        var nested = new Parser(new Lexer().scan("x=f(,6)+2")).parse();
        assertEquals(1, nested.diagnostics().size());
    }
    @Test void recoversInsideDoAndReportsEndOfInputLocation() {
        var parsed = new Parser(new Lexer().scan("do\nx=random(,6)\nuntil x == 1\ny=2")).parse();
        assertEquals(1, parsed.diagnostics().size());
        assertEquals(2, parsed.size());
        var loop = assertInstanceOf(AST.DoUntil.class, parsed.getFirst());
        assertEquals(1, loop.body().size());
        assertInstanceOf(AST.Assign.class, parsed.getLast());
        var eof = new Parser(new Lexer().scan("do\nx=1\n")).parse();
        assertEquals(1, eof.diagnostics().size());
        assertEquals(new Lexer.Position(7, 3, 1), eof.diagnostics().getFirst().position());
    }
    @Test void recoversFromIndependentSyntaxErrorsAndKeepsOuterTerminator() {
        var parsed = new Parser(new Lexer().scan("array a[1\narray b[2")).parse();
        assertEquals(2, parsed.diagnostics().size());
        assertEquals(new Lexer.Position(9, 1, 10), parsed.diagnostics().get(0).position());
        assertEquals(new Lexer.Position(19, 2, 10), parsed.diagnostics().get(1).position());
        var nested = new Parser(new Lexer().scan("if true then\nwhile true\nx=1\nendif\ny=2")).parse();
        assertEquals(1, nested.diagnostics().size());
        assertEquals(2, nested.size());
        assertInstanceOf(AST.If.class, nested.getFirst());
        assertInstanceOf(AST.Assign.class, nested.getLast());
    }
    @Test void reportsUnclosedCallAtActualEndOfInput() {
        var parsed = new Parser(new Lexer().scan("x=f(1  ")).parse();
        assertEquals("Expected ')'", parsed.diagnostics().getFirst().message());
        assertEquals(new Lexer.Position(7, 1, 8), parsed.diagnostics().getFirst().position());
        var mismatched = new Parser(new Lexer().scan("x=f(a[1),2]")).parse();
        assertEquals("Mismatched closing delimiter", mismatched.diagnostics().getFirst().message());
        assertEquals(new Lexer.Position(7, 1, 8), mismatched.diagnostics().getFirst().position());
    }
    @Test void nestedCallsPreserveArgumentBoundaries() {
        for (String source : List.of("x=f(g(1),2)", "x=f(g(1,2),3)")) {
            var assign = assertInstanceOf(AST.Assign.class, parse(source).getFirst());
            var outer = assertInstanceOf(AST.FunctionCall.class, assign.expression());
            assertEquals("f", outer.name().text());
            assertEquals(2, outer.arguments().size());
            var inner = assertInstanceOf(AST.FunctionCall.class, outer.arguments().getFirst());
            assertEquals("g", inner.name().text());
            assertEquals(source.contains("1,2") ? 2 : 1, inner.arguments().size());
            assertEquals(source.contains("1,2") ? "3" : "2", assertInstanceOf(AST.Atom.class, outer.arguments().getLast()).token().text());
        }
    }
    @Test void parsesSwitchBlockAndRejectsMissingColon() {
        var program = parse("switch day:\ncase \"Sat\":\nprint(\"Saturday\")\ndefault:\nprint(\"Weekday\")\nendswitch");
        assertEquals(1, program.size());
        assertEquals("Switch", program.getFirst().getClass().getSimpleName());
        var error = assertThrows(Parser.ParseError.class, () -> parse("switch day\ncase \"Sat\":\nprint(1)\nendswitch"));
        assertTrue(error.getMessage().contains(":"));
        assertTrue(error.getMessage().contains("line 1, column 11"));
        assertThrows(Parser.ParseError.class, () -> parse("switch day:\ncase \"Sat\":\nprint(1)"));
        assertThrows(Parser.ParseError.class, () -> parse("switch day:\ncase other:\nprint(1)\nendswitch"));
    }
    @Test void lexesProjectXorOperatorWithExistingCaseRules() {
        var tokens = new Lexer().scan("XOR xor Xor").tokens();
        assertEquals(List.of("OP_XOR", "OP_XOR", "IDENTIFIER"),
                tokens.stream().map(token -> token.kind().name()).toList());
        assertEquals(List.of("XOR", "xor", "Xor"), tokens.stream().map(Lexer.Token::text).toList());
        assertEquals(1, tokens.getFirst().span().start().column());
        assertEquals(4, tokens.getFirst().span().end().column());
    }
    private List<AST.Statement> parse(String source) {
        System.out.println("Input: " + source);
        try {
            var result = new Parser(new Lexer().scan(source)).parse();
            if (!result.diagnostics().isEmpty()) {
                var diagnostic = result.diagnostics().getFirst();
                throw new Parser.ParseError(diagnostic.message() + " at line " + diagnostic.position().line() + ", column " + diagnostic.position().column());
            }
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
        var assignment = assertInstanceOf(AST.Assign.class, parse("x = 1 < 2 < 3").getFirst());
        assertEquals(List.of("1", "<", "2", "<", "3"), assertInstanceOf(AST.Unresolved.class, assignment.expression()).tokens().stream().map(Lexer.Token::text).toList());
    }
    @Test void parsesEstablishedArithmeticPrecedenceAndWordOperators() {
        for (String source : List.of("x = 2 + 3 * 4", "x = 2 DIV 3 + 4")) {
            var assign = assertInstanceOf(AST.Assign.class, parse(source).getFirst());
            var outer = assertInstanceOf(AST.Binary.class, assign.expression());
            assertEquals("+", outer.operator().text());
            var inner = assertInstanceOf(AST.Binary.class, source.contains("DIV") ? outer.left() : outer.right());
            assertEquals(source.contains("DIV") ? "DIV" : "*", inner.operator().text());
        }
        for (String operator : List.of("MOD", "DIV", "AND", "OR", "XOR", "^")) {
            var assign = assertInstanceOf(AST.Assign.class, parse("x = a " + operator + " b").getFirst());
            var binary = assertInstanceOf(AST.Binary.class, assign.expression());
            assertEquals(operator, binary.operator().text());
            assertEquals(7, binary.operator().span().start().column());
        }
        var assign = assertInstanceOf(AST.Assign.class, parse("x = NOT false").getFirst());
        assertEquals("NOT", assertInstanceOf(AST.Unary.class, assign.expression()).operator().text());
    }
    @Test void leavesUnsettledGroupingAndChainedComparisonsUnresolved() {
        for (String source : List.of("x = a < b == c")) {
            var assign = assertInstanceOf(AST.Assign.class, parse(source).getFirst());
            assertInstanceOf(AST.Unresolved.class, assign.expression(), source);
        }
    }
    @Test void groupsComparisonFirstAndRepeatedUnary() {
        var assign = assertInstanceOf(AST.Assign.class, parse("x = 1 + 2 < 3").getFirst());
        var add = assertInstanceOf(AST.Binary.class, assign.expression());
        assertEquals(Lexer.Kind.OP_PLUS, add.operator().kind());
        assertEquals(Lexer.Kind.OP_LT, assertInstanceOf(AST.Binary.class, add.right()).operator().kind());
        var unary = assertInstanceOf(AST.Assign.class, parse("x = --2").getFirst());
        assertInstanceOf(AST.Unary.class, assertInstanceOf(AST.Unary.class, unary.expression()).operand());
        var assoc = assertInstanceOf(AST.Assign.class, parse("x = 2 MOD 3 * 4").getFirst());
        assertEquals(Lexer.Kind.OP_MOD, assertInstanceOf(AST.Binary.class, assertInstanceOf(AST.Binary.class, assoc.expression()).left()).operator().kind());
        var power = assertInstanceOf(AST.Assign.class, parse("x = 2 ^ 3 ^ 4").getFirst());
        assertEquals(Lexer.Kind.OP_POWER, assertInstanceOf(AST.Binary.class, assertInstanceOf(AST.Binary.class, power.expression()).right()).operator().kind());
        var negative = assertInstanceOf(AST.Assign.class, parse("x = -1 < 2").getFirst());
        assertEquals(Lexer.Kind.OP_LT, assertInstanceOf(AST.Binary.class, assertInstanceOf(AST.Unary.class, negative.expression()).operand()).operator().kind());
    }
    @Test void keepsCaretTokensInsideExplicitGroups() {
        var assign = assertInstanceOf(AST.Assign.class, parse("x = (a ^ b) == true").getFirst());
        var comparison = assertInstanceOf(AST.Binary.class, assign.expression());
        var group = assertInstanceOf(AST.Group.class, comparison.left());
        var caret = assertInstanceOf(AST.Binary.class, group.expression());
        assertEquals("^", caret.operator().text());
        assertEquals(Lexer.Kind.OP_POWER, caret.operator().kind());
        assertEquals(8, caret.operator().span().start().column());
        assertEquals(9, caret.operator().span().end().column());
        var inequality = assertInstanceOf(AST.Assign.class, parse("x = (a ^ b) != false").getFirst());
        var comparison2 = assertInstanceOf(AST.Binary.class, inequality.expression());
        var group2 = assertInstanceOf(AST.Group.class, comparison2.left());
        assertEquals(Lexer.Kind.OP_POWER, assertInstanceOf(AST.Binary.class, group2.expression()).operator().kind());
    }
    @Test void groupsKnownLogicalPrecedenceAndExplicitArithmetic() {
        var logical = assertInstanceOf(AST.Assign.class, parse("x = true OR false AND true").getFirst());
        var or = assertInstanceOf(AST.Binary.class, logical.expression());
        assertEquals("OR", or.operator().text());
        assertEquals("AND", assertInstanceOf(AST.Binary.class, or.right()).operator().text());
        var arithmetic = assertInstanceOf(AST.Assign.class, parse("x = (2 MOD 3) * 4").getFirst());
        var multiply = assertInstanceOf(AST.Binary.class, arithmetic.expression());
        var group = assertInstanceOf(AST.Group.class, multiply.left());
        assertEquals("MOD", assertInstanceOf(AST.Binary.class, group.expression()).operator().text());
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
        for (String source : List.of("newFile", "files.newfile(\"x\")", "newFile(\"x\") extra"))
            assertInstanceOf(AST.Line.class, parse(source).getFirst(), source);
        assertTrue(assertThrows(Parser.ParseError.class, () -> parse("newFile(\"x\"")).getMessage().contains("Expected ')'"));
        for (String source : List.of("other.newFile(\"x\")"))
            assertInstanceOf(AST.ExpressionStatement.class, parse(source).getFirst(), source);
        assertThrows(Parser.ParseError.class, () -> parse("newFile(\"x\") @"));
    }
}
