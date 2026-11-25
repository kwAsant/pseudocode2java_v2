import java.util.ArrayList;
import java.util.AbstractList;
import java.util.List;
import java.util.Set;

/** Parses established OCR constructs; unimplemented syntax retains its tokens. */
public final class Parser {
    public static final class ParseError extends RuntimeException {
        public ParseError(String message) { super(message); }
    }
    public record Diagnostic(String message, Lexer.Position position) {}
    public static final class ParseResult extends AbstractList<AST.Statement> {
        private final List<AST.Statement> statements;
        private final List<Diagnostic> diagnostics;
        ParseResult(List<AST.Statement> statements, List<Diagnostic> diagnostics) { this.statements = List.copyOf(statements); this.diagnostics = List.copyOf(diagnostics); }
        public List<Diagnostic> diagnostics() { return diagnostics; }
        public List<AST.Statement> statements() { return statements; }
        @Override public AST.Statement get(int index) { return statements.get(index); }
        @Override public int size() { return statements.size(); }
    }
    private static final Set<Lexer.Kind> TERMINATORS = Set.of(Lexer.Kind.KEYWORD_ELSEIF, Lexer.Kind.KEYWORD_ELSE,
            Lexer.Kind.KEYWORD_ENDIF, Lexer.Kind.KEYWORD_ENDWHILE, Lexer.Kind.KEYWORD_NEXT, Lexer.Kind.KEYWORD_ENDFUNCTION, Lexer.Kind.KEYWORD_ENDPROCEDURE,
            Lexer.Kind.KEYWORD_CASE, Lexer.Kind.KEYWORD_DEFAULT, Lexer.Kind.KEYWORD_ENDSWITCH, Lexer.Kind.KEYWORD_UNTIL);
    private static final Set<Lexer.Kind> OPERATORS = Set.of(Lexer.Kind.OP_EQ, Lexer.Kind.OP_NE, Lexer.Kind.OP_LT,
            Lexer.Kind.OP_LE, Lexer.Kind.OP_GT, Lexer.Kind.OP_GE, Lexer.Kind.OP_PLUS, Lexer.Kind.OP_MINUS,
            Lexer.Kind.OP_MULTIPLY, Lexer.Kind.OP_DIVIDE, Lexer.Kind.OP_MOD, Lexer.Kind.OP_DIV,
            Lexer.Kind.OP_AND, Lexer.Kind.OP_OR, Lexer.Kind.OP_XOR, Lexer.Kind.OP_POWER);
    private final List<Lexer.Token> tokens;
    private int current;
    private final Lexer.Position end;
    private final List<Diagnostic> diagnostics = new ArrayList<>();

