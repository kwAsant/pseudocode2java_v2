# Repository guidance

The lexical language reference is [docs/lexical-rules.md](docs/lexical-rules.md). Use OCR J277 specification version 3.1, section 3c, with the documented project-specific rules and unresolved questions.

Preserve source spelling and locations for tokens and diagnostics. Identifiers compare case-insensitively; mixed-case keyword spellings, including `fOR` and `DiV`, are identifiers. Keyword case, built-in member case, and the `mod`/`DIV` warning are detailed in the reference.

Do not infer undefined identifier, numeric, escape, recovery, or type-declaration grammar. Resolve documented contradictions before implementing affected behavior. No parser or semantic implementation is authorized by this guidance.

The unified OCR entry points are `Lexer`, `Parser`, `AST`, `Pipeline`, `Interpreter`, `Main`, and `LexerScratch` in `src/main/java`. `Pipeline` creates a fresh interpreter for each execution. Unsupported syntax and unresolved expressions must not be given invented semantics; see `docs/parser-semantic-findings.md`.
