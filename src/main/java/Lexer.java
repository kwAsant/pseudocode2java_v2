import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Lexer for the documented J277 project dialect. No parsing or type checking. */
public final class Lexer {
    public enum Kind { IDENTIFIER, MEMBER, ANNOTATION, NUMBER, STRING, CHARACTER, KEYWORD_FOR, KEYWORD_TO, KEYWORD_STEP, KEYWORD_NEXT, KEYWORD_WHILE, KEYWORD_ENDWHILE, KEYWORD_DO, KEYWORD_UNTIL, KEYWORD_IF, KEYWORD_THEN, KEYWORD_ELSEIF, KEYWORD_ELSE, KEYWORD_ENDIF, KEYWORD_SWITCH, KEYWORD_CASE, KEYWORD_DEFAULT, KEYWORD_ENDSWITCH, KEYWORD_ARRAY, KEYWORD_GLOBAL, KEYWORD_CONST, KEYWORD_FUNCTION, KEYWORD_ENDFUNCTION, KEYWORD_PROCEDURE, KEYWORD_ENDPROCEDURE, KEYWORD_RETURN, KEYWORD_INT, KEYWORD_FLOAT, KEYWORD_STR, KEYWORD_BOOL, KEYWORD_REAL, KEYWORD_STRING, KEYWORD_INTEGER, KEYWORD_BOOLEAN, KEYWORD_TRUE, KEYWORD_FALSE, BUILTIN_PRINT, BUILTIN_INPUT, BUILTIN_RANDOM, BUILTIN_OPEN, BUILTIN_NEWFILE, BUILTIN_ASC, BUILTIN_CHR, OP_MOD, OP_DIV, OP_AND, OP_OR, OP_NOT, OP_ASSIGN, OP_EQ, OP_NE, OP_LT, OP_LE, OP_GT, OP_GE, OP_PLUS, OP_MINUS, OP_MULTIPLY, OP_DIVIDE, OP_POWER, LPAREN, RPAREN, LBRACKET, RBRACKET, LBRACE, RBRACE, COMMA, COLON, DOT, NEWLINE }
    public enum Severity { WARNING, ERROR }
    public record Position(int offset, int line, int column) {}
    public record Span(Position start, Position end) {}
    public record Token(Kind kind, String text, String value, Span span) {
        public String key() { return kind == Kind.IDENTIFIER ? text.toLowerCase(Locale.ROOT) : null; }
    }
    public record Diagnostic(Severity severity, String category, String message, Span span) {}
    public record Trivia(String text, Span span) {}
    public record Result(List<Token> tokens, List<Trivia> trivia, List<Diagnostic> diagnostics) {
        public boolean hasErrors() { return diagnostics.stream().anyMatch(d -> d.severity == Severity.ERROR); }
    }
    private enum State { DEFAULT, STRING, CHARACTER, COMMENT }
    private static final Set<String> KEYWORDS = Set.of("for", "to", "step", "next", "while", "endwhile", "do", "until", "if", "then", "elseif", "else", "endif", "switch", "case", "default", "endswitch", "array", "global", "const", "function", "endfunction", "procedure", "endprocedure", "return", "int", "float", "str", "bool", "real", "string", "integer", "boolean", "true", "false");
    private static final Set<String> BUILTINS = Set.of("print", "input", "random", "open", "newfile", "asc", "chr");
    private static final Set<String> WORD_OPS = Set.of("mod", "div", "and", "or", "not");
    private static final Set<String> MEMBERS = Set.of("length", "substring", "left", "right", "upper", "lower", "close", "readLine", "writeLine", "endOfFile");
    private static final Set<String> ANNOTATIONS = Set.of("byVal", "byRef");
    private static boolean letter(char c) { return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'; }
    private static boolean digit(char c) { return c >= '0' && c <= '9'; }
    private static boolean name(char c) { return letter(c) || digit(c) || c == '_'; }
    private static boolean uniform(String s) { return s.equals(s.toLowerCase(Locale.ROOT)) || s.equals(s.toUpperCase(Locale.ROOT)); }

    public Result scan(String source) { return new Scanner(source).scan(); }

    private static final class Scanner {
        private final String s;
        private final List<Token> tokens = new ArrayList<>();
        private final List<Trivia> trivia = new ArrayList<>();
        private final List<Diagnostic> diagnostics = new ArrayList<>();
        private int i, line = 1, column = 1;
        private boolean error, mod;
        private Span div;
        private State state = State.DEFAULT;
        Scanner(String source) { s = source; }
        private Position pos() { return new Position(i, line, column); }
        private Span span(Position start) { return new Span(start, pos()); }
        private boolean at(char c) { return i < s.length() && s.charAt(i) == c; }
        private void advance() {
            char c = s.charAt(i++);
            if (c == '\r') { if (i < s.length() && s.charAt(i) == '\n') i++; line++; column = 1; }
            else if (c == '\n') { line++; column = 1; }
            else column++;
        }
        private void token(Kind k, Position p, String value) { tokens.add(new Token(k, s.substring(p.offset(), i), value, span(p))); }
        private void diagnostic(Severity severity, String category, String message, Span where) { diagnostics.add(new Diagnostic(severity, category, message, where)); if (severity == Severity.ERROR) error = true; }
        private Result scan() {
            if (at('\ufeff')) advance();
            while (i < s.length() && !error) {
                Position p = pos(); char c = s.charAt(i);
                if (c == ' ' || c == '\t') { advance(); continue; }
                if (c == '\r' || c == '\n') { advance(); token(Kind.NEWLINE, p, null); continue; }
                if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') { comment(p); continue; }
                if (c == '"' || c == '\'') { literal(p, c); continue; }
                if (digit(c) || c == '.' && i + 1 < s.length() && digit(s.charAt(i + 1))) { numberOrName(p); continue; }
                if (letter(c) || c == '_') { word(p); continue; }
                if ("=<>!".indexOf(c) >= 0) {
                    advance(); if (at('=')) advance();
                    if (i < s.length() && "=<>!".indexOf(s.charAt(i)) >= 0) {
                        while (i < s.length() && "=<>!".indexOf(s.charAt(i)) >= 0) advance();
                        diagnostic(Severity.ERROR, "unknown-symbol", "Unsupported operator sequence", span(p)); continue;
                    }
                    String op = s.substring(p.offset(), i);
                    if (op.equals("!")) diagnostic(Severity.ERROR, "unknown-symbol", "Unsupported operator", span(p)); else token(switch (op) { case "=" -> Kind.OP_ASSIGN; case "==" -> Kind.OP_EQ; case "!=" -> Kind.OP_NE; case "<" -> Kind.OP_LT; case "<=" -> Kind.OP_LE; case ">" -> Kind.OP_GT; case ">=" -> Kind.OP_GE; default -> throw new IllegalStateException(op); }, p, null);
                    continue;
                }
                if ("+-*/^".indexOf(c) >= 0) { advance(); token(switch (c) { case '+' -> Kind.OP_PLUS; case '-' -> Kind.OP_MINUS; case '*' -> Kind.OP_MULTIPLY; case '/' -> Kind.OP_DIVIDE; case '^' -> Kind.OP_POWER; default -> throw new IllegalStateException(); }, p, null); continue; }
                Kind punctuation = switch (c) {
                    case '(' -> Kind.LPAREN;
                    case ')' -> Kind.RPAREN;
                    case '[' -> Kind.LBRACKET;
                    case ']' -> Kind.RBRACKET;
                    case '{' -> Kind.LBRACE;
                    case '}' -> Kind.RBRACE;
                    case ',' -> Kind.COMMA;
                    case ':' -> Kind.COLON;
                    case '.' -> Kind.DOT;
                    default -> null;
                };
                if (punctuation != null) { advance(); token(punctuation, p, null); continue; }
                advance(); diagnostic(Severity.ERROR, "unknown-symbol", "Unknown symbol", span(p));
            }
            if (mod && div != null) diagnostic(Severity.WARNING, "mixed-operator-case", "Lowercase mod and uppercase DIV occur together", div);
            return new Result(List.copyOf(tokens), List.copyOf(trivia), List.copyOf(diagnostics));
        }
        private void comment(Position p) {
            state = State.COMMENT; advance(); advance();
            while (i < s.length() && !at('\n') && !at('\r')) advance();
            trivia.add(new Trivia(s.substring(p.offset(), i), span(p))); state = State.DEFAULT;
        }
        private void literal(Position p, char quote) {
            state = quote == '"' ? State.STRING : State.CHARACTER; advance(); StringBuilder value = new StringBuilder(); boolean closed = false;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == quote) { advance(); closed = true; break; }
                if (c == '\\') {
                    advance(); if (i == s.length()) break; char e = s.charAt(i); advance();
                    switch (e) { case 'n' -> value.append('\n'); case 't' -> value.append('\t'); case 'r' -> value.append('\r'); case '\\', '\'', '"' -> value.append(e); default -> { diagnostic(Severity.ERROR, "invalid-escape", "Unsupported escape", span(p)); state = State.DEFAULT; return; } }
                } else {
                    int start = i;
                    advance();
                    value.append(s, start, i);
                }
            }
            if (!closed) diagnostic(Severity.ERROR, "unterminated-string", "Unterminated literal", span(p));
            else if (quote == '\'' && value.codePointCount(0, value.length()) != 1) diagnostic(Severity.ERROR, "invalid-character", "Character literal must contain one character", span(p));
            else token(quote == '"' ? Kind.STRING : Kind.CHARACTER, p, value.toString());
            state = State.DEFAULT;
        }
        private void word(Position p) {
            while (i < s.length() && name(s.charAt(i))) advance();
            String text = s.substring(p.offset(), i);
            // A documented word operator followed directly by digits forms two tokens.
            for (String op : List.of("MOD", "DIV", "mod", "div")) {
                if (text.startsWith(op) && text.length() > op.length() && text.substring(op.length()).chars().allMatch(Character::isDigit)) {
                    i = p.offset() + op.length(); column = p.column() + op.length(); text = op; break;
                }
            }
            String lower = text.toLowerCase(Locale.ROOT); Kind kind = Kind.IDENTIFIER;
            boolean afterDot = !tokens.isEmpty() && tokens.get(tokens.size()-1).kind() == Kind.DOT;
            if (ANNOTATIONS.contains(text)) kind = Kind.ANNOTATION;
            else if (afterDot && MEMBERS.contains(text)) kind = Kind.MEMBER;
            else if (uniform(text) && WORD_OPS.contains(lower)) kind = Kind.valueOf("OP_" + lower.toUpperCase(Locale.ROOT));
            else if (uniform(text) && BUILTINS.contains(lower)) kind = Kind.valueOf("BUILTIN_" + lower.toUpperCase(Locale.ROOT));
            else if (uniform(text) && KEYWORDS.contains(lower)) kind = Kind.valueOf("KEYWORD_" + lower.toUpperCase(Locale.ROOT));
            token(kind, p, null);
            if (kind == Kind.OP_MOD && text.equals("mod")) mod = true;
            if (kind == Kind.OP_DIV && text.equals("DIV")) div = span(p);
        }
        private void numberOrName(Position p) {
            boolean dot = false;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (digit(c) || c == '_') { advance(); continue; }
                if (c == '.' && !dot) { dot = true; advance(); continue; }
                if (c == '.' && dot && i + 1 < s.length() && digit(s.charAt(i+1))) { while (i < s.length() && (digit(s.charAt(i)) || at('.'))) advance(); diagnostic(Severity.ERROR, "invalid-number", "Malformed number", span(p)); return; }
                break;
            }
            if (i < s.length() && letter(s.charAt(i))) {
                if (s.startsWith("MOD", i) || s.startsWith("DIV", i) || s.startsWith("mod", i) || s.startsWith("div", i)) { /* scan operator next */ }
                else {
                    String prefix = s.substring(p.offset(), i);
                    while (i < s.length() && name(s.charAt(i))) advance();
                    if (prefix.matches("[0-9]+\\.[0-9]+"))
                        diagnostic(Severity.ERROR, "invalid-number", "A decimal followed by letters cannot be a variable name", span(p));
                    else token(Kind.IDENTIFIER, p, null);
                    return;
                }
            }
            String text = s.substring(p.offset(), i), normalized = text.replace("_", "");
            if (text.startsWith(".")) normalized = "0" + normalized;
            if (text.endsWith(".")) normalized += "0";
            while (normalized.length() > 1 && normalized.charAt(0) == '0' && digit(normalized.charAt(1))) normalized = normalized.substring(1);
            token(Kind.NUMBER, p, normalized);
            if (text.startsWith(".") || text.endsWith(".")) diagnostic(Severity.WARNING, "missing-zero", "Omitting a zero is erroneous practice", span(p));
            String integer = text.split("\\.", -1)[0].replace("_", "");
            if (integer.length() > 1 && integer.charAt(0) == '0') diagnostic(Severity.WARNING, "leading-zero", "Leading zeros are unnecessary", span(p));
        }
    }
}
