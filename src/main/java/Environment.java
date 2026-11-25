import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Runtime bindings keyed by the OCR lexer's case-insensitive identifier key. */
public final class Environment {
    private static final class Binding {
        private final String original;
        private String variant;
        private boolean warned;
        private Object value;
        private Binding(String original, Object value) { this.original = original; this.value = value; }
    }
    private final Map<String, Binding> values = new HashMap<>();
    private final PrintStream warnings;
    public Environment() { this(System.err); }
    public Environment(PrintStream warnings) { this.warnings = Objects.requireNonNull(warnings); }
    public void assign(Lexer.Token name, Object value) {
        Binding binding = values.get(name.key());
        if (binding == null) values.put(name.key(), new Binding(name.text(), value));
        else { observe(binding, name); binding.value = value; }
    }
    public Object get(Lexer.Token name) {
        Binding binding = values.get(name.key());
        if (binding == null) throw new Interpreter.RuntimeError(name, "Undefined variable '" + name.text() + "'");
        observe(binding, name);
        return binding.value;
    }
    void bindReference(Lexer.Token parameter, Environment caller, Lexer.Token argument) {
        Binding binding = caller.values.get(argument.key());
        if (binding == null) throw new Interpreter.RuntimeError(argument, "Undefined variable '" + argument.text() + "'");
        values.put(parameter.key(), binding);
    }
    private void observe(Binding binding, Lexer.Token name) {
        if (binding.warned || name.text().equals(binding.original)) return;
        if (binding.variant == null) binding.variant = name.text();
        else if (!binding.variant.equals(name.text())) {
            binding.warned = true;
            warnings.println("Warning at line " + name.span().start().line() + ", column " + name.span().start().column()
                    + ": variable '" + binding.original + "' also used as '" + binding.variant + "' and '" + name.text()
                    + "'; use consistent spelling for the same variable.");
        }
    }
}
