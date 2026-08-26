package com.springify.cli.domain;

/**
 * A single file the LLM has planned to generate, identified by its relative path
 * and a short description used to prompt the content-generation step.
 *
 * @param path        relative file path (e.g. "src/main/java/com/company/billing/domain/Invoice.java")
 * @param description short description of the file's purpose, used to generate its content
 */
public record FileSpec(String path, String description) {
}
