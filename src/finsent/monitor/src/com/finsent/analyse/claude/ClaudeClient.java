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
 * <b>text</b> content block (skipping a leading thinking block). The screener runs at {@code
 * temperature} 0 (deterministic classification); the deep pass runs with adaptive {@code thinking} so
 * the model reasons through the fact/new/channel test before answering (sampling params are omitted
 * with thinking on). The endpoint URL is injected (from {@code Config}) rather than hardcoded.
 */
public final class ClaudeClient implements IClaudeClient
{
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    // Both passes are deterministic classification/extraction (screener scoring, deep JSON analysis),
    // not creative generation, so we pin temperature to 0 for the most reproducible output. Haiku 4.5
    // and Sonnet 4.6 both still accept the sampling parameter (it is only removed on the Opus-4.7+ tier).
    private static final double TEMPERATURE = 0.0;

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
        return complete(model, prompt, maxTokens, false);
    }

    @Override
    public String complete(String model, String prompt, int maxTokens, boolean thinking)
            throws IOException, InterruptedException
    {
        Map<String, String> headers = Map.of("x-api-key", apiKey_, "anthropic-version", ANTHROPIC_VERSION);
        String response = Http.postJson(messagesUrl_, requestBody(model, prompt, maxTokens, thinking), headers, TIMEOUT);
        return firstContentText(response);
    }

    private static String requestBody(String model, String prompt, int maxTokens, boolean thinking) throws IOException
    {
        ObjectNode message = Json.newObject();
        message.put("role", "user");
        message.put("content", prompt);
        ArrayNode messages = Json.newArray();
        messages.add(message);

        ObjectNode body = Json.newObject();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        if (thinking)
        {
            // Adaptive thinking: the model decides how much to reason privately before answering. Sampling
            // params are omitted with thinking on (the thinking-capable models reject them alongside it).
            ObjectNode think = Json.newObject();
            think.put("type", "adaptive");
            body.set("thinking", think);
        }
        else
        {
            body.put("temperature", TEMPERATURE);
        }
        body.set("messages", messages);
        return Json.toCompactString(body);
    }

    /** The first {@code text}-type content block's text, skipping any leading {@code thinking} block. */
    private static String firstContentText(String response) throws IOException
    {
        JsonNode content = Json.parse(response).path("content");
        String text = "";
        if (content.isArray())
        {
            for (JsonNode block : content)
            {
                if (block.path("type").asText("").equals("text"))
                {
                    text = block.path("text").asText("");
                    break;
                }
            }
        }
        return text;
    }
}
