package com.finsent.analyse.pass;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.Test;

import com.finsent.analyse.claude.IClaudeClient;
import com.finsent.core.Json;

/**
 * Verifies {@link ScreenerPass} against Python {@code call_claude_screener} + {@code _build_score_map}:
 * threshold-based resonant selection, 100-article batching with offset prompt indices, string /
 * 0-based index coercion, and the {@link IllegalStateException} abort when a batch cannot be parsed
 * after retries.
 */
public class ScreenerPass_utest
{
    private static final String TEMPLATE = "SCORE THESE:\n{articles}\n";
    private static final String MODEL = "claude-haiku-test";
    private static final int THRESHOLD = 2;

    @Test
    public void scoresAndSelectsResonantByThreshold()
    {
        StubClaudeClient client = new StubClaudeClient().enqueue(
                "[{\"i\":1,\"score\":3,\"reason\":\"a\"},{\"i\":2,\"score\":1,\"reason\":\"b\"},"
                        + "{\"i\":3,\"score\":2,\"reason\":\"c\"}]");
        List<ObjectNode> articles = articles(10, 20, 30);

        ScreenerResult result = new ScreenerPass(client, MODEL, THRESHOLD).screen(articles, "", TEMPLATE);

        assertEquals(List.of(10, 30), ids(result.resonant())); // 3 and 2 pass (>= 2); 1 does not
        assertEquals(1, articles.get(1).path("screener_score").asInt());
        assertEquals("b", articles.get(1).path("screener_reason").asText());
        assertEquals(3, result.screenerOut().size());
        assertEquals(10, result.screenerOut().get(0).path("article_id").asInt());
        assertEquals(3, result.screenerOut().get(0).path("score").asInt());
        assertEquals(1, client.callCount());
    }

    @Test
    public void batchesAtHundredWithOffsetPromptIndices()
    {
        StubClaudeClient client = new StubClaudeClient().enqueue(scoresJson(1, 100, 3), scoresJson(101, 120, 3));
        List<ObjectNode> articles = articleRange(1, 120);

        ScreenerResult result = new ScreenerPass(client, MODEL, THRESHOLD).screen(articles, "", TEMPLATE);

        assertEquals(2, client.callCount());
        assertEquals(120, result.resonant().size()); // all score 3 >= 2
        assertTrue("batch 1 numbers from 1", client.promptAt(0).contains("[1] "));
        assertTrue("batch 1 reaches 100", client.promptAt(0).contains("[100] "));
        assertTrue("batch 2 offset starts at 101", client.promptAt(1).contains("[101] "));
    }

    @Test
    public void coercesStringIndicesAndScores()
    {
        StubClaudeClient client = new StubClaudeClient().enqueue(
                "[{\"i\":\"1\",\"score\":\"3\",\"reason\":\"x\"},{\"i\":\"2\",\"score\":\"1\"}]");
        List<ObjectNode> articles = articles(5, 6);

        ScreenerResult result = new ScreenerPass(client, MODEL, THRESHOLD).screen(articles, "", TEMPLATE);

        assertEquals(List.of(5), ids(result.resonant()));
        assertEquals(3, articles.get(0).path("screener_score").asInt());
        assertEquals(1, articles.get(1).path("screener_score").asInt());
    }

    @Test
    public void shiftsWhollyZeroBasedIndices()
    {
        // One article scored at index 0: 1-based coverage is 0, 0-based is 1 -> shift +1.
        StubClaudeClient client = new StubClaudeClient().enqueue("[{\"i\":0,\"score\":3,\"reason\":\"z\"}]");
        List<ObjectNode> articles = articles(9);

        ScreenerResult result = new ScreenerPass(client, MODEL, THRESHOLD).screen(articles, "", TEMPLATE);

        assertEquals(List.of(9), ids(result.resonant()));
        assertEquals(3, articles.get(0).path("screener_score").asInt());
    }

    @Test
    public void failedScreenThrowsToAbort()
    {
        StubClaudeClient client = new StubClaudeClient(); // empty queue -> every call throws
        List<ObjectNode> articles = articles(1, 2);
        ScreenerPass pass = new ScreenerPass(client, MODEL, THRESHOLD);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> pass.screen(articles, "", TEMPLATE));

        assertTrue("message names the attempt count", failure.getMessage().contains("2 attempts"));
        assertEquals("two attempts before aborting", 2, client.callCount());
    }

    private static List<Integer> ids(List<ObjectNode> articles)
    {
        List<Integer> ids = new ArrayList<>();
        for (ObjectNode article : articles)
        {
            ids.add(article.path("id").asInt());
        }
        return ids;
    }

    private static List<ObjectNode> articles(int... ids)
    {
        List<ObjectNode> articles = new ArrayList<>();
        for (int id : ids)
        {
            articles.add(article(id));
        }
        return articles;
    }

    private static List<ObjectNode> articleRange(int fromId, int toId)
    {
        List<ObjectNode> articles = new ArrayList<>();
        for (int id = fromId; id <= toId; id++)
        {
            articles.add(article(id));
        }
        return articles;
    }

    private static ObjectNode article(int id)
    {
        ObjectNode source = Json.newObject();
        source.put("name", "Src");
        ObjectNode article = Json.newObject();
        article.put("id", id);
        article.set("source", source);
        article.put("publishedAt", "2026-06-04T08:00:00Z");
        article.put("title", "Title " + id);
        article.put("description", "");
        return article;
    }

    private static String scoresJson(int fromIndex, int toIndex, int score)
    {
        ArrayNode array = Json.newArray();
        for (int i = fromIndex; i <= toIndex; i++)
        {
            ObjectNode entry = Json.newObject();
            entry.put("i", i);
            entry.put("score", score);
            entry.put("reason", "r");
            array.add(entry);
        }
        return array.toString();
    }

    /** Returns queued responses in order and records prompts; an empty queue throws (drives fallback). */
    private static final class StubClaudeClient implements IClaudeClient
    {
        private final Deque<String> responses_ = new ArrayDeque<>();
        private final List<String> prompts_ = new ArrayList<>();

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
            prompts_.add(prompt);
            String response = responses_.pollFirst();
            if (response == null)
            {
                throw new IOException("stub: no response queued");
            }
            return response;
        }

        private int callCount()
        {
            return prompts_.size();
        }

        private String promptAt(int index)
        {
            return prompts_.get(index);
        }
    }
}
