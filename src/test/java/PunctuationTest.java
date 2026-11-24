import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;

class PunctuationTest {
    record Example(String source, String punctuation) {}
    private static final List<Example> EXAMPLES = List.of(
        new Example("(", "("), new Example(")", ")"),
        new Example("[", "["), new Example("]", "]"),
        new Example("{", "{"), new Example("}", "}"),
        new Example(",", ","), new Example(":", ":"),
        new Example(".", "."), new Example("()", "()"),
        new Example("[]", "[]"), new Example("{}", "{}"),
        new Example("print(1)", "()"), new Example("input(\"name\")", "()"),
        new Example("print()", "()"), new Example("print((x))", "(())"),
        new Example("print(x,y)", "(,)"), new Example("f(a,b,c)", "(,,)"),
        new Example("x=f(g(1))", "(())"), new Example("x=(1+2)*(3+4)", "()()"),
        new Example("array a[5]", "[]"), new Example("a[0]=1", "[]"),
        new Example("array board[8,8]", "[,]"), new Example("board[0,1]=x", "[,]"),
        new Example("print(a[0])", "([])"), new Example("a[f(x)]=1", "[()]"),
        new Example("array a=[1,2,3]", "[,,]"), new Example("x=a[i]+a[j]", "[][]"),
        new Example("switch x:", ":"), new Example("case 1:", ":"),
        new Example("default:", ":"), new Example("procedure p(x:byVal,y:byRef)", "(:,:)"),
        new Example("function f(x,y)", "(,)"), new Example("p(a[0],b[1])", "([],[])"),
        new Example("x=s.length", "."), new Example("x=s.substring(3,3)", ".(,)"),
        new Example("print(s.length)", "(.)"), new Example("f.readLine()", ".()"),
        new Example("f.writeLine(\"a,b\")", ".()"), new Example("print(\"([{}]),:.\")", "()"),
        new Example("x=(a[1]+f(b[2]))", "([]([]))"), new Example("if (x>1) then", "()"),
        new Example("while (x<5)", "()"), new Example("x={a[0]}", "{[]}"),
        new Example("x=({a,b})", "({,})"), new Example("print(\"x\") // ([{}])", "()")
    );

    static Stream<Arguments> punctuationExamples() {
        return IntStream.range(0, EXAMPLES.size()).boxed().flatMap(index ->
            Stream.of(false, true).map(spaced -> {
                    Example example = EXAMPLES.get(index);
                    return Arguments.of((spaced ? "  " : "") + example.source(), example.punctuation());
            }));
    }

    @ParameterizedTest(name = "{index}: punctuation in [{0}]")
    @MethodSource("punctuationExamples")
    void punctuationAcrossConstructs(String source, String expectedText) {
                    var result = new Lexer().scan(source);
                    var punctuation = result.tokens().stream().filter(t -> switch (t.kind()) {
                        case LPAREN, RPAREN, LBRACKET, RBRACKET, LBRACE, RBRACE, COMMA, COLON, DOT -> true;
                        default -> false;
                    }).toList();
                    var expectedKinds = expectedText.chars().mapToObj(c -> expectedKind((char) c)).toList();
                    System.out.println("Input: " + source);
                    System.out.println("Expected punctuation: " + expectedText + ", kinds: " + expectedKinds + ", diagnostics: []");
                    System.out.println("Actual punctuation: " + punctuation.stream().map(Lexer.Token::text).toList() + ", kinds: " + punctuation.stream().map(Lexer.Token::kind).toList() + ", diagnostics: " + result.diagnostics());
                    assertFalse(result.hasErrors(), () -> result.diagnostics().toString());
                    assertEquals(expectedText.length(), punctuation.size());
                    for (int i = 0; i < punctuation.size(); i++) {
                        char expected = expectedText.charAt(i);
                        var token = punctuation.get(i);
                        assertEquals(String.valueOf(expected), token.text());
                        assertEquals(expectedKind(expected), token.kind());
                        assertNull(token.value());
                        assertEquals(token.text(), source.substring(token.span().start().offset(), token.span().end().offset()));
                    }
    }

    private static Lexer.Kind expectedKind(char character) {
        return switch (character) {
            case '(' -> Lexer.Kind.LPAREN;
            case ')' -> Lexer.Kind.RPAREN;
            case '[' -> Lexer.Kind.LBRACKET;
            case ']' -> Lexer.Kind.RBRACKET;
            case '{' -> Lexer.Kind.LBRACE;
            case '}' -> Lexer.Kind.RBRACE;
            case ',' -> Lexer.Kind.COMMA;
            case ':' -> Lexer.Kind.COLON;
            case '.' -> Lexer.Kind.DOT;
            default -> throw new AssertionError(character);
        };
    }
}
