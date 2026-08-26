package com.springify.cli.domain;

/**
 * A file ready to be materialized in the user's workspace.
 *
 * @param path    relative file path (e.g. "src/main/java/com/company/billing/domain/Invoice.java")
 * @param content full file content
 */
public record GeneratedFile(String path, String content) {
}
