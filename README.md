# hexagen-cli

A command-line tool that generates Java projects scaffolded in **Hexagonal Architecture**
(Ports and Adapters) from nothing but a domain name — by asking an LLM to design and write
the code for you.

```
$ export ANTHROPIC_API_KEY=sk-ant-...
$ java -jar hexagen-cli.jar generate Billing
Planning project structure for context 'Billing' (provider: claude)...
Generating 66 file(s) in parallel...
66 file(s) successfully generated at: /home/you/projects/billing
```

## How it works

You give `hexagen-cli` a single word: the name of a business domain/context (e.g. `Billing`,
`Inventory`, `Shipping`). The tool then:

1. **Plans** the project — a single, fast LLM call returns the full file plan: every package,
   entity, port (interface), and adapter that belongs in that context, each with a short
   description of its purpose. No file content is generated yet, so this step is cheap and quick.
2. **Generates** every file's content — one LLM call per planned file, fanned out **in parallel**
   using Java 21 virtual threads (`Executors.newVirtualThreadPerTaskExecutor()` + `CompletableFuture`).
   Each call receives the full file plan as context, so cross-file references (imports, method
   signatures, package names) stay consistent even though files are written independently and
   concurrently.
3. **Writes** the result to disk — every generated file is materialized under your chosen output
   directory, creating any necessary folders along the way.

Splitting generation into a "plan" phase and a fan-out "content" phase — instead of asking the
LLM to write an entire project in one giant response — is what keeps a full run to roughly
1-2 minutes even for 50+ files: each individual request is small, fast, and safely parallelizable.

## Requirements

- Java 21+
- Maven (for building from source)
- An API key for the LLM provider you want to use (see below) — **your own key, from your own
  account**. This tool never ships, bundles, or depends on any API key belonging to its
  developers; every request is billed to and authenticated with the key you provide.

## Installation

Build the executable JAR from source:

```
mvn package
```

This produces `target/hexagen-cli.jar`.

For an ahead-of-time compiled native binary (faster startup, no JVM required), use the `native`
Maven profile with a [GraalVM](https://www.graalvm.org/) installation:

```
mvn -Pnative package
```

## Usage

```
java -jar target/hexagen-cli.jar generate <context> [-o <directory>] [-p <provider>]
```

| Option | Description |
|---|---|
| `<context>` | Required. The domain/context name to generate (e.g. `Billing`). |
| `-o, --output <directory>` | Output directory the project is written into. Defaults to the current directory. |
| `-p, --provider <provider>` | LLM provider to use: `claude` (default) or `openai`. |
| `-h, --help` | Show usage help. |
| `-V, --version` | Show the CLI version. |

## Configuring your LLM provider

`hexagen-cli` never stores or hardcodes an API key. Export the environment variable that matches
the provider you want to use, exactly as you would for any other tool that talks to that API:

```
# Claude (default provider)
export ANTHROPIC_API_KEY=sk-ant-...

# OpenAI
export OPENAI_API_KEY=sk-...
```

Then run the CLI with `-p claude` (or omit it, since Claude is the default) or `-p openai`.
Because provider selection is a simple CLI flag reading a plain environment variable, adding
support for another LLM later only requires a new `LlmProvider` implementation — no changes to
the rest of the codebase.

## Architecture of this CLI

The tool follows the same Hexagonal Architecture style it generates for you:

```
com.springify.cli
├── HexagenCliApplication      entry point
├── command/                   PicoCLI command (I/O boundary: args in, exit code out)
├── domain/                    ports and models — no I/O, no framework dependencies
│   ├── LlmProvider            output port: plan + generate file content
│   ├── FileSpec                a planned file (path + description)
│   ├── GeneratedFile          a file ready to be written (path + content)
│   └── LlmException           domain error for LLM failures
└── infrastructure/
    ├── llm/                   LlmProvider adapters: AnthropicLlmProvider, OpenAiLlmProvider
    └── workspace/             WorkspaceManager — the only component that touches the filesystem
```

- **`LlmProvider`** is the port that decouples the CLI from any specific LLM vendor. Swapping or
  adding a provider means writing a new adapter class, not touching the command or the domain.
- **`WorkspaceManager`** isolates all file-system writes, including path-traversal protection
  against any `../` sequences an LLM response might (accidentally or not) include.
- Errors are surfaced as distinct, human-readable console messages with dedicated exit codes for:
  missing/invalid API key, LLM call failure, empty file plan, and file-system write failure.

## Tech stack

- Java 21 (records, text blocks, virtual threads)
- [PicoCLI](https://picocli.info/) for argument parsing
- Native SDKs for each LLM provider: [`anthropic-java`](https://github.com/anthropics/anthropic-sdk-java)
  and `java.net.http.HttpClient` (JDK built-in) for the OpenAI Chat Completions API
- [Jackson](https://github.com/FasterXML/jackson) for JSON parsing
- Maven, with an optional GraalVM native-image profile
# springify
