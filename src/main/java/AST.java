import java.util.List;

/** Source-bearing OCR syntax. Unresolved lines and expressions retain their tokens. */
public final class AST {
    private AST() {}
    public sealed interface Statement permits Line, If, While, For, Call, Print, Assign, Function, Procedure, Return, Invoke, Switch, Break, ArrayDeclaration, DoUntil, IndexAssignment, ExpressionStatement {}
    public record ArrayDeclaration(Lexer.Token keyword, Lexer.Token name, Lexer.Token open, List<Expr> dimensions, Lexer.Token close, Lexer.Token assignment, ArrayLiteral initializer) implements Statement { public ArrayDeclaration { dimensions = List.copyOf(dimensions); } }
    public record IndexAssignment(Index target, Lexer.Token assignment, Expr value) implements Statement {}
    public record ExpressionStatement(CallExpression call) implements Statement {}
    public record DoUntil(Lexer.Token start, List<Statement> body, Lexer.Token until, Expr condition) implements Statement { public DoUntil { body = List.copyOf(body); } }
    public record Case(Lexer.Token keyword, Expr value, List<Statement> body) {
        public Case { body = List.copyOf(body); }
    }
    public record Switch(Lexer.Token start, Expr selector, List<Case> cases, List<Statement> otherwise, Lexer.Token end) implements Statement {
        public Switch { cases = List.copyOf(cases); otherwise = List.copyOf(otherwise); }
    }
    public record Break(Lexer.Token keyword) implements Statement {}
    public record For(Lexer.Token start, Lexer.Token variable, Expr from, Expr to, Expr step, List<Statement> body, Lexer.Token end) implements Statement {
        public For { body = List.copyOf(body); }
    }
    public record Parameter(Lexer.Token name, boolean byReference) {}
    public record Function(Lexer.Token name, List<Parameter> parameters, List<Statement> body) implements Statement {
        public Function { parameters = List.copyOf(parameters); body = List.copyOf(body); }
    }
    public record Procedure(Lexer.Token name, List<Parameter> parameters, List<Statement> body) implements Statement {
        public Procedure { parameters = List.copyOf(parameters); body = List.copyOf(body); }
    }
    public record Return(Lexer.Token keyword, Expr value) implements Statement {}
    public record Invoke(Lexer.Token name, List<Expr> arguments) implements Statement { public Invoke { arguments = List.copyOf(arguments); } }
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
    public record Call(String receiver, Lexer.Token method, List<Lexer.Token> argumentTokens, List<Expr> arguments, Lexer.Span span) implements Statement {
        public Call { argumentTokens = List.copyOf(argumentTokens); arguments = List.copyOf(arguments); }
    }
    public record Print(Lexer.Token start, Expr expression) implements Statement {}
    public record Assign(Lexer.Token name, Expr expression) implements Statement {}
    public sealed interface Expr permits Atom, Binary, Group, Unresolved, FunctionCall, Unary, Index, MemberAccess, CallExpression, ArrayLiteral {}
    public record ArrayLiteral(Lexer.Token open, List<Expr> elements, Lexer.Token close) implements Expr { public ArrayLiteral { elements = List.copyOf(elements); } }
    public record Index(Expr receiver, Lexer.Token open, List<Expr> indices, Lexer.Token close) implements Expr { public Index { indices = List.copyOf(indices); } }
    public record MemberAccess(Expr receiver, Lexer.Token dot, Lexer.Token member) implements Expr {}
    public record CallExpression(Expr callee, Lexer.Token open, List<Expr> arguments, Lexer.Token close) implements Expr { public CallExpression { arguments = List.copyOf(arguments); } }
    public record Unary(Lexer.Token operator, Expr operand) implements Expr {}
    public record FunctionCall(Lexer.Token name, List<Expr> arguments) implements Expr { public FunctionCall { arguments = List.copyOf(arguments); } }
    public record Atom(Lexer.Token token) implements Expr {}
    public record Binary(Expr left, Lexer.Token operator, Expr right) implements Expr {}
    public record Group(Lexer.Token open, Expr expression, Lexer.Token close) implements Expr {}
    public record Unresolved(List<Lexer.Token> tokens) implements Expr {
        public Unresolved { tokens = List.copyOf(tokens); }
    }
}
