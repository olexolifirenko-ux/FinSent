package com.finsent.analyse.claude;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.core.Http;
import com.finsent.core.Json;
import com.finsent.util.GlobalSystem;

/**
 * Anthropic Messages API client (ports Python {@code analyse.call_claude}). Issues a raw HTTP POST
 * through {@link Http} (which handles retry/backoff) with the {@code x-api-key} and
 * {@code anthropic-version} headers and returns the text of the first <b>text</b> content block
 * (skipping a leading thinking block). The screener runs at {@code temperature} 0 (deterministic
 * classification); the deep pass runs with adaptive {@code thinking} so the model reasons through the
 * fact/new/channel test before answering (sampling params are omitted with thinking on), capped by an
 * {@code output_config.effort} level. The deep pass also sends its large static instruction block as a
 * cacheable {@code system} content block (an ephemeral {@code cache_control} breakpoint) so the
 * high-frequency lane reuses the prefill instead of re-sending it. The endpoint URL is injected.
 */
public final class ClaudeClient implements IClaudeClient
{
    private static final String NAME = "ClaudeClient";
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
        return complete(model, null, prompt, maxTokens, false, null, null);
    }

    @Override
    public String complete(String model, String prompt, int maxTokens, boolean thinking, JsonNode schema)
            throws IOException, InterruptedException
    {
        return complete(model, null, prompt, maxTokens, thinking, null, schema);
    }

    @Override
    public String complete(String model, String system, String prompt, int maxTokens, boolean thinking,
                           String effort, JsonNode schema) throws IOException, InterruptedException
    {
        Map<String, String> headers = Map.of("x-api-key", apiKey_, "anthropic-version", ANTHROPIC_VERSION);
        String response = Http.postJson(messagesUrl_,
                requestBody(model, system, prompt, maxTokens, thinking, effort, schema), headers, TIMEOUT);
        if (system != null && !system.isEmpty())
        {
            logCacheUsage(response);
        }
        return firstContentText(response);
    }

    private static String requestBody(String model, String system, String prompt, int maxTokens,
                                      boolean thinking, String effort, JsonNode schema) throws IOException
    {
        ObjectNode message = Json.newObject();
        message.put("role", "user");
        message.put("content", prompt);
        ArrayNode messages = Json.newArray();
        messages.add(message);

        ObjectNode body = Json.newObject();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        if (system != null && !system.isEmpty())
        {
            body.set("system", systemBlocks(system));
        }
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
        ObjectNode outputConfig = outputConfig(schema, thinking, effort);
        if (outputConfig != null)
        {
            body.set("output_config", outputConfig);
        }
        body.set("messages", messages);
        return Json.toCompactString(body);
    }

    /** The static instruction block as a single {@code text} system block with an ephemeral cache breakpoint. */
    private static ArrayNode systemBlocks(String system)
    {
        ObjectNode cacheControl = Json.newObject();
        cacheControl.put("type", "ephemeral");
        ObjectNode block = Json.newObject();
        block.put("type", "text");
        block.put("text", system);
        block.set("cache_control", cacheControl);
        ArrayNode blocks = Json.newArray();
        blocks.add(block);
        return blocks;
    }

    /**
     * The {@code output_config} for the request, or null when neither applies: the structured-output
     * {@code format} (schema) and, on the thinking pass, the {@code effort} cap on thinking depth.
     */
    private static ObjectNode outputConfig(JsonNode schema, boolean thinking, String effort)
    {
        ObjectNode outputConfig = null;
        if (schema != null)
        {
            // Structured outputs: constrain the response to the given JSON Schema so the API returns
            // schema-valid JSON (output_config.format / json_schema), removing the malformed-JSON path.
            ObjectNode format = Json.newObject();
            format.put("type", "json_schema");
            format.set("schema", schema);
            outputConfig = Json.newObject();
            outputConfig.set("format", format);
        }
        if (thinking && effort != null && !effort.isEmpty())
        {
            if (outputConfig == null)
            {
                outputConfig = Json.newObject();
            }
            outputConfig.put("effort", effort);
        }
        return outputConfig;
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

    /** Best-effort cache-hit diagnostics for the cached deep pass (verifies the system prefix is being reused). */
    private static void logCacheUsage(String response)
    {
        try
        {
            JsonNode usage = Json.parse(response).path("usage");
            GlobalSystem.debug().writes(NAME, "deep cache: read=" + usage.path("cache_read_input_tokens").asInt(0)
                    + " write=" + usage.path("cache_creation_input_tokens").asInt(0));
        }
        catch (IOException badJson)
        {
            // Diagnostics only -- a parse failure here must never affect the analysis result.
        }
    }
}
