package com.finsent.analyse.claude;

import java.io.IOException;

/**
 * Seam over the Anthropic Messages API: a single user-message completion. Defined as an interface so
 * the screener and deep-analysis passes can be driven by a stub in unit tests without any network
 * access. The real implementation is {@link ClaudeClient}.
 */
public interface IClaudeClient
{
    /**
     * Send {@code prompt} as one user message to {@code model} and return the assistant's text
     * (the first text content block). {@code maxTokens} caps the response length.
     *
     * @throws IOException on transport failure or a non-2xx response
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    String complete(String model, String prompt, int maxTokens) throws IOException, InterruptedException;

    /**
     * As {@link #complete(String, String, int)} but with optional adaptive {@code thinking} (the model
     * reasons privately before answering -- used by the decisive deep pass, not the cheap screener).
     * Default: ignore the flag and delegate, so test stubs need not implement it; the real client
     * ({@link ClaudeClient}) overrides this to enable thinking on the request.
     */
    default String complete(String model, String prompt, int maxTokens, boolean thinking)
            throws IOException, InterruptedException
    {
        return complete(model, prompt, maxTokens);
    }
}
