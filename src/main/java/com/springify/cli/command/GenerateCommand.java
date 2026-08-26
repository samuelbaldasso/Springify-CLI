package com.springify.cli.command;

import com.springify.cli.domain.FileSpec;
import com.springify.cli.domain.GeneratedFile;
import com.springify.cli.domain.LlmException;
import com.springify.cli.domain.LlmProvider;
import com.springify.cli.infrastructure.llm.AnthropicLlmProvider;
import com.springify.cli.infrastructure.llm.OpenAiLlmProvider;
import com.springify.cli.infrastructure.workspace.WorkspaceManager;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Command(
        name = "generate",
        mixinStandardHelpOptions = true,
        version = "hexagen-cli 1.0.0",
        description = "Generates a Java project in Hexagonal Architecture for the given context/domain, using an LLM."
)
public final class GenerateCommand implements Callable<Integer> {

    private static final String ANTHROPIC_API_KEY_ENV_VAR = "ANTHROPIC_API_KEY";
    private static final String OPENAI_API_KEY_ENV_VAR = "OPENAI_API_KEY";

    @Parameters(
            index = "0",
            paramLabel = "<context>",
            description = "Domain/context name to generate (e.g. Billing)."
    )
    private String context;

    @Option(
            names = {"-o", "--output"},
            paramLabel = "<directory>",
            description = "Output directory the project will be generated into (default: current directory)."
    )
    private String outputDirectory = System.getProperty("user.dir");

    @Option(
            names = {"-p", "--provider"},
            paramLabel = "<provider>",
            description = "LLM provider to use: claude (default) or openai. Reads the matching API key from " +
                    "your own environment - this tool never ships or depends on a bundled key."
    )
    private String provider = "claude";

    private final WorkspaceManager workspaceManager = new WorkspaceManager();

    @Override
    public Integer call() {
        LlmProvider llmProvider;
        try {
            llmProvider = resolveLlmProvider();
        } catch (IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }

        System.out.printf("Planning project structure for context '%s' (provider: %s)...%n", context, provider);

        List<FileSpec> fileSpecs;
        try {
            fileSpecs = llmProvider.planProject(context);
        } catch (LlmException e) {
            System.err.println("Error while planning the project with the LLM: " + e.getMessage());
            return 2;
        }

        if (fileSpecs.isEmpty()) {
            System.err.println("The LLM did not plan any file for the given context.");
            return 3;
        }

        System.out.printf("Generating %d file(s) in parallel...%n", fileSpecs.size());

        List<GeneratedFile> files;
        try {
            files = generateFilesInParallel(llmProvider, fileSpecs);
        } catch (LlmException e) {
            System.err.println("Error while generating file content with the LLM: " + e.getMessage());
            return 2;
        }

        try {
            workspaceManager.write(Path.of(outputDirectory), files);
        } catch (IOException e) {
            System.err.println("Error writing files to the output directory: " + e.getMessage());
            return 4;
        }

        System.out.printf("%d file(s) successfully generated at: %s%n", files.size(), outputDirectory);
        return 0;
    }

    private List<GeneratedFile> generateFilesInParallel(LlmProvider llmProvider, List<FileSpec> fileSpecs)
            throws LlmException {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<GeneratedFile>> futures = fileSpecs.stream()
                    .map(fileSpec -> CompletableFuture.supplyAsync(
                            () -> generateOrThrow(llmProvider, fileSpecs, fileSpec), executor))
                    .toList();

            List<GeneratedFile> files = new ArrayList<>(futures.size());
            try {
                for (CompletableFuture<GeneratedFile> future : futures) {
                    files.add(future.join());
                }
            } catch (CompletionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof LlmException llmException) {
                    throw llmException;
                }
                throw new LlmException("Unexpected failure while generating a file: " + cause.getMessage(), cause);
            }
            return files;
        }
    }

    private GeneratedFile generateOrThrow(LlmProvider llmProvider, List<FileSpec> allFiles, FileSpec fileSpec) {
        try {
            return llmProvider.generateFileContent(context, allFiles, fileSpec);
        } catch (LlmException e) {
            throw new CompletionException(e);
        }
    }

    private LlmProvider resolveLlmProvider() {
        return switch (provider.toLowerCase()) {
            case "claude", "anthropic" -> new AnthropicLlmProvider(requireEnv(ANTHROPIC_API_KEY_ENV_VAR));
            case "openai", "chatgpt", "gpt" -> new OpenAiLlmProvider(requireEnv(OPENAI_API_KEY_ENV_VAR));
            default -> throw new IllegalStateException(
                    "Unknown provider: '" + provider + "'. Use 'claude' or 'openai'.");
        };
    }

    private String requireEnv(String envVar) {
        String value = System.getenv(envVar);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "environment variable " + envVar + " is not set. Export your own API key before continuing.");
        }
        return value;
    }
}
