package com.finsent.analyse.pass;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.analyse.claude.ClaudeJson;
import com.finsent.analyse.claude.IClaudeClient;
import com.finsent.analyse.claude.PromptBuilder;
import com.finsent.analyse.claude.PromptTemplates;
import com.finsent.core.Json;
import com.finsent.util.GlobalSystem;

/**
 * Pass 1 &mdash; the Haiku screener (ports Python {@code call_claude_screener} +
 * {@code _build_score_map}). Scores the window's articles for BTC impact in batches of
 * {@value #BATCH_SIZE}, annotates each article with {@code screener_score} / {@code screener_reason},
 * and selects the resonant set ({@code |score| >= threshold}). Tolerant of the LLM's index quirks
 * (string / 0-based {@code "i"} indices are coerced when mapping scores back to articles), but a batch
 * that cannot be parsed after a retry throws {@link IllegalStateException} rather than guessing,
 * aborting the window so the deep pass never runs on unscored input.
 *
 * <p>The caller supplies the (hot-reloadable) screener template and a window whose articles are
 * already de-duplicated; this pass does not load files or touch the network beyond the injected
 * {@link IClaudeClient}.
 */
public final class ScreenerPass
{
    private static final String NAME = "ScreenerPass";
    private static final int BATCH_SIZE = 100;
    private static final int MAX_ATTEMPTS = 2;
    private static final int TOKENS_PER_ARTICLE = 50;
    private static final int TOKENS_FLOOR = 600;
    private static final int TOKENS_OVERHEAD = 200;
    private static final double COVERAGE_MIN_FRACTION = 0.5;

    private final IClaudeClient client_;
    private final String model_;
    private final int threshold_;

    public ScreenerPass(IClaudeClient client, String model, int threshold)
    {
        client_ = client;
        model_ = model;
        threshold_ = threshold;
    }

    /**
     * Score {@code articles} against {@code screenerTemplate} and select the resonant set. Throws
     * {@link IllegalStateException} if a batch cannot be parsed after retries, aborting the window
     * rather than running the deep pass on fabricated scores (the caller's cycle handler logs and
     * skips it).
     */
    public ScreenerResult screen(List<ObjectNode> articles, String coveredBlock, String screenerTemplate)
    {
        ArrayNode scores = scoreAllBatches(articles, coveredBlock, screenerTemplate);
        Map<Integer, JsonNode> scoreMap = buildScoreMap(scores, articles.size());
        applyScores(articles, scoreMap);
        return new ScreenerResult(selectResonant(articles), screenerOut(articles));
    }

    private ArrayNode scoreAllBatches(List<ObjectNode> articles, String coveredBlock, String template)
    {
        ArrayNode all = Json.newArray();
        for (int start = 0; start < articles.size(); start += BATCH_SIZE)
        {
            List<ObjectNode> batch = articles.subList(start, Math.min(start + BATCH_SIZE, articles.size()));
            all.addAll(scoreBatch(batch, start, coveredBlock, template));
        }
        return all;
    }

