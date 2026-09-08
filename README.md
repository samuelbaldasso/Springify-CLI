# Springify

A command-line tool that generates Java projects scaffolded in Hexagonal Architecture (Ports and Adapters) from nothing but a domain name — by asking an LLM to design and write the code for you.

```
$ export ANTHROPIC_API_KEY=sk-ant-...
$ java -jar hexagen-cli.jar generate Billing

Planning project structure for context 'Billing' (provider: claude)...
Generating 66 file(s) in parallel...
66 file(s) successfully generated at: /home/you/projects/billing
```

## How it works

You give hexagen-cli a single word: the name of a business domain/context (e.g. `Billing`, `Inventory`, `Shipping`). The tool then:

1. **Plans the project** — a single, fast LLM call returns the full file plan: every package, entity, port (interface), and adapter that belongs in that context, each with a short description of its purpose. No file content is generated yet, so this step is cheap and quick.
2. **Generates every file's content** — one LLM call per planned file, fanned out in parallel using Java 21 virtual threads (`Executors.newVirtualThreadPerTaskExecutor()` + `CompletableFuture`). Each call receives the full file plan as context, so cross-file references (imports, method signatures, package names) stay consistent even though files are written independently and concurrently.
3. **Writes the result to disk** — every generated file is materialized under your chosen output directory, creating any necessary folders along the way.

Splitting generation into a "plan" phase and a fan-out "content" phase — instead of asking the LLM to write an entire project in one giant response — is what keeps a full run to roughly 1–2 minutes even for 50+ files (measured, not estimated): each individual request is small, fast, and safely parallelizable.

## Known Limitations