    public Parser(Lexer.Result result) {
        if (result.hasErrors()) throw new ParseError("Cannot parse: lexical errors stopped scanning");
        tokens = result.tokens();
        end = result.end();
    }
    public ParseResult parse() {
        List<AST.Statement> result = block(Set.of());
        return new ParseResult(result, diagnostics);
    }
    private List<AST.Statement> block(Set<Lexer.Kind> ends) {
        List<AST.Statement> statements = new ArrayList<>();
        while (!atEnd()) {
            if (check(Lexer.Kind.NEWLINE)) { current++; continue; }
            if (ends.contains(peek().kind())) break;
            int before = current;
            try {
            if (TERMINATORS.contains(peek().kind())) {
                if (!ends.isEmpty()) break;
                throw error("Unexpected block ending", peek());
            }
            if (check(Lexer.Kind.KEYWORD_IF)) statements.add(parseIf());
            else if (check(Lexer.Kind.KEYWORD_SWITCH)) statements.add(parseSwitch());
            else if (check(Lexer.Kind.KEYWORD_DO)) statements.add(parseDoUntil());
            else if (check(Lexer.Kind.KEYWORD_ARRAY)) statements.add(parseArray());
            else if (check(Lexer.Kind.KEYWORD_WHILE)) statements.add(parseWhile());
            else if (check(Lexer.Kind.KEYWORD_FOR)) statements.add(parseFor());
            else if (check(Lexer.Kind.KEYWORD_FUNCTION)) statements.add(parseSubroutine(true));
            else if (check(Lexer.Kind.KEYWORD_PROCEDURE)) statements.add(parseSubroutine(false));
            else statements.add(parseLine());
            } catch (ParseError error) {
                if (current == before && !atEnd() && !ends.contains(peek().kind())) take();
                while (!atEnd() && !check(Lexer.Kind.NEWLINE) && !TERMINATORS.contains(peek().kind())) take();
            }
        }
        return List.copyOf(statements);
    }
    private AST.DoUntil parseDoUntil() {
        Lexer.Token start = take();
        expect(Lexer.Kind.NEWLINE, "Expected newline after do");
        List<AST.Statement> body = block(Set.of(Lexer.Kind.KEYWORD_UNTIL));
        Lexer.Token until = expect(Lexer.Kind.KEYWORD_UNTIL, "Expected 'until'");
        return new AST.DoUntil(start, body, until, expression(until(Lexer.Kind.NEWLINE)));
    }
    private AST.ArrayDeclaration parseArray() {
        Lexer.Token keyword = take();
        Lexer.Token name = expect(Lexer.Kind.IDENTIFIER, "Expected array name");
        if (check(Lexer.Kind.OP_ASSIGN)) {
            Lexer.Token assignment = take();
            AST.ArrayLiteral initializer = arrayInitializer();
            return new AST.ArrayDeclaration(keyword, name, null, List.of(), null, assignment, initializer);
        }
        Lexer.Token open = expect(Lexer.Kind.LBRACKET, "Expected '['");
        int limit = current;
        while (limit < tokens.size() && tokens.get(limit).kind() != Lexer.Kind.NEWLINE) limit++;
        int closeIndex = matchingClose(tokens.subList(current - 1, limit), 0);
        if (closeIndex < 0) {
            if (closeIndex < -1) throw error("Mismatched closing delimiter", tokens.get(current - 1 + (-closeIndex - 2)));
            while (current < limit) take();
            throw atEnd() ? errorAtEnd("Expected ']'") : error("Expected ']'", peek());
        }
        List<Lexer.Token> items = new ArrayList<>();
        int target = current - 1 + closeIndex;
        while (current < target) items.add(take());
        Lexer.Token close = expect(Lexer.Kind.RBRACKET, "Expected ']'");
        List<AST.Expr> dimensions = arguments(items, open, close);
        Lexer.Token assignment = null; AST.ArrayLiteral initializer = null;
        if (check(Lexer.Kind.OP_ASSIGN)) { assignment = take(); initializer = arrayInitializer(); }
        return new AST.ArrayDeclaration(keyword, name, open, dimensions, close, assignment, initializer);
    }
    private AST.ArrayLiteral arrayInitializer() {
        Lexer.Token open = expect(Lexer.Kind.LBRACKET, "Expected '[' in array initializer");
        int limit = current;
        while (limit < tokens.size() && tokens.get(limit).kind() != Lexer.Kind.NEWLINE) limit++;
        int closeIndex = matchingClose(tokens.subList(current - 1, limit), 0);
        if (closeIndex < 0) {
            if (closeIndex < -1) throw error("Mismatched closing delimiter", tokens.get(current - 1 + (-closeIndex - 2)));
            while (current < limit) take();
            throw atEnd() ? errorAtEnd("Expected ']'") : error("Expected ']'", peek());
        }
        List<Lexer.Token> items = new ArrayList<>();
        int target = current - 1 + closeIndex;
        while (current < target) items.add(take());
        Lexer.Token close = expect(Lexer.Kind.RBRACKET, "Expected ']'");
        return new AST.ArrayLiteral(open, arguments(items, open, close), close);
    }
    private AST.Switch parseSwitch() {
        Lexer.Token start = take();
        AST.Expr selector = expression(until(Lexer.Kind.COLON));
        expect(Lexer.Kind.COLON, "Expected ':' after switch expression");
        expect(Lexer.Kind.NEWLINE, "Expected newline after switch header");
        while (check(Lexer.Kind.NEWLINE)) take();
        List<AST.Case> cases = new ArrayList<>();
        while (check(Lexer.Kind.KEYWORD_CASE)) {
            Lexer.Token keyword = take();
            if (atEnd()) throw errorAtEnd("Expected case literal");
            Lexer.Token first = peek();
            List<Lexer.Token> label = until(Lexer.Kind.COLON);
            boolean literal = label.size() == 1 && Set.of(Lexer.Kind.NUMBER, Lexer.Kind.STRING, Lexer.Kind.CHARACTER,
                    Lexer.Kind.KEYWORD_TRUE, Lexer.Kind.KEYWORD_FALSE).contains(first.kind());
            boolean signedNumber = label.size() == 2 && (first.kind() == Lexer.Kind.OP_MINUS || first.kind() == Lexer.Kind.OP_PLUS)
                    && label.get(1).kind() == Lexer.Kind.NUMBER;
            if (!literal && !signedNumber)
                throw error("Expected case literal", first);
            expect(Lexer.Kind.COLON, "Expected ':' after case literal");
            expect(Lexer.Kind.NEWLINE, "Expected newline after case label");
            cases.add(new AST.Case(keyword, expression(label), block(Set.of(Lexer.Kind.KEYWORD_CASE, Lexer.Kind.KEYWORD_DEFAULT, Lexer.Kind.KEYWORD_ENDSWITCH))));
        }
        List<AST.Statement> otherwise = List.of();
        if (check(Lexer.Kind.KEYWORD_DEFAULT)) {
            take(); expect(Lexer.Kind.COLON, "Expected ':' after default");
            expect(Lexer.Kind.NEWLINE, "Expected newline after default");
            otherwise = block(Set.of(Lexer.Kind.KEYWORD_ENDSWITCH));
        }
        return new AST.Switch(start, selector, cases, otherwise, expect(Lexer.Kind.KEYWORD_ENDSWITCH, "Expected 'endswitch'"));
    }
    private AST.Statement parseSubroutine(boolean function) {
        take();
        Lexer.Token name = expect(Lexer.Kind.IDENTIFIER, "Expected subroutine name");
        expect(Lexer.Kind.LPAREN, "Expected '('");
        List<AST.Parameter> parameters = new ArrayList<>();
        while (!check(Lexer.Kind.RPAREN)) {
            Lexer.Token parameter = expect(Lexer.Kind.IDENTIFIER, "Expected parameter name");
            boolean byReference = false;
            if (check(Lexer.Kind.COLON)) {
                take(); Lexer.Token mode = expect(Lexer.Kind.ANNOTATION, "Expected byVal or byRef");
                byReference = mode.text().equals("byRef");
            }
            parameters.add(new AST.Parameter(parameter, byReference));
            if (!check(Lexer.Kind.COMMA)) break;
            take();
        }
        expect(Lexer.Kind.RPAREN, "Expected ')'");
        expect(Lexer.Kind.NEWLINE, "Expected newline after declaration");
        Lexer.Kind end = function ? Lexer.Kind.KEYWORD_ENDFUNCTION : Lexer.Kind.KEYWORD_ENDPROCEDURE;
        var body = block(Set.of(end));
        expect(end, "Expected subroutine ending");
        return function ? new AST.Function(name, parameters, body) : new AST.Procedure(name, parameters, body);
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
    private AST.For parseFor() {
        Lexer.Token start = take();
        Lexer.Token variable = expect(Lexer.Kind.IDENTIFIER, "Expected loop variable");
        expect(Lexer.Kind.OP_ASSIGN, "Expected '=' after loop variable");
        AST.Expr from = expression(until(Lexer.Kind.KEYWORD_TO));
        expect(Lexer.Kind.KEYWORD_TO, "Expected 'to'");
        List<Lexer.Token> endTokens = new ArrayList<>();
        while (!atEnd() && !check(Lexer.Kind.KEYWORD_STEP) && !check(Lexer.Kind.NEWLINE)) endTokens.add(take());
        if (endTokens.isEmpty()) throw error("Expected loop end", start);
        AST.Expr step = null;
        if (check(Lexer.Kind.KEYWORD_STEP)) { take(); step = expression(until(Lexer.Kind.NEWLINE)); }
        expect(Lexer.Kind.NEWLINE, "Expected newline after for header");
        var body = block(Set.of(Lexer.Kind.KEYWORD_NEXT));
        Lexer.Token end = expect(Lexer.Kind.KEYWORD_NEXT, "Expected 'next'");
        Lexer.Token repeated = expect(Lexer.Kind.IDENTIFIER, "Expected loop variable after next");
        if (!repeated.key().equals(variable.key())) throw error("Loop variable after next does not match", repeated);
        return new AST.For(start, variable, from, expression(endTokens), step, body, end);
    }
    private List<Lexer.Token> until(Lexer.Kind end) {
        List<Lexer.Token> result = new ArrayList<>();
        while (!atEnd() && !check(end) && !check(Lexer.Kind.NEWLINE) && !TERMINATORS.contains(peek().kind())) result.add(take());
        if (result.isEmpty()) throw atEnd() ? errorAtEnd("Expected a condition") : error("Expected a condition", peek());
        return List.copyOf(result);
    }
    private AST.Statement parseLine() {
        List<Lexer.Token> line = new ArrayList<>();
        while (!atEnd() && !check(Lexer.Kind.NEWLINE) && !TERMINATORS.contains(peek().kind())) line.add(take());
        AST.Call call = newFileCall(line);
        if (call != null) return call;
        if (line.size() == 1 && line.getFirst().kind() == Lexer.Kind.KEYWORD_BREAK) return new AST.Break(line.getFirst());
        if (!line.isEmpty() && line.getFirst().kind() == Lexer.Kind.KEYWORD_RETURN)
            return new AST.Return(line.getFirst(), expression(line.subList(1, line.size())));
        if (line.size() >= 2 && line.getFirst().kind() == Lexer.Kind.IDENTIFIER && line.get(1).kind() == Lexer.Kind.LBRACKET) {
            int close = matchingClose(line, 1);
            if (close < 0) {
                if (close < -1) throw error("Mismatched closing delimiter", line.get(-close - 2));
                throw atEnd() ? errorAtEnd("Expected ']'") : error("Expected ']'", peek());
            }
            if (close + 1 < line.size() && line.get(close + 1).kind() == Lexer.Kind.OP_ASSIGN) {
                var target = (AST.Index) expression(line.subList(0, close + 1));
                if (close + 2 == line.size()) throw atEnd() ? errorAtEnd("Expected expression") : error("Expected expression", peek());
                return new AST.IndexAssignment(target, line.get(close + 1), expression(line.subList(close + 2, line.size())));
            }
        }
        if (line.size() >= 2 && line.get(0).kind() == Lexer.Kind.IDENTIFIER && line.get(1).kind() == Lexer.Kind.OP_ASSIGN) {
            if (line.size() == 2) throw errorAtBoundary("Expected expression");
            return new AST.Assign(line.get(0), expression(line.subList(2, line.size())));
        }
        if (line.size() >= 2 && line.getFirst().kind() == Lexer.Kind.BUILTIN_PRINT && line.get(1).kind() == Lexer.Kind.LPAREN) {
            int close = matchingClose(line, 1);
            if (close == -1) throw errorAtBoundary("Expected ')'");
            if (close < -1) throw error("Mismatched closing delimiter", line.get(-close - 2));
        }
        if (line.size() >= 4 && line.get(0).kind() == Lexer.Kind.BUILTIN_PRINT && wrapped(line.subList(1, line.size())))
            return new AST.Print(line.get(0), expression(line.subList(2, line.size() - 1), line.getLast().span().start()));
        if (line.size() >= 3 && line.getFirst().kind() == Lexer.Kind.IDENTIFIER && wrapped(line.subList(1, line.size())))
            return new AST.Invoke(line.getFirst(), arguments(line.subList(2, line.size() - 1), line.get(1), line.getLast()));
        if (line.size() >= 3 && line.getFirst().kind() == Lexer.Kind.IDENTIFIER && line.get(1).kind() == Lexer.Kind.DOT) {
            AST.Expr parsed = expression(line);
            if (parsed instanceof AST.CallExpression statement) return new AST.ExpressionStatement(statement);
        }
        if (line.size() >= 2 && line.getFirst().kind() == Lexer.Kind.IDENTIFIER && line.get(1).kind() == Lexer.Kind.LPAREN)
            expression(line);
        return new AST.Line(line);
    }
    private AST.Call newFileCall(List<Lexer.Token> line) {
        int method = 0;
        if (line.size() >= 3 && line.get(0).kind() == Lexer.Kind.IDENTIFIER && "files".equals(line.get(0).key())
                && line.get(1).kind() == Lexer.Kind.DOT) method = 2;
        if (line.size() <= method + 2 || line.get(method).kind() != Lexer.Kind.IDENTIFIER
                || !line.get(method).text().equals("newFile") || !wrapped(line.subList(method + 1, line.size()))) return null;
        return new AST.Call("files", line.get(method), line.subList(method + 2, line.size() - 1),
                arguments(line.subList(method + 2, line.size() - 1), line.get(method + 1), line.getLast()),
                new Lexer.Span(line.getFirst().span().start(), line.getLast().span().end()));
    }
    private static boolean wrapped(List<Lexer.Token> tokens) {
        if (tokens.size() < 2 || tokens.getFirst().kind() != Lexer.Kind.LPAREN || tokens.getLast().kind() != Lexer.Kind.RPAREN) return false;
        return matchingClose(tokens, 0) == tokens.size() - 1;
    }
    private AST.Expr expression(List<Lexer.Token> tokens) {
        return expression(tokens, atEnd() ? end : peek().span().start());
    }
    private AST.Expr expression(List<Lexer.Token> tokens, Lexer.Position boundary) {
        if (!tokens.isEmpty()) {
            Lexer.Kind last = tokens.getLast().kind();
            if (OPERATORS.contains(last) || last == Lexer.Kind.OP_NOT)
                throw errorAt("Expected expression", boundary);
            Lexer.Kind first = tokens.getFirst().kind();
            if (OPERATORS.contains(first) && first != Lexer.Kind.OP_PLUS && first != Lexer.Kind.OP_MINUS)
                throw error("Expected expression", tokens.getFirst());
        }
        AST.Expr postfix = postfix(tokens, boundary);
        if (postfix != null) return postfix;
        if (tokens.size() == 1) {
            Lexer.Kind kind = tokens.getFirst().kind();
            if (Set.of(Lexer.Kind.IDENTIFIER, Lexer.Kind.NUMBER, Lexer.Kind.STRING, Lexer.Kind.CHARACTER,
                    Lexer.Kind.KEYWORD_TRUE, Lexer.Kind.KEYWORD_FALSE).contains(kind)) return new AST.Atom(tokens.getFirst());
        }
        if (wrapped(tokens)) return new AST.Group(tokens.getFirst(), expression(tokens.subList(1, tokens.size() - 1), tokens.getLast().span().start()), tokens.getLast());
        List<Lexer.Kind> delimiters = new ArrayList<>();
        int operator = -1, weakest = Integer.MAX_VALUE, comparisons = 0;
        for (int i = 0; i < tokens.size(); i++) {
            Lexer.Kind kind = tokens.get(i).kind();
            if (kind == Lexer.Kind.LPAREN || kind == Lexer.Kind.LBRACKET || kind == Lexer.Kind.LBRACE) delimiters.add(kind);
            else if (kind == Lexer.Kind.RPAREN || kind == Lexer.Kind.RBRACKET || kind == Lexer.Kind.RBRACE) {
                if (delimiters.isEmpty()) return new AST.Unresolved(tokens);
                var open = delimiters.removeLast();
                if (!(open == Lexer.Kind.LPAREN && kind == Lexer.Kind.RPAREN || open == Lexer.Kind.LBRACKET && kind == Lexer.Kind.RBRACKET || open == Lexer.Kind.LBRACE && kind == Lexer.Kind.RBRACE)) return new AST.Unresolved(tokens);
            }
            else if (delimiters.isEmpty() && (OPERATORS.contains(kind) || kind == Lexer.Kind.OP_NOT)) {
                boolean unary = kind == Lexer.Kind.OP_NOT || (kind == Lexer.Kind.OP_PLUS || kind == Lexer.Kind.OP_MINUS)
                        && (i == 0 || OPERATORS.contains(tokens.get(i - 1).kind()) || tokens.get(i - 1).kind() == Lexer.Kind.OP_NOT);
                if (unary && i != 0) continue;
                int rank = unary ? 6 : operatorRank(kind);
                if (!unary && rank == 8) comparisons++;
                if (rank < weakest || rank == weakest && kind != Lexer.Kind.OP_POWER) { operator = i; weakest = rank; }
            }
        }
        if (delimiters.isEmpty() && weakest == 8 && comparisons > 1) return new AST.Unresolved(tokens);
        if (delimiters.isEmpty() && operator > 0 && operator < tokens.size() - 1)
            return new AST.Binary(expression(tokens.subList(0, operator), tokens.get(operator).span().start()), tokens.get(operator), expression(tokens.subList(operator + 1, tokens.size()), boundary));
        if (delimiters.isEmpty() && operator == 0 && tokens.size() > 1 && (tokens.getFirst().kind() == Lexer.Kind.OP_MINUS
                || tokens.getFirst().kind() == Lexer.Kind.OP_PLUS || tokens.getFirst().kind() == Lexer.Kind.OP_NOT))
            return new AST.Unary(tokens.getFirst(), expression(tokens.subList(1, tokens.size()), boundary));
        return new AST.Unresolved(tokens);
    }
    private static int operatorRank(Lexer.Kind kind) {
        return switch (kind) {
            case OP_OR -> 1;
            case OP_XOR -> 2;
            case OP_AND -> 3;
            case OP_PLUS, OP_MINUS -> 4;
            case OP_MULTIPLY, OP_DIVIDE, OP_MOD, OP_DIV -> 5;
            case OP_POWER -> 7;
            case OP_EQ, OP_NE, OP_LT, OP_LE, OP_GT, OP_GE -> 8;
            default -> throw new IllegalArgumentException("Not a binary operator: " + kind);
        };
    }
    private AST.Expr postfix(List<Lexer.Token> tokens, Lexer.Position boundary) {
        if (tokens.size() < 2) return null;
        Lexer.Kind first = tokens.getFirst().kind();
        if (!(first == Lexer.Kind.IDENTIFIER || first == Lexer.Kind.MEMBER && Set.of("openRead", "openWrite").contains(tokens.getFirst().text()) || first.name().startsWith("BUILTIN_") || Set.of(Lexer.Kind.KEYWORD_INT, Lexer.Kind.KEYWORD_FLOAT, Lexer.Kind.KEYWORD_REAL, Lexer.Kind.KEYWORD_STR, Lexer.Kind.KEYWORD_BOOL).contains(first))) return null;
        int diagnosticStart = diagnostics.size();
        AST.Expr result = new AST.Atom(tokens.getFirst());
        int i = 1;
        while (i < tokens.size()) {
            Lexer.Token open = tokens.get(i);
            if (open.kind() == Lexer.Kind.DOT && i + 1 < tokens.size() && (tokens.get(i + 1).kind() == Lexer.Kind.MEMBER || tokens.get(i + 1).kind() == Lexer.Kind.IDENTIFIER)) {
                result = new AST.MemberAccess(result, open, tokens.get(i + 1)); i += 2; continue;
            }
            if (open.kind() != Lexer.Kind.LPAREN && open.kind() != Lexer.Kind.LBRACKET) {
                diagnostics.subList(diagnosticStart, diagnostics.size()).clear();
                return null;
            }
            int closeIndex = matchingClose(tokens, i);
            if (closeIndex < 0) {
                Lexer.Position position = closeIndex < -1 ? tokens.get(-closeIndex - 2).span().start() : boundary;
                String message = closeIndex < -1 ? "Mismatched closing delimiter" : open.kind() == Lexer.Kind.LPAREN ? "Expected ')'" : "Expected ']'";
                diagnostics.add(new Diagnostic(message, position));
                return new AST.Unresolved(tokens);
            }
            Lexer.Token close = tokens.get(closeIndex);
            List<AST.Expr> parts = arguments(tokens.subList(i + 1, closeIndex), open, close);
            if (open.kind() == Lexer.Kind.LBRACKET) result = new AST.Index(result, open, parts, close);
            else if (result instanceof AST.Atom atom && atom.token().kind() == Lexer.Kind.IDENTIFIER) result = new AST.FunctionCall(atom.token(), parts);
            else result = new AST.CallExpression(result, open, parts, close);
            i = closeIndex + 1;
        }
        return result;
    }
    private static int matchingClose(List<Lexer.Token> tokens, int start) {
        List<Lexer.Kind> stack = new ArrayList<>();
        for (int i = start; i < tokens.size(); i++) {
            var kind = tokens.get(i).kind();
            if (kind == Lexer.Kind.LPAREN || kind == Lexer.Kind.LBRACKET || kind == Lexer.Kind.LBRACE) stack.add(kind);
            else if (kind == Lexer.Kind.RPAREN || kind == Lexer.Kind.RBRACKET || kind == Lexer.Kind.RBRACE) {
                if (stack.isEmpty()) return -i - 2;
                var open = stack.removeLast();
                if (!(open == Lexer.Kind.LPAREN && kind == Lexer.Kind.RPAREN || open == Lexer.Kind.LBRACKET && kind == Lexer.Kind.RBRACKET || open == Lexer.Kind.LBRACE && kind == Lexer.Kind.RBRACE)) return -i - 2;
                if (stack.isEmpty()) return i;
            }
        }
        return -1;
    }
    private List<AST.Expr> arguments(List<Lexer.Token> tokens, Lexer.Token open, Lexer.Token close) {
        if (tokens.isEmpty()) {
            if (open != null && open.kind() == Lexer.Kind.LBRACKET && close != null)
                diagnostics.add(new Diagnostic("Expected expression", close.span().start()));
            return List.of();
        }
        List<AST.Expr> result = new ArrayList<>(); List<Lexer.Kind> stack = new ArrayList<>(); int start = 0;
        for (int i = 0; i < tokens.size(); i++) {
            var kind = tokens.get(i).kind();
            if (kind == Lexer.Kind.LPAREN || kind == Lexer.Kind.LBRACKET || kind == Lexer.Kind.LBRACE) stack.add(kind);
            else if (kind == Lexer.Kind.RPAREN || kind == Lexer.Kind.RBRACKET || kind == Lexer.Kind.RBRACE) { if (!stack.isEmpty()) stack.removeLast(); }
            else if (kind == Lexer.Kind.COMMA && stack.isEmpty()) {
                if (start == i) diagnostics.add(new Diagnostic("Expected expression", tokens.get(i).span().start()));
                result.add(expression(tokens.subList(start, i), tokens.get(i).span().start())); start = i + 1;
            }
        }
        if (start == tokens.size() && close != null) diagnostics.add(new Diagnostic("Expected expression", close.span().start()));
        result.add(expression(tokens.subList(start, tokens.size()), close != null ? close.span().start() : atEnd() ? end : peek().span().start()));
        return List.copyOf(result);
    }
    private boolean atEnd() { return current >= tokens.size(); }
    private boolean check(Lexer.Kind kind) { return !atEnd() && peek().kind() == kind; }
    private Lexer.Token peek() { return tokens.get(current); }
    private Lexer.Token take() { return tokens.get(current++); }
    private Lexer.Token expect(Lexer.Kind kind, String message) {
        if (check(kind)) return take();
        throw atEnd() ? errorAtEnd(message) : error(message, peek());
    }
    private ParseError error(String message, Lexer.Token token) {
        diagnostics.add(new Diagnostic(message, token.span().start()));
        return new ParseError(message + " at line " + token.span().start().line() + ", column " + token.span().start().column());
    }
    private ParseError errorAtEnd(String message) {
        return errorAt(message, end);
    }
    private ParseError errorAtBoundary(String message) { return errorAt(message, atEnd() ? end : peek().span().start()); }
    private ParseError errorAt(String message, Lexer.Position position) {
        diagnostics.add(new Diagnostic(message, position));
        return new ParseError(message + " at line " + position.line() + ", column " + position.column());
    }
}
