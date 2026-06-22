package com.finsent.analyse.claude;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import com.finsent.core.Json;

/**
 * JSON Schemas the Claude passes constrain their output to via the Messages API {@code
 * output_config.format} (structured outputs): the API guarantees the response is schema-valid JSON,
 * so a malformed answer can no longer slip through to the tolerant parser as a null/noise result.
 * Per pass, because the shapes differ: the screener returns an array of relevance scores; the news
 * deep pass returns an aggregate call <b>plus</b> a per-article array; the econ deep variant
 * returns the aggregate call only (no per-article block). {@code additionalProperties:false} keeps the
 * model from adding stray fields (e.g. a {@code macro_regime} the analyser computes itself).
 */
public final class ClaudeSchemas
{
    private static final String DIRECTION = "{\"type\":\"string\",\"enum\":[\"bullish\",\"bearish\",\"neutral\"]}";
    private static final String IMPACT_TIER = "{\"type\":\"string\",\"enum\":[\"high\",\"low\",\"noise\"]}";

    /** Screener output: one {@code {i, score 0-3, reason}} per article (array root). */
    public static final JsonNode SCREENER = parse("{\"type\":\"array\",\"items\":{\"type\":\"object\","
            + "\"additionalProperties\":false,\"required\":[\"i\",\"score\",\"reason\"],\"properties\":{"
            + "\"i\":{\"type\":\"integer\"},\"score\":{\"type\":\"integer\",\"enum\":[0,1,2,3]},"
            + "\"reason\":{\"type\":\"string\"}}}}");

    /** News deep pass: the aggregate call plus the per-article {@code articles} array. */
    public static final JsonNode NEWS_DEEP = parse("{\"type\":\"object\",\"additionalProperties\":false,"
            + "\"required\":[\"direction\",\"impact_tier\",\"key_events\",\"reasoning\",\"articles\"],\"properties\":{"
            + "\"direction\":" + DIRECTION + ",\"impact_tier\":" + IMPACT_TIER + ","
            + "\"key_events\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},\"reasoning\":{\"type\":\"string\"},"
            + "\"articles\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"additionalProperties\":false,"
            + "\"required\":[\"i\",\"direction\",\"reasoning\"],\"properties\":{\"i\":{\"type\":\"integer\"},"
            + "\"direction\":" + DIRECTION + ",\"reasoning\":{\"type\":\"string\"}}}}}}");

    /** Econ deep pass: the aggregate call only (no per-article block). */
    public static final JsonNode ALERT_DEEP = parse("{\"type\":\"object\",\"additionalProperties\":false,"
            + "\"required\":[\"direction\",\"impact_tier\",\"key_events\",\"reasoning\"],\"properties\":{"
            + "\"direction\":" + DIRECTION + ",\"impact_tier\":" + IMPACT_TIER + ","
            + "\"key_events\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},\"reasoning\":{\"type\":\"string\"}}}");

    private ClaudeSchemas()
    {
    }

    /** Parse a constant schema; a malformed literal is a programming error, surfaced eagerly at load. */
    private static JsonNode parse(String json)
    {
        try
        {
            return Json.parse(json);
        }
        catch (JsonProcessingException malformed)
        {
            throw new IllegalStateException("malformed Claude output schema literal", malformed);
        }
    }
}
