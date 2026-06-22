package com.finsent.analyse.pass;

import java.io.IOException;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.analyse.claude.ClaudeJson;
import com.finsent.analyse.claude.IClaudeClient;
import com.finsent.core.Json;
import com.finsent.util.GlobalSystem;

/**
 * Pass 2 &mdash; the Sonnet deep analysis (ports Python {@code parse_analysis_response}). Sends the
 * pre-assembled prompt (built by the pipeline from the resonant articles + mechanical signals) and
 * parses the single JSON object Claude returns: it splits out the per-article {@code articles}
 * array, defaults an out-of-range {@code impact_tier} to {@code noise}, and strips any
 * Claude-supplied {@code macro_regime} (that field is computed mechanically). A response that can't
 * be parsed or has no {@code direction} yields a null prediction (the legacy delimited-block parser
 * is out of scope for this port).
 */
public final class DeepAnalysisPass
{
    private static final String NAME = "DeepAnalysisPass";
    // Headroom for adaptive thinking + the JSON answer: the model reasons privately first, and that
    // reasoning shares the response budget with the output, so the cap is well above the JSON's size.
    private static final int MAX_TOKENS = 4000;
    private static final Set<String> VALID_IMPACT_TIERS = Set.of("high", "low", "noise");

    private final IClaudeClient client_;
    private final String model_;
    private final String effort_;

    public DeepAnalysisPass(IClaudeClient client, String model, String effort)
    {
        client_ = client;
        model_ = model;
        effort_ = effort;
    }

    /** Run deep analysis on the assembled {@code prompt} (free-form output) and parse the result. */
    public DeepResult analyse(String prompt)
    {
        return analyse(prompt, null);
    }

    /**
     * Run deep analysis with no cached system block (the econ pass), constraining the output to
     * {@code schema} when non-null.
     */
    public DeepResult analyse(String prompt, JsonNode schema)
    {
        return analyse(null, prompt, schema);
    }

    /**
     * Run deep analysis with a cacheable static {@code system} block (the news pass) plus the volatile
     * {@code userContent}, constraining the output to {@code schema} (structured outputs) when non-null.
     */
    public DeepResult analyse(String system, String userContent, JsonNode schema)
    {
        String text = callQuietly(system, userContent, schema);
        ObjectNode prediction = text == null ? null : ClaudeJson.extractObject(text);
        DeepResult result;
        if (prediction == null || !prediction.has("direction"))
        {
            result = new DeepResult(null, Json.newArray());
        }
        else
        {
            ArrayNode articles = popArticles(prediction);
            clampImpactTier(prediction);
            prediction.remove("macro_regime");
            result = new DeepResult(prediction, articles);
        }
        return result;
    }

    private String callQuietly(String system, String prompt, JsonNode schema)
    {
        String text = null;
        try
        {
            // Adaptive thinking on the decisive pass, capped at the configured effort; the static
            // instructions ride in the cacheable system block (null for the econ pass).
            text = client_.complete(model_, system, prompt, MAX_TOKENS, true, effort_, schema);
        }
        catch (IOException callFailed)
        {
            GlobalSystem.warning().writes(NAME, "Deep-analysis API call failed", callFailed);
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
            GlobalSystem.warning().writes(NAME, "Deep-analysis call interrupted");
        }
        return text;
    }

    private static ArrayNode popArticles(ObjectNode prediction)
    {
        JsonNode articles = prediction.remove("articles");
        return articles instanceof ArrayNode ? (ArrayNode) articles : Json.newArray();
    }

    private static void clampImpactTier(ObjectNode prediction)
    {
        if (!VALID_IMPACT_TIERS.contains(prediction.path("impact_tier").asText()))
        {
            GlobalSystem.warning().writes(NAME, "Invalid impact_tier '"
                    + prediction.path("impact_tier").asText() + "' -- defaulting to noise.");
            prediction.put("impact_tier", "noise");
        }
    }
}
