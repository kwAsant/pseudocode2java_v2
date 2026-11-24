# pseudocode2java_v2

A work-in-progress project for processing OCR J277 pseudocode, with the longer-term aim of generating Java. It does not yet generate Java.

## Progress

- The lexer recognises tokens and reports source locations, comments and diagnostics.
- A Gradle build and tests are in place.
- The AST and parser support some statements and expressions; unsupported or unresolved syntax is retained as tokens. Parsing and semantic analysis are incomplete.

See the [lexical rules](docs/lexical-rules.md) and [parser and semantic findings](docs/parser-semantic-findings.md) for details and open questions.

## Build and test

Install JDK 21, then run:

```sh
./gradlew build
./gradlew test
```

On Windows, use `gradlew.bat`. The wrapper may need network access to download Gradle and dependencies. There is not yet a committed command-line entry point for running a program.
