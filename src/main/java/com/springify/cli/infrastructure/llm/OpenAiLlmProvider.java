package com.springify.cli.infrastructure.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springify.cli.domain.FileSpec;
import com.springify.cli.domain.GeneratedFile;
import com.springify.cli.domain.LlmException;
import com.springify.cli.domain.LlmProvider;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * {@link LlmProvider} implementation backed by the OpenAI Chat Completions API.
 * The API key is always supplied by the caller - see {@link LlmProvider}.
 */
public final class OpenAiLlmProvider implements LlmProvider {

    private static final String API_URL = "https://api.openai.com/v1/chat/completions";
    private static final String MODEL = "gpt-4o-mini";

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

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    public OpenAiLlmProvider(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public List<FileSpec> planProject(String context) throws LlmException {
        String responseText = complete(PLAN_SYSTEM_PROMPT, "Domain/context: " + context);
        return parseFileSpecs(responseText);
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

        String responseText = complete(FILE_SYSTEM_PROMPT, userMessage);
        String content = parseFileContent(responseText);
        return new GeneratedFile(fileSpec.path(), content);
    }

    private String complete(String systemPrompt, String userMessage) throws LlmException {
        String requestBody = buildRequestBody(systemPrompt, userMessage);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new LlmException("Network failure while calling the OpenAI API: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Call to the OpenAI API was interrupted.", e);
        }

        if (response.statusCode() != 200) {
            throw new LlmException("OpenAI API returned status " + response.statusCode() + ": " + response.body());
        }

        return extractMessageContent(response.body());
    }

    private String buildRequestBody(String systemPrompt, String userMessage) throws LlmException {
        try {
            var root = objectMapper.createObjectNode();
            root.put("model", MODEL);

            var responseFormat = objectMapper.createObjectNode();
            responseFormat.put("type", "json_object");
            root.set("response_format", responseFormat);

            var messages = objectMapper.createArrayNode();

            var systemMessage = objectMapper.createObjectNode();
            systemMessage.put("role", "system");
            systemMessage.put("content", systemPrompt);
            messages.add(systemMessage);

            var userMessageNode = objectMapper.createObjectNode();
            userMessageNode.put("role", "user");
            userMessageNode.put("content", userMessage);
            messages.add(userMessageNode);

            root.set("messages", messages);

            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new LlmException("Failed to build the request to the LLM: " + e.getMessage(), e);
        }
    }

    private String extractMessageContent(String responseBody) throws LlmException {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull()) {
                throw new LlmException("OpenAI response does not contain the expected content: " + responseBody);
            }
            return content.asText();
        } catch (IOException e) {
            throw new LlmException("Failed to parse the OpenAI response: " + e.getMessage(), e);
        }
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
