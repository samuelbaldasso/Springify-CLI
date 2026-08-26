package com.springify.cli.infrastructure.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springify.cli.domain.FileSpec;
import com.springify.cli.domain.GeneratedFile;
import com.springify.cli.domain.LlmException;
import com.springify.cli.domain.LlmProvider;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * {@link LlmProvider} implementation backed by the Anthropic Messages API (Claude).
 * The API key is always supplied by the caller - see {@link LlmProvider}.
 */
public final class AnthropicLlmProvider implements LlmProvider {

    private static final String MODEL = "claude-opus-5";

    private static final String PLAN_SYSTEM_PROMPT = """
            You design the file structure of Java projects following Hexagonal Architecture
            (Ports and Adapters), using idiomatic Java 21 conventions.

            Given a domain/context name, list the packages, entities, ports (interfaces) and
            adapters that belong in that context. Do NOT write file content - only plan file
            paths and a one-sentence description of what each file should contain.

            Respond EXCLUSIVELY with a JSON object in the following format, with no extra text,
            no markdown, and no code fences (```):

            {
              "files": [
                { "path": "relative/path/File.java", "description": "one-sentence description" }
              ]
            }
            """;

    private static final String FILE_SYSTEM_PROMPT = """
            You write a single Java file that is part of a larger project generated in
            Hexagonal Architecture (Ports and Adapters), using idiomatic Java 21 conventions
            (records, text blocks, modern APIs where appropriate).

            You will be given the full list of planned files (for context/consistency) and the
            specific file you must write.

            Respond EXCLUSIVELY with a JSON object in the following format, with no extra text,
            no markdown, and no code fences (```):

            { "content": "full file content" }
            """;

    private final AnthropicClient client;
    private final ObjectMapper objectMapper;

    public AnthropicLlmProvider(String apiKey) {
        this.client = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public List<FileSpec> planProject(String context) throws LlmException {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(8000L)
                .thinking(ThinkingConfigAdaptive.builder().build())
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).build())
                .system(PLAN_SYSTEM_PROMPT)
                .addUserMessage("Domain/context: " + context)
                .build();

        Message response = send(params);
        String json = extractText(response);
        return parseFileSpecs(json);
    }

    @Override
    public GeneratedFile generateFileContent(String context, List<FileSpec> allFiles, FileSpec fileSpec)
            throws LlmException {
        String fileList = allFiles.stream()
                .map(f -> "- " + f.path() + ": " + f.description())
                .collect(Collectors.joining("\n"));

        String userMessage = """
                Domain/context: %s

                Full planned file list:
                %s

                Now write the content of this file:
                Path: %s
                Description: %s
                """.formatted(context, fileList, fileSpec.path(), fileSpec.description());

        MessageCreateParams params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(16000L)
                .thinking(ThinkingConfigAdaptive.builder().build())
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                .system(FILE_SYSTEM_PROMPT)
                .addUserMessage(userMessage)
                .build();

        Message response = send(params);
        String json = extractText(response);
        String content = parseFileContent(json);
        return new GeneratedFile(fileSpec.path(), content);
    }

    private Message send(MessageCreateParams params) throws LlmException {
        try {
            return client.messages().create(params);
        } catch (Exception e) {
            throw new LlmException("Failed to call the Anthropic API: " + e.getMessage(), e);
        }
    }

    private String extractText(Message response) throws LlmException {
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : response.content()) {
            block.text().ifPresent(t -> text.append(t.text()));
        }

        if (text.isEmpty()) {
            throw new LlmException("The Anthropic API returned no text content.");
        }

        return text.toString();
    }

    private List<FileSpec> parseFileSpecs(String json) throws LlmException {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode filesNode = root.path("files");
            if (!filesNode.isArray()) {
                throw new LlmException("LLM response is not in the expected format (missing or invalid 'files' field).");
            }

            List<FileSpec> files = new ArrayList<>();
            for (JsonNode fileNode : filesNode) {
                String path = fileNode.path("path").asText(null);
                String description = fileNode.path("description").asText(null);
                if (path == null || description == null) {
                    throw new LlmException("LLM returned an incomplete file plan entry: " + fileNode);
                }
                files.add(new FileSpec(path, description));
            }
            return files;
        } catch (IOException e) {
            throw new LlmException("LLM returned invalid JSON: " + e.getMessage(), e);
        }
    }

    private String parseFileContent(String json) throws LlmException {
        try {
            JsonNode root = objectMapper.readTree(json);
            String content = root.path("content").asText(null);
            if (content == null) {
                throw new LlmException("LLM response is missing the 'content' field.");
            }
            return content;
        } catch (IOException e) {
            throw new LlmException("LLM returned invalid JSON: " + e.getMessage(), e);
        }
    }
}
