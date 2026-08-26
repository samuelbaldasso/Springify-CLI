package com.springify.cli.domain;

import java.util.List;

/**
 * Output port for LLM-backed project generation. Implementations must never embed
 * their own credentials - the API key always comes from the caller (typically read
 * from an environment variable set by the end user), so the CLI never depends on a
 * key owned by this tool's developers.
 */
public interface LlmProvider {

    /**
     * Asks the LLM to plan the Hexagonal Architecture (Ports and Adapters) file structure
     * for the given context/domain. This call is intentionally small and fast: it returns
     * only file paths and short descriptions, not file content.
     *
     * @param context domain/context name (e.g. "Billing")
     * @return the list of files to be generated
     * @throws LlmException if the call fails or the response cannot be interpreted
     */
    List<FileSpec> planProject(String context) throws LlmException;

    /**
     * Asks the LLM to generate the full content of a single planned file. Called once per
     * file, in parallel, so the caller can fan out many concurrent requests instead of
     * waiting for one large response.
     *
     * @param context  domain/context name (e.g. "Billing")
     * @param allFiles the full file plan, given as context so cross-file references stay consistent
     * @param fileSpec the specific file to generate content for
     * @return the generated file, ready to be written to disk
     * @throws LlmException if the call fails or the response cannot be interpreted
     */
    GeneratedFile generateFileContent(String context, List<FileSpec> allFiles, FileSpec fileSpec) throws LlmException;
}
