import java.io.PrintStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;
import java.util.function.Supplier;

/** Walks the supported OCR AST; unresolved syntax is reported rather than guessed. */
public final class Interpreter {
    private record CharacterValue(String value) {
        @Override public String toString() { return value; }
    }
    public static final class RuntimeError extends RuntimeException {
        public RuntimeError(Lexer.Token token, String message) {
            super((token == null ? "" : "Line " + token.span().start().line() + ", column " + token.span().start().column() + ": ") + message);
        }
    }
    private final PrintStream output;
    private Environment environment;
    private final PrintStream warnings;
    private final Map<String, AST.Statement> subroutines = new HashMap<>();
    private static final class Returned extends RuntimeException {
        final Object value;
        Returned(Object value) { this.value = value; }
    }
    private static final class SwitchBreak extends RuntimeException {}
    private int switchDepth;
    private ResultTrace trace;
    public Interpreter() { this(System.out); }
    public Interpreter(PrintStream output) { this(output, System.err); }
    public Interpreter(PrintStream output, PrintStream warnings) {
        this.output = Objects.requireNonNull(output);
        this.warnings = Objects.requireNonNull(warnings);
        this.environment = new Environment(warnings);
    }
    public void interpret(List<AST.Statement> program) { for (AST.Statement statement : program) execute(statement); }
    public void interpretWithResults(List<AST.Statement> program) {
        interpretWithResults(program, supportsColor(System.console() != null, output == System.out, System.getenv("NO_COLOR"), System.getenv("TERM")));
    }
    void interpretWithResults(List<AST.Statement> program, boolean color) {
        if (trace != null) throw new IllegalStateException("An execution trace is already active");
        ResultTrace current = new ResultTrace();
        trace = current;
        try { interpret(program); }
        finally { trace = null; current.print(output, color); }
    }
    static boolean supportsColor(boolean terminal, boolean systemOutput, String noColor, String term) {
        return terminal && systemOutput && noColor == null && !"dumb".equalsIgnoreCase(term);
    }
    public void execute(AST.Statement statement) {
        if (trace == null) { executeStatement(statement); return; }
        trace.record(statementLabel(statement), ResultTrace.Category.STATEMENT, false, () -> { executeStatement(statement); return null; });
    }
    private void executeStatement(AST.Statement statement) {
        switch (statement) {
            case AST.Assign assign -> environment.assign(assign.name(), evaluate(assign.expression()));
            case AST.Print print -> output.println(evaluate(print.expression()));
            case AST.If selection -> {
                boolean matched = false;
                for (AST.Branch branch : selection.branches()) {
                    if (condition(branch.condition(), selection.start())) { interpret(branch.body()); matched = true; break; }
                }
                if (!matched) interpret(selection.otherwise());
            }
            case AST.Switch selection -> {
                Object selector = evaluate(selection.selector());
                List<AST.Statement> selected = selection.otherwise();
                for (AST.Case branch : selection.cases()) {
                    Object value = evaluate(branch.value());
                    boolean equal = selector instanceof BigDecimal a && value instanceof BigDecimal b
                            ? a.compareTo(b) == 0 : Objects.equals(selector, value);
                    if (equal) { selected = branch.body(); break; }
                }
                switchDepth++;
                try { interpret(selected); } catch (SwitchBreak ignored) { }
                finally { switchDepth--; }
            }
            case AST.Break stop -> {
                if (switchDepth == 0) throw new RuntimeError(stop.keyword(), "break outside switch");
                throw new SwitchBreak();
            }
            case AST.While loop -> { while (condition(loop.condition(), loop.start())) interpret(loop.body()); }
            case AST.For loop -> executeFor(loop);
            case AST.ArrayDeclaration array -> throw new RuntimeError(array.keyword(), "Array declarations are not implemented");
            case AST.IndexAssignment assignment -> throw new RuntimeError(assignment.assignment(), "Array assignment is not implemented");
            case AST.ExpressionStatement statementCall -> throw new RuntimeError(statementCall.call().open(), "Member calls are not implemented");
            case AST.DoUntil loop -> throw new RuntimeError(loop.start(), "DO UNTIL is not implemented");
            case AST.Line line -> throw new RuntimeError(line.tokens().isEmpty() ? null : line.tokens().getFirst(), "Unsupported statement");
            case AST.Call call -> throw new RuntimeError(call.method(), "File calls are not implemented");
            case AST.Function function -> subroutines.put(function.name().key(), function);
            case AST.Procedure procedure -> subroutines.put(procedure.name().key(), procedure);
            case AST.Return ret -> throw new Returned(evaluate(ret.value()));
            case AST.Invoke invoke -> call(invoke.name(), invoke.arguments(), false);
        }
    }
    private void executeFor(AST.For loop) {
        Object start = evaluate(loop.from());
        Object end = evaluate(loop.to());
        Object step = loop.step() == null ? BigDecimal.ONE : evaluate(loop.step());
        if (!(start instanceof BigDecimal from) || !(end instanceof BigDecimal to) || !(step instanceof BigDecimal increment))
            throw new RuntimeError(loop.start(), "FOR bounds and step must be numeric");
        if (increment.stripTrailingZeros().scale() > 0 || increment.signum() == 0)
            throw new RuntimeError(loop.start(), "FOR step must be a non-zero integer");
        environment.assign(loop.variable(), from);
        if (increment.signum() > 0 && from.compareTo(to) > 0 || increment.signum() < 0 && from.compareTo(to) < 0) return;
        BigDecimal current = from;
        while (true) {
            interpret(loop.body());
            BigDecimal next = current.add(increment);
            if (increment.signum() > 0 && next.compareTo(to) > 0 || increment.signum() < 0 && next.compareTo(to) < 0) break;
            current = next;
            environment.assign(loop.variable(), current);
        }
    }
    private boolean condition(AST.Expr expression, Lexer.Token token) {
        Object value = evaluate(expression);
        if (value instanceof Boolean condition) return condition;
        throw new RuntimeError(token, "Expected a Boolean condition");
    }
    public Object evaluate(AST.Expr expression) {
        if (trace == null) return evaluateExpression(expression);
        return trace.record(expressionLabel(expression), expression instanceof AST.Atom atom && atom.token().kind() != Lexer.Kind.IDENTIFIER
                ? ResultTrace.Category.LITERAL : ResultTrace.Category.EXPRESSION, true, () -> evaluateExpression(expression));
    }
    private Object evaluateExpression(AST.Expr expression) {
        return switch (expression) {
            case AST.Atom atom -> atom(atom.token());
            case AST.Group group -> evaluate(group.expression());
            case AST.Binary binary -> binary(evaluate(binary.left()), binary.operator(), evaluate(binary.right()));
            case AST.Unresolved unresolved -> throw new RuntimeError(unresolved.tokens().isEmpty() ? null : unresolved.tokens().getFirst(), "Unsupported or ambiguous expression");
            case AST.FunctionCall call -> call(call.name(), call.arguments(), true);
            case AST.Index index -> throw new RuntimeError(index.open(), "Array indexing is not implemented");
            case AST.ArrayLiteral array -> throw new RuntimeError(array.open(), "Array literals are not implemented");
            case AST.MemberAccess member -> throw new RuntimeError(member.member(), "Member access is not implemented");
            case AST.CallExpression call -> throw new RuntimeError(call.open(), "Built-in or member calls are not implemented");
            case AST.Unary unary -> {
                Object value = evaluate(unary.operand());
                if (unary.operator().kind() == Lexer.Kind.OP_NOT) {
                    yield !booleanOperand(value, unary.operator());
                }
                if (!(value instanceof BigDecimal number)) throw new RuntimeError(unary.operator(), "Expected numeric operand");
                yield unary.operator().kind() == Lexer.Kind.OP_MINUS ? number.negate() : number;
            }
        };
    }
    private Object call(Lexer.Token name, List<AST.Expr> arguments, boolean expression) {
        AST.Statement declaration = subroutines.get(name.key());
        boolean function = declaration instanceof AST.Function;
        if (!(function || declaration instanceof AST.Procedure)) throw new RuntimeError(name, "Undefined subroutine");
        if (expression != function) throw new RuntimeError(name, expression ? "Procedure does not return a value" : "Function requires an expression context");
        List<AST.Parameter> parameters = function ? ((AST.Function) declaration).parameters() : ((AST.Procedure) declaration).parameters();
        List<AST.Statement> body = function ? ((AST.Function) declaration).body() : ((AST.Procedure) declaration).body();
        if (parameters.size() != arguments.size()) throw new RuntimeError(name, "Incorrect number of arguments");
        Environment caller = environment;
        Environment local = new Environment(warnings);
        for (int i = 0; i < parameters.size(); i++) {
            AST.Parameter parameter = parameters.get(i); AST.Expr argument = arguments.get(i);
            if (parameter.byReference()) {
                if (!(argument instanceof AST.Atom atom) || atom.token().kind() != Lexer.Kind.IDENTIFIER)
                    throw new RuntimeError(name, "byRef requires a variable");
                local.bindReference(parameter.name(), caller, atom.token());
            } else local.assign(parameter.name(), evaluate(argument));
        }
        environment = local;
        try {
            interpret(body);
            if (function) throw new RuntimeError(name, "Function ended without return");
            return null;
        } catch (Returned returned) {
            if (!function) throw new RuntimeError(name, "Procedure cannot return a value");
            return returned.value;
        } finally { environment = caller; }
    }
    private Object atom(Lexer.Token token) {
        return switch (token.kind()) {
            case IDENTIFIER -> environment.get(token);
            case NUMBER -> normalize(new BigDecimal(token.value()));
            case STRING -> token.value();
            case CHARACTER -> new CharacterValue(token.value());
            case KEYWORD_TRUE -> true;
            case KEYWORD_FALSE -> false;
            default -> throw new RuntimeError(token, "Unsupported expression");
        };
    }
    private Object binary(Object left, Lexer.Token op, Object right) {
        if (op.kind() == Lexer.Kind.OP_AND || op.kind() == Lexer.Kind.OP_OR || op.kind() == Lexer.Kind.OP_XOR) {
            boolean a = booleanOperand(left, op), b = booleanOperand(right, op);
            return switch (op.kind()) {
                case OP_AND -> a && b;
                case OP_OR -> a || b;
                case OP_XOR -> a ^ b;
                default -> throw new IllegalStateException();
            };
        }
        if (op.kind() == Lexer.Kind.OP_PLUS && (left instanceof String || right instanceof String)) {
            return textForConcat(left, op) + textForConcat(right, op);
        }
        if (op.kind() == Lexer.Kind.OP_EQ || op.kind() == Lexer.Kind.OP_NE) {
            boolean equal = left instanceof BigDecimal a && right instanceof BigDecimal b ? a.compareTo(b) == 0 : Objects.equals(left, right);
            return op.kind() == Lexer.Kind.OP_EQ ? equal : !equal;
        }
        if (!(left instanceof BigDecimal a) || !(right instanceof BigDecimal b)) throw new RuntimeError(op, "Expected numeric operands");
        try {
            return switch (op.kind()) {
                case OP_PLUS -> normalize(a.add(b));
                case OP_MINUS -> normalize(a.subtract(b));
                case OP_MULTIPLY -> normalize(a.multiply(b));
                case OP_MOD -> {
                    if (b.signum() == 0) throw new RuntimeError(op, "Division by zero");
                    yield normalize(a.remainder(b));
                }
                case OP_DIV -> {
                    if (b.signum() == 0) throw new RuntimeError(op, "Division by zero");
                    yield normalize(a.divideToIntegralValue(b));
                }
                case OP_DIVIDE -> {
                    if (b.signum() == 0) throw new RuntimeError(op, "Division by zero");
                    yield normalize(a.divide(b, 6, RoundingMode.HALF_UP));
                }
                case OP_LT -> a.compareTo(b) < 0;
                case OP_LE -> a.compareTo(b) <= 0;
                case OP_GT -> a.compareTo(b) > 0;
                case OP_GE -> a.compareTo(b) >= 0;
                default -> throw new RuntimeError(op, "Unsupported operator");
            };
        } catch (ArithmeticException error) { throw new RuntimeError(op, "Unsupported numeric result"); }
    }
    private boolean booleanOperand(Object value, Lexer.Token op) {
        if (value instanceof Boolean bool) return bool;
        throw new RuntimeError(op, "Expected Boolean operand");
    }
    private String textForConcat(Object value, Lexer.Token op) {
        if (value instanceof BigDecimal number) return normalize(number).toPlainString();
        if (value instanceof String || value instanceof Boolean || value instanceof CharacterValue) return value.toString();
        throw new RuntimeError(op, "Unsupported operand for string concatenation");
    }
    private static BigDecimal normalize(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
    private static String statementLabel(AST.Statement statement) {
        return switch (statement) {
            case AST.Assign assign -> "assign " + assign.name().text();
            case AST.Print ignored -> "print";
            case AST.If ignored -> "if";
            case AST.Switch ignored -> "switch";
            case AST.Break ignored -> "break";
            case AST.While ignored -> "while";
            case AST.For ignored -> "for";
            case AST.ArrayDeclaration ignored -> "array";
            case AST.IndexAssignment ignored -> "array assignment";
            case AST.ExpressionStatement ignored -> "call";
            case AST.DoUntil ignored -> "do until";
            case AST.Line ignored -> "unsupported";
            case AST.Call call -> call.method().text();
            case AST.Function ignored -> "function";
            case AST.Procedure ignored -> "procedure";
            case AST.Return ignored -> "return";
            case AST.Invoke invoke -> "call " + invoke.name().text();
        };
    }
    private static String expressionLabel(AST.Expr expression) {
        return switch (expression) {
            case AST.Atom atom -> atom.token().kind() == Lexer.Kind.IDENTIFIER ? "variable " + atom.token().text() : "literal";
            case AST.Binary binary -> "binary " + binary.operator().text();
            case AST.Group ignored -> "group";
            case AST.Unresolved ignored -> "unresolved expression";
            case AST.FunctionCall call -> "call " + call.name().text();
            case AST.Index ignored -> "index";
            case AST.ArrayLiteral ignored -> "array literal";
            case AST.MemberAccess member -> "member " + member.member().text();
            case AST.CallExpression ignored -> "call";
            case AST.Unary unary -> "unary " + unary.operator().text();
        };
    }
    private static final class ResultTrace {
        private enum Category { STATEMENT, EXPRESSION, LITERAL }
        private static final String RESET = "\u001b[0m";
        private static final String RED = "\u001b[31m";
        private static final String GREEN = "\u001b[32m";
        private static final String BLUE = "\u001b[34m";
        private static final String CYAN = "\u001b[36m";
        private static final class Node {
            private final String label;
            private final Category category;
            private final List<Node> children = new ArrayList<>();
            private String suffix = "";
            private boolean failed;
            private Node(String label, Category category) { this.label = label; this.category = category; }
        }
        private record Pending(Node node, String prefix, boolean last) {}
        private final List<Node> roots = new ArrayList<>();
        private final Deque<Node> stack = new ArrayDeque<>();

        private <T> T record(String label, Category category, boolean hasValue, Supplier<T> action) {
            Node node = new Node(label, category);
            if (stack.isEmpty()) roots.add(node); else stack.peek().children.add(node);
            stack.push(node);
            try {
                T value = action.get();
                if (hasValue) node.suffix = " = " + format(value);
                return value;
            } catch (Returned returned) {
                throw returned;
            } catch (SwitchBreak stop) {
                throw stop;
            } catch (RuntimeException error) {
                node.failed = true;
                node.suffix = node.children.stream().anyMatch(child -> child.failed)
                        ? " [aborted]" : " [error: " + escape(String.valueOf(error.getMessage())) + "]";
                throw error;
            } finally { stack.pop(); }
        }
        private void print(PrintStream out, boolean color) {
            out.println("Result tree:");
            Deque<Pending> pending = new ArrayDeque<>();
            pushChildren(pending, roots, "");
            while (!pending.isEmpty()) {
                Pending item = pending.pop();
                Node node = item.node();
                out.print(item.prefix() + (item.last() ? "\\-- " : "|-- "));
                if (color) out.print(node.failed ? RED : switch (node.category) {
                    case STATEMENT -> CYAN;
                    case EXPRESSION -> BLUE;
                    case LITERAL -> GREEN;
                });
                out.print(node.label + node.suffix);
                if (color) out.print(RESET);
                out.println();
                pushChildren(pending, node.children, item.prefix() + (item.last() ? "    " : "|   "));
            }
        }
        private void pushChildren(Deque<Pending> pending, List<Node> children, String prefix) {
            for (int i = children.size() - 1; i >= 0; i--)
                pending.push(new Pending(children.get(i), prefix, i == children.size() - 1));
        }
        private static String format(Object value) {
            if (value instanceof String text) return "\"" + escape(text) + "\"";
            return String.valueOf(value);
        }
        private static String escape(String text) {
            return text.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        }
    }
}
