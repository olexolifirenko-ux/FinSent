package com.finsent.analyse.claude;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.core.Http;
import com.finsent.core.Json;

/**
 * Anthropic Messages API client (ports Python {@code analyse.call_claude}). Issues a raw HTTP POST
 * through {@link Http} (which handles retry/backoff) with the {@code x-api-key} and
 * {@code anthropic-version} headers, a single-user-message body, and returns the text of the first
 * content block. The endpoint URL is injected (from {@code Config}) rather than hardcoded.
 */
public final class ClaudeClient implements IClaudeClient
{
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final String apiKey_;
    private final String messagesUrl_;

    public ClaudeClient(String apiKey, String messagesUrl)
    {
        apiKey_ = apiKey;
        messagesUrl_ = messagesUrl;
    }

    @Override
    public String complete(String model, String prompt, int maxTokens) throws IOException, InterruptedException
    {
        Map<String, String> headers = Map.of("x-api-key", apiKey_, "anthropic-version", ANTHROPIC_VERSION);
        String response = Http.postJson(messagesUrl_, requestBody(model, prompt, maxTokens), headers, TIMEOUT);
        return firstContentText(response);
    }

    private static String requestBody(String model, String prompt, int maxTokens) throws IOException
    {
        ObjectNode message = Json.newObject();
        message.put("role", "user");
        message.put("content", prompt);
        ArrayNode messages = Json.newArray();
        messages.add(message);

        ObjectNode body = Json.newObject();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.set("messages", messages);
        return Json.toCompactString(body);
    }

    private static String firstContentText(String response) throws IOException
    {
        JsonNode content = Json.parse(response).path("content");
        return content.isArray() && content.size() > 0 ? content.get(0).path("text").asText("") : "";
    }
}
