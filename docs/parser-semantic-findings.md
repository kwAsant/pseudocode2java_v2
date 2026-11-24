# Parser and semantic analysis: reference findings

The reference is [OCR J277 specification version 3.1 (May 2026), section 3c](https://www.ocr.org.uk/images/558027-specification-gcse-computer-science-j277.pdf), together with `lexical-rules.md`. OCR presents examples, not a complete formal grammar or static semantics. The following is an inventory, not an implementation of unspecified rules.

## Supported by the reference

- Assignment, constants and globals; input, output and casts.
- `if`/`elseif`/`else`, `switch`/`case`/`default`, `for`/`next`, `while`/`endwhile`, and `do`/`until`.
- Arrays are zero-indexed and contain a single data type; the examples include one- and two-dimensional arrays and initialized one-dimensional arrays.
- Functions return values; procedures, string operations, file operations and random numbers are illustrated.
- The `for` examples use inclusive endpoints; the specification describes a positive or negative integer step.

## Unresolved boundaries

- The lexer classifies `newFile` as an identifier, including after `files.`. In call context, `newFile(...)` and `files.newFile(...)` both denote the `newFile` method on `files`; the first has an implicit receiver. The explicit receiver is a project extension; OCR shows the bare form. The method retains exact spelling and the receiver follows identifier case rules. Recognition does not alter the lexer or treat a bare `newFile` identifier as a call.
- The project leaves case-insensitive identifiers versus keyword recognition unresolved. For example, `fOR` is an identifier and `for` is a keyword. Type declaration syntax is unspecified. The identifier and numeric boundaries, escapes, recovery and operator boundaries also have documented open questions.
- OCR's examples do not supply complete rules for operator precedence, branch definite assignment, name resolution, implicit conversions, or type inference. An implementation must not claim that its choices for these are OCR requirements.

## Acceptance criteria for a future implementation

- Consume the existing lexer result and preserve source spelling and spans; propagate lexical errors without treating a truncated token stream as a complete program.
- Parse documented examples, including nested selection and iteration, into a source-located syntax representation. Do not classify unsupported or unresolved constructs as OCR violations.
- Check rules that can be established without guessing, including homogeneous array element types when those types are known. Unknown types must not yield speculative mismatch errors.
- Report supported syntax and semantic errors with a category, specific message and location. Tests should include nested blocks, array types, identifier casing, scope, coercion, and incomplete input, with unresolved behavior explicitly separated from established requirements.
- Keep parsing and semantic analysis separate from Java generation; preserve all existing lexer tests.

These criteria do not establish a complete parser grammar or authorize answers to the unresolved questions. They cannot establish that every OCR semantic error is detected until those boundaries are settled.

## Adopted analysis policies

- Retain the existing lexer behavior for escapes, multiline strings, newlines, BOM, end-of-file comments and `name\thing`.
- Explicit type-declaration syntax is deferred; report declaration-like constructs as unsupported, not as OCR violations.
- Track names and assignments only where established; do not report uncertain scope, declaration or definite-assignment cases as errors.
- Infer types from clear evidence and otherwise keep them unknown. Check array homogeneity where the relevant element types are known; do not assume implicit numeric conversions or promote mixed numeric elements.
- Preserve operator sequences when grouping is not established. Explicit parentheses establish grouping; analysis that depends on unresolved grouping is incomplete.
- Preserve the lexer's stop on its first error. Parser recovery may proceed at defined statement or block boundaries, but a truncated lexical stream is not a complete program.
- Diagnostics have stable categories and locations; wording may improve. Preserve lexer diagnostics and the `mod`/`DIV` warning.

`Parser` recognizes a complete `newFile(...)` or `files.newFile(...)` call as an `AST.Call`, retaining its argument tokens and source span. It rejects lexically incomplete input. Arguments are not yet parsed or validated and execution of file calls is not implemented.
