import java.util.List;

/** Source-bearing OCR syntax. Unresolved lines and expressions retain their tokens. */
public final class AST {
    private AST() {}
    public sealed interface Statement permits Line, If, While, Call, Print, Assign {}
    public record Line(List<Lexer.Token> tokens) implements Statement {
        public Line { tokens = List.copyOf(tokens); }
    }
    public record Branch(Expr condition, List<Statement> body) {
        public Branch { body = List.copyOf(body); }
    }
    public record If(Lexer.Token start, List<Branch> branches, List<Statement> otherwise, Lexer.Token end) implements Statement {
        public If { branches = List.copyOf(branches); otherwise = List.copyOf(otherwise); }
    }
    public record While(Lexer.Token start, Expr condition, List<Statement> body, Lexer.Token end) implements Statement {
        public While { body = List.copyOf(body); }
    }
    public record Call(String receiver, Lexer.Token method, List<Lexer.Token> argumentTokens, Lexer.Span span) implements Statement {
        public Call { argumentTokens = List.copyOf(argumentTokens); }
    }
    public record Print(Lexer.Token start, Expr expression) implements Statement {}
    public record Assign(Lexer.Token name, Expr expression) implements Statement {}
    public sealed interface Expr permits Atom, Binary, Group, Unresolved {}
    public record Atom(Lexer.Token token) implements Expr {}
    public record Binary(Expr left, Lexer.Token operator, Expr right) implements Expr {}
    public record Group(Lexer.Token open, Expr expression, Lexer.Token close) implements Expr {}
    public record Unresolved(List<Lexer.Token> tokens) implements Expr {
        public Unresolved { tokens = List.copyOf(tokens); }
    }
}