- **No post-generation validation.** hexagen-cli does not compile, lint, or test the generated project after writing it to disk. It's entirely possible for the LLM to produce code that doesn't build — you're expected to open the project and build/review it yourself before relying on it. This is by design (keeping the tool simple and fast), not an oversight — see [ADR-004](#adr-004-no-post-generation-validation).
- **Partial failures don't roll back the whole run.** If an individual file's generation call fails (rate limit, transient network error, provider error), that file is skipped and reported — every other file that succeeded is still written to disk. A run can end with, say, 64 of 66 files written and 2 reported as failed; you re-run or patch just those, rather than losing the other 64. See [ADR-005](#adr-005-partial-failure-handling--abort-only-the-failed-file-keep-successful-ones).
- **Output quality is bounded by the underlying LLM.** There's no correctness guarantee beyond what the model itself produces — the file plan and each file's content are only as good as a single LLM call, with no self-review or second-pass critique step.
- **Only two providers today** (Claude, OpenAI). Adding another means writing a new `LlmProvider` adapter — straightforward given the port/adapter design, but not available out of the box yet.

## Requirements

- Java 21+
- Maven (for building from source)
- An API key for the LLM provider you want to use (see below) — **your own key, from your own account**. This tool never ships, bundles, or depends on any API key belonging to its developers; every request is billed to and authenticated with the key you provide.

## Installation

Build the executable JAR from source:

```
mvn package
```

This produces `target/hexagen-cli.jar`.

For an ahead-of-time compiled native binary (faster startup, no JVM required), use the `native` Maven profile with a GraalVM installation:

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

hexagen-cli never stores or hardcodes an API key. Export the environment variable that matches the provider you want to use, exactly as you would for any other tool that talks to that API:

```
# Claude (default provider)
export ANTHROPIC_API_KEY=sk-ant-...

# OpenAI
export OPENAI_API_KEY=sk-...
```

Then run the CLI with `-p claude` (or omit it, since Claude is the default) or `-p openai`. Because provider selection is a simple CLI flag reading a plain environment variable, adding support for another LLM later only requires a new `LlmProvider` implementation — no changes to the rest of the codebase.

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

- `LlmProvider` is the port that decouples the CLI from any specific LLM vendor. Swapping or adding a provider means writing a new adapter class, not touching the command or the domain.
- `WorkspaceManager` isolates all file-system writes, including path-traversal protection against any `../` sequences an LLM response might (accidentally or not) include.
- Errors are surfaced as distinct, human-readable console messages with dedicated exit codes for: missing/invalid API key, LLM call failure, empty file plan, and file-system write failure.

## Tech stack

- Java 21 (records, text blocks, virtual threads)
- PicoCLI for argument parsing
- Native SDKs for each LLM provider: `anthropic-java` and `java.net.http.HttpClient` (JDK built-in) for the OpenAI Chat Completions API
- Jackson for JSON parsing
- Maven, with an optional GraalVM native-image profile

---

## Architectural Decisions & Tradeoffs

### ADR-001: Two-Phase Generation (Plan, then Parallel Content Fan-Out)

**Status:** Accepted

**Context:** Generating a full hexagonal-architecture Java project in a single LLM response would require the model to produce many files' worth of content in one call — slow, a single point of failure, and increasingly likely to hit output-length/context limits as the project grows.

**Decision:** Split generation into two phases: (1) one fast call that returns only the file plan — paths and short descriptions, no content; (2) one call per planned file, fanned out in parallel, each given the full plan as shared context so cross-file references (imports, method signatures, package names) stay consistent.

**Alternatives discarded:**
- *Single monolithic call generating the entire project at once*: simpler, but slow, hits context/output limits as project size grows, and any failure means restarting the whole generation.
- *Sequential per-file generation* (one file at a time, in order): avoids concurrency entirely, but total wall-clock time scales linearly with file count — too slow for 50+ files.

**Consequences:** Full runs stay in the 1–2 minute range even for 50+ files (measured). The trade-off is that the plan is fixed before any file content is written — if the LLM's plan is subtly wrong, every downstream file inherits that assumption, since there's no re-planning step once content generation starts.

---

### ADR-002: Java 21 Virtual Threads for Parallel Fan-Out

**Status:** Accepted

**Context:** Generating dozens of files in parallel means dozens of concurrent, I/O-bound tasks (each mostly waiting on an LLM API response, not doing CPU work).

**Decision:** Use `Executors.newVirtualThreadPerTaskExecutor()` with `CompletableFuture` to fan out one virtual thread per file-generation call.

**Alternatives discarded:**
- *Fixed-size platform thread pool* (`Executors.newFixedThreadPool(N)`): works, but requires tuning a pool-size trade-off between throughput and resource usage. Virtual threads remove that tuning — they're cheap enough to spin up one per file regardless of how many files a plan contains.
- *Reactive/async I/O* (e.g. reactive HTTP client chains): achieves similar concurrency without blocking OS threads, but trades plain, synchronous, easy-to-read code for a steeper reactive API — overkill for what's fundamentally "wait for N independent HTTP calls."

**Consequences:** Each generation task stays simple, blocking, synchronous code, while still scaling to however many files a plan contains — no thread pool size to guess ahead of time.

---

### ADR-003: `LlmProvider` as a Port (Hexagonal Architecture Applied to Itself)

**Status:** Accepted

**Context:** The tool needs to support more than one LLM vendor (Claude, OpenAI today) without vendor-specific code leaking into the CLI command or domain logic.

**Decision:** Define `LlmProvider` as a domain port (plan + generate file content), with `AnthropicLlmProvider` and `OpenAiLlmProvider` as infrastructure adapters implementing it. The CLI command and domain logic depend only on the port, never on a specific vendor SDK.

**Alternatives discarded:**
- *Hardcoding a single provider's SDK directly into the command*: simplest to start, but adding OpenAI support later would have meant touching command code instead of just adding a file.
- *A generic "prompt string in, text out" abstraction with no domain-specific methods*: more flexible on paper, but pushes response-parsing logic into the CLI layer for every provider, instead of keeping each adapter responsible for translating its own vendor's response shape into `FileSpec`/`GeneratedFile`.

**Consequences:** Adding a third provider is purely additive — a new adapter class implementing `LlmProvider`, no changes to `command/` or `domain/`. The cost is one extra layer of indirection that would be unnecessary if the tool only ever supported one provider.

---

### ADR-004: No Post-Generation Validation

**Status:** Accepted, with documented caveat

**Context:** After files are written to disk, the tool could shell out to `mvn compile` (or similar) against the generated project to catch LLM mistakes before returning control to the user.

**Decision:** Skip validation entirely. The tool's responsibility ends at "files written to disk"; build, test, and review are left to the user.

**Alternatives discarded:**
- *Auto-compile after generation*: would catch some classes of LLM error (syntax errors, missing imports) immediately, but adds a Maven/JVM dependency on the exact generated project's build, extra run time, and doesn't catch logic errors anyway — a green compile doesn't mean the domain logic is correct.
- *LLM self-review pass* (ask the model to critique its own output before finalizing): could catch more, but roughly doubles the number of LLM calls and total run time, cutting directly against the tool's core value proposition of being fast.

**Consequences:** Runs stay fast and simple, but there's no guarantee the generated project builds. This is stated explicitly in [Known Limitations](#known-limitations) rather than left for the user to discover the hard way.

---

### ADR-005: Partial-Failure Handling — Abort Only the Failed File, Keep Successful Ones

**Status:** Accepted

**Context:** With 50+ parallel LLM calls per run, individual calls can fail (rate limits, transient network errors, provider-side errors) even when most of the run succeeds.

**Decision:** A failure in one file's generation call aborts only that file — it isn't written, and is reported to the user — while every other file that succeeded is still written to disk normally. The run as a whole is not rolled back because one file failed.

**Alternatives discarded:**
- *All-or-nothing* (discard everything if any single file fails): guarantees the output directory is never left in a partially-generated state, but throws away potentially 60+ successfully generated files over one transient failure — expensive to redo against paid LLM APIs.
- *Automatic retry with backoff for failed files*: would recover more runs without user intervention, but adds retry/backoff logic and non-determinism to reason about; not implemented today.

**Consequences:** A run can end with, say, 64 of 66 files written and 2 reported as failed — the user re-runs or manually patches just those 2, instead of losing the other 64. The trade-off: the output directory can look superficially complete (the project structure exists) while some planned files are actually missing, if the user doesn't read the failure report.

---

### ADR-006: Bring-Your-Own API Key — No Bundled Key or Hosted Proxy

**Status:** Accepted

**Context:** A CLI tool that calls LLM APIs needs credentials. One option is to bundle a key or run a hosted proxy so users don't need their own account; the other is to require users to supply their own.

**Decision:** hexagen-cli never ships, stores, or depends on any API key belonging to its developers. Users export `ANTHROPIC_API_KEY` or `OPENAI_API_KEY` themselves, exactly as they would for any other tool calling that API directly.

**Alternatives discarded:**
- *Bundled/shared API key*: removes setup friction, but means the developer pays for and rate-limits every user's usage — not sustainable for a personal project, and a single point of abuse.
- *Hosted proxy service*: would allow adding features like usage analytics or caching, but requires standing up and maintaining server infrastructure, and routes every user's domain names and generated code through a third party.

**Consequences:** Zero infrastructure to run or pay for; usage cost and rate limits are entirely the user's own, tied to their own provider account. The trade-off is slightly more setup friction — the user needs their own API key before the tool does anything.
