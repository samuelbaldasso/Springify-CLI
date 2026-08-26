package com.springify.cli.domain;

/**
 * Error raised while communicating with the LLM provider or interpreting its response.
 */
public class LlmException extends Exception {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
