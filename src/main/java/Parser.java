import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Parses established OCR constructs; unimplemented syntax retains its tokens. */
public final class Parser {
    public static final class ParseError extends RuntimeException {
        public ParseError(String message) { super(message); }
    }
    private static final Set<Lexer.Kind> TERMINATORS = Set.of(Lexer.Kind.KEYWORD_ELSEIF, Lexer.Kind.KEYWORD_ELSE,
            Lexer.Kind.KEYWORD_ENDIF, Lexer.Kind.KEYWORD_ENDWHILE);
    private static final Set<Lexer.Kind> OPERATORS = Set.of(Lexer.Kind.OP_EQ, Lexer.Kind.OP_NE, Lexer.Kind.OP_LT,
            Lexer.Kind.OP_LE, Lexer.Kind.OP_GT, Lexer.Kind.OP_GE, Lexer.Kind.OP_PLUS, Lexer.Kind.OP_MINUS,
            Lexer.Kind.OP_MULTIPLY, Lexer.Kind.OP_DIVIDE);
    private final List<Lexer.Token> tokens;
    private int current;

    public Parser(Lexer.Result result) {
        if (result.hasErrors()) throw new ParseError("Cannot parse: lexical errors stopped scanning");
        tokens = result.tokens();
    }
    public List<AST.Statement> parse() {
        List<AST.Statement> result = block(Set.of());
        if (!atEnd()) throw error("Unexpected block ending", peek());
        return result;
    }
    private List<AST.Statement> block(Set<Lexer.Kind> ends) {
        List<AST.Statement> statements = new ArrayList<>();
        while (!atEnd()) {
            if (check(Lexer.Kind.NEWLINE)) { current++; continue; }
            if (ends.contains(peek().kind())) break;
            if (TERMINATORS.contains(peek().kind())) throw error("Unexpected block ending", peek());
            if (check(Lexer.Kind.KEYWORD_IF)) statements.add(parseIf());
            else if (check(Lexer.Kind.KEYWORD_WHILE)) statements.add(parseWhile());
            else statements.add(parseLine());
        }
        return List.copyOf(statements);
    }
    private AST.If parseIf() {
        Lexer.Token start = take();
        List<AST.Branch> branches = new ArrayList<>();
        branches.add(branch());
        while (check(Lexer.Kind.KEYWORD_ELSEIF)) { take(); branches.add(branch()); }
        List<AST.Statement> otherwise = List.of();
        if (check(Lexer.Kind.KEYWORD_ELSE)) { take(); otherwise = block(Set.of(Lexer.Kind.KEYWORD_ENDIF)); }
        return new AST.If(start, branches, otherwise, expect(Lexer.Kind.KEYWORD_ENDIF, "Expected 'endif'"));
    }
    private AST.Branch branch() {
        List<Lexer.Token> condition = until(Lexer.Kind.KEYWORD_THEN);
        expect(Lexer.Kind.KEYWORD_THEN, "Expected 'then'");
        return new AST.Branch(expression(condition), block(Set.of(Lexer.Kind.KEYWORD_ELSEIF, Lexer.Kind.KEYWORD_ELSE, Lexer.Kind.KEYWORD_ENDIF)));
    }
    private AST.While parseWhile() {
        Lexer.Token start = take();
        List<Lexer.Token> condition = until(Lexer.Kind.NEWLINE);
        expect(Lexer.Kind.NEWLINE, "Expected a newline after the while condition");
        List<AST.Statement> body = block(Set.of(Lexer.Kind.KEYWORD_ENDWHILE));
        return new AST.While(start, expression(condition), body, expect(Lexer.Kind.KEYWORD_ENDWHILE, "Expected 'endwhile'"));
    }
    private List<Lexer.Token> until(Lexer.Kind end) {
        List<Lexer.Token> result = new ArrayList<>();
        while (!atEnd() && !check(end) && !check(Lexer.Kind.NEWLINE) && !TERMINATORS.contains(peek().kind())) result.add(take());
        if (result.isEmpty()) throw atEnd() ? new ParseError("Expected a condition at end of input") : error("Expected a condition", peek());
        return List.copyOf(result);
    }
    private AST.Statement parseLine() {
        List<Lexer.Token> line = new ArrayList<>();
        while (!atEnd() && !check(Lexer.Kind.NEWLINE) && !TERMINATORS.contains(peek().kind())) line.add(take());
        AST.Call call = newFileCall(line);
        if (call != null) return call;
        if (line.size() >= 3 && line.get(0).kind() == Lexer.Kind.IDENTIFIER && line.get(1).kind() == Lexer.Kind.OP_ASSIGN)
            return new AST.Assign(line.get(0), expression(line.subList(2, line.size())));
        if (line.size() >= 4 && line.get(0).kind() == Lexer.Kind.BUILTIN_PRINT && wrapped(line.subList(1, line.size())))
            return new AST.Print(line.get(0), expression(line.subList(2, line.size() - 1)));
        return new AST.Line(line);
    }
    private AST.Call newFileCall(List<Lexer.Token> line) {
        int method = 0;
        if (line.size() >= 3 && line.get(0).kind() == Lexer.Kind.IDENTIFIER && "files".equals(line.get(0).key())
                && line.get(1).kind() == Lexer.Kind.DOT) method = 2;
        if (line.size() <= method + 2 || line.get(method).kind() != Lexer.Kind.IDENTIFIER
                || !line.get(method).text().equals("newFile") || !wrapped(line.subList(method + 1, line.size()))) return null;
        return new AST.Call("files", line.get(method), line.subList(method + 2, line.size() - 1),
                new Lexer.Span(line.getFirst().span().start(), line.getLast().span().end()));
    }
    private static boolean wrapped(List<Lexer.Token> tokens) {
        if (tokens.size() < 2 || tokens.getFirst().kind() != Lexer.Kind.LPAREN || tokens.getLast().kind() != Lexer.Kind.RPAREN) return false;
        int depth = 0;
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).kind() == Lexer.Kind.LPAREN) depth++;
            if (tokens.get(i).kind() == Lexer.Kind.RPAREN && --depth == 0 && i != tokens.size() - 1) return false;
            if (depth < 0) return false;
        }
        return depth == 0;
    }
    private static AST.Expr expression(List<Lexer.Token> tokens) {
        if (tokens.size() == 1) {
            Lexer.Kind kind = tokens.getFirst().kind();
            if (Set.of(Lexer.Kind.IDENTIFIER, Lexer.Kind.NUMBER, Lexer.Kind.STRING, Lexer.Kind.CHARACTER,
                    Lexer.Kind.KEYWORD_TRUE, Lexer.Kind.KEYWORD_FALSE).contains(kind)) return new AST.Atom(tokens.getFirst());
        }
        if (wrapped(tokens)) return new AST.Group(tokens.getFirst(), expression(tokens.subList(1, tokens.size() - 1)), tokens.getLast());
        int depth = 0, operator = -1;
        for (int i = 0; i < tokens.size(); i++) {
            Lexer.Kind kind = tokens.get(i).kind();
            if (kind == Lexer.Kind.LPAREN) depth++;
            else if (kind == Lexer.Kind.RPAREN) depth--;
            else if (depth == 0 && OPERATORS.contains(kind)) {
                if (operator != -1) return new AST.Unresolved(tokens);
                operator = i;
            }
            if (depth < 0) return new AST.Unresolved(tokens);
        }
        if (depth == 0 && operator > 0 && operator < tokens.size() - 1)
            return new AST.Binary(expression(tokens.subList(0, operator)), tokens.get(operator), expression(tokens.subList(operator + 1, tokens.size())));
        return new AST.Unresolved(tokens);
    }
    private boolean atEnd() { return current >= tokens.size(); }
    private boolean check(Lexer.Kind kind) { return !atEnd() && peek().kind() == kind; }
    private Lexer.Token peek() { return tokens.get(current); }
    private Lexer.Token take() { return tokens.get(current++); }
    private Lexer.Token expect(Lexer.Kind kind, String message) {
        if (check(kind)) return take();
        throw atEnd() ? new ParseError(message + " at end of input") : error(message, peek());
    }
    private ParseError error(String message, Lexer.Token token) {
        return new ParseError(message + " at line " + token.span().start().line() + ", column " + token.span().start().column());
    }
}