    private ArrayNode scoreBatch(List<ObjectNode> batch, int indexOffset, String coveredBlock, String template)
    {
        String articleBlock = PromptBuilder.screenerArticles(batch, new HashMap<>(), indexOffset);
        String prompt = PromptTemplates.fillScreener(template, coveredBlock, articleBlock);
        int maxTokens = Math.max(TOKENS_FLOOR, batch.size() * TOKENS_PER_ARTICLE + TOKENS_OVERHEAD);

        ArrayNode parsed = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS && parsed == null; attempt++)
        {
            String text = callQuietly(prompt, maxTokens, attempt);
            if (text != null)
            {
                parsed = ClaudeJson.extractArray(text);
            }
        }
        if (parsed == null)
        {
            throw new IllegalStateException("Screener failed to parse a response after " + MAX_ATTEMPTS
                    + " attempts for a batch of " + batch.size() + " article(s)");
        }
        return parsed;
    }

    private String callQuietly(String prompt, int maxTokens, int attempt)
    {
        String text = null;
        try
        {
            text = client_.complete(model_, prompt, maxTokens);
        }
        catch (IOException callFailed)
        {
            GlobalSystem.warning().writes(NAME, "Screener API call failed (attempt " + attempt + ") ", callFailed);
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
            GlobalSystem.warning().writes(NAME, "Screener call interrupted (attempt " + attempt + ")");
        }
        return text;
    }

    /**
     * Map the screener's {@code "i"} to its score entry, coercing string indices and shifting a
     * wholly 0-based response by +1 (ports Python {@code _build_score_map}).
     */
    private Map<Integer, JsonNode> buildScoreMap(ArrayNode scores, int expected)
    {
        Map<Integer, JsonNode> rawMap = new HashMap<>();
        for (JsonNode entry : scores)
        {
            Integer index = coerceInt(entry.path("i"));
            if (index != null)
            {
                rawMap.put(index, entry);
            }
        }
        int matchedOneBased = countMatched(rawMap, 1, expected);
        int matchedZeroBased = countMatched(rawMap, 0, expected - 1);
        Map<Integer, JsonNode> result = rawMap;
        if (matchedOneBased < expected * COVERAGE_MIN_FRACTION && matchedZeroBased > matchedOneBased)
        {
            result = shiftPlusOne(rawMap);
        }
        return result;
    }

    private static int countMatched(Map<Integer, JsonNode> map, int lowInclusive, int highInclusive)
    {
        int matched = 0;
        for (Integer key : map.keySet())
        {
            if (key >= lowInclusive && key <= highInclusive)
            {
                matched++;
            }
        }
        return matched;
    }

    private static Map<Integer, JsonNode> shiftPlusOne(Map<Integer, JsonNode> rawMap)
    {
        Map<Integer, JsonNode> shifted = new HashMap<>();
        for (Map.Entry<Integer, JsonNode> entry : rawMap.entrySet())
        {
            shifted.put(entry.getKey() + 1, entry.getValue());
        }
        return shifted;
    }

    private static void applyScores(List<ObjectNode> articles, Map<Integer, JsonNode> scoreMap)
    {
        for (int position = 0; position < articles.size(); position++)
        {
            ObjectNode article = articles.get(position);
            JsonNode entry = scoreMap.get(position + 1);
            Integer score = entry == null ? null : coerceInt(entry.path("score"));
            if (score == null)
            {
                article.putNull("screener_score");
            }
            else
            {
                article.put("screener_score", score.intValue());
            }
            article.put("screener_reason", entry == null ? "" : entry.path("reason").asText(""));
        }
    }

    private List<ObjectNode> selectResonant(List<ObjectNode> articles)
    {
        List<ObjectNode> resonant = new ArrayList<>();
        for (ObjectNode article : articles)
        {
            if (Math.abs(article.path("screener_score").asInt(0)) >= threshold_)
            {
                resonant.add(article);
            }
        }
        return resonant;
    }

    private static ArrayNode screenerOut(List<ObjectNode> articles)
    {
        ArrayNode out = Json.newArray();
        for (ObjectNode article : articles)
        {
            ObjectNode entry = Json.newObject();
            entry.put("article_id", article.path("id").asInt());
            JsonNode score = article.path("screener_score");
            if (score.isNumber())
            {
                entry.put("score", score.asInt());
            }
            else
            {
                entry.putNull("score");
            }
            entry.put("reason", article.path("screener_reason").asText(""));
            out.add(entry);
        }
        return out;
    }

    private static Integer coerceInt(JsonNode node)
    {
        Integer result = null;
        if (node != null && !node.isNull())
        {
            if (node.isNumber())
            {
                result = node.asInt();
            }
            else if (node.isTextual())
            {
                result = parseIntOrNull(node.asText().trim());
            }
        }
        return result;
    }

    private static Integer parseIntOrNull(String text)
    {
        Integer result;
        try
        {
            result = Integer.valueOf(text);
        }
        catch (NumberFormatException notInt)
        {
            result = null;
        }
        return result;
    }
}
