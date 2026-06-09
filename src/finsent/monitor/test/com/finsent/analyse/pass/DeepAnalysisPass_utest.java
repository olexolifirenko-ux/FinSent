package com.finsent.analyse.pass;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;

import org.junit.Test;

import com.finsent.analyse.claude.IClaudeClient;

/**
 * Verifies {@link DeepAnalysisPass} against Python {@code parse_analysis_response}: splitting the
 * per-article {@code articles} array out of the prediction object, defaulting an invalid
 * {@code impact_tier} to {@code noise}, defaulting a missing/invalid {@code confidence} to
 * {@code low}, stripping a Claude-supplied {@code macro_regime}, and yielding a null prediction on a
 * missing {@code direction} or an API failure.
 */
public class DeepAnalysisPass_utest
{
    private static final String MODEL = "claude-sonnet-test";

    @Test
    public void parsesPredictionAndSplitsArticles()
    {
        StubClaudeClient client = new StubClaudeClient().enqueue(
                "{\"direction\":\"bearish\",\"impact_tier\":\"high\",\"key_events\":[\"e1\"],"
                        + "\"reasoning\":\"r\",\"articles\":[{\"i\":1,\"direction\":\"bearish\",\"reasoning\":\"x\"}]}");

        DeepResult result = new DeepAnalysisPass(client, MODEL).analyse("PROMPT");

        assertEquals("bearish", result.prediction().path("direction").asText());
        assertEquals("high", result.prediction().path("impact_tier").asText());
        assertFalse("articles split out of the prediction object", result.prediction().has("articles"));
        assertEquals(1, result.articles().size());
        assertEquals(1, result.articles().get(0).path("i").asInt());
        assertEquals("bearish", result.articles().get(0).path("direction").asText());
    }

    @Test
    public void stripsCodeFenceAroundObject()
    {
        StubClaudeClient client = new StubClaudeClient().enqueue(
                "```json\n{\"direction\":\"bullish\",\"impact_tier\":\"low\"}\n```");

        DeepResult result = new DeepAnalysisPass(client, MODEL).analyse("PROMPT");

        assertEquals("bullish", result.prediction().path("direction").asText());
    }

    @Test
    public void clampsInvalidImpactTierToNoise()
    {
        StubClaudeClient client = new StubClaudeClient().enqueue(
                "{\"direction\":\"neutral\",\"impact_tier\":\"extreme\",\"articles\":[]}");

        DeepResult result = new DeepAnalysisPass(client, MODEL).analyse("PROMPT");

        assertEquals("noise", result.prediction().path("impact_tier").asText());
    }

    @Test
    public void defaultsMissingOrInvalidConfidenceToLow()
    {
        StubClaudeClient client = new StubClaudeClient().enqueue(
                "{\"direction\":\"bearish\",\"impact_tier\":\"high\",\"confidence\":\"extreme\"}",
                "{\"direction\":\"bullish\",\"impact_tier\":\"high\",\"confidence\":\"high\"}",
                "{\"direction\":\"neutral\",\"impact_tier\":\"noise\"}");
        DeepAnalysisPass pass = new DeepAnalysisPass(client, MODEL);

        assertEquals("invalid confidence -> low", "low", pass.analyse("P").prediction().path("confidence").asText());
        assertEquals("valid confidence kept", "high", pass.analyse("P").prediction().path("confidence").asText());
        assertEquals("missing confidence -> low", "low", pass.analyse("P").prediction().path("confidence").asText());
    }

    @Test
    public void stripsClaudeSuppliedMacroRegime()
    {
        StubClaudeClient client = new StubClaudeClient().enqueue(
                "{\"direction\":\"bullish\",\"impact_tier\":\"low\",\"macro_regime\":\"risk_on\"}");

        DeepResult result = new DeepAnalysisPass(client, MODEL).analyse("PROMPT");

        assertFalse("macro_regime is computed mechanically, not taken from Claude",
                result.prediction().has("macro_regime"));
    }

    @Test
    public void nullPredictionWhenDirectionMissing()
    {
        StubClaudeClient client = new StubClaudeClient().enqueue("{\"impact_tier\":\"high\"}");

        DeepResult result = new DeepAnalysisPass(client, MODEL).analyse("PROMPT");

        assertNull(result.prediction());
        assertEquals(0, result.articles().size());
    }

    @Test
    public void nullPredictionOnApiFailure()
    {
        StubClaudeClient client = new StubClaudeClient(); // empty queue -> complete throws

        DeepResult result = new DeepAnalysisPass(client, MODEL).analyse("PROMPT");

        assertNull(result.prediction());
        assertEquals(0, result.articles().size());
    }

    /** Returns queued responses in order; an empty queue throws (drives the API-failure path). */
    private static final class StubClaudeClient implements IClaudeClient
    {
        private final Deque<String> responses_ = new ArrayDeque<>();

        private StubClaudeClient enqueue(String... responses)
        {
            for (String response : responses)
            {
                responses_.addLast(response);
            }
            return this;
        }

        @Override
        public String complete(String model, String prompt, int maxTokens) throws IOException
        {
            String response = responses_.pollFirst();
            if (response == null)
            {
                throw new IOException("stub: no response queued");
            }
            return response;
        }
    }
}
