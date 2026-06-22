package com.finsent.collect.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.core.Json;

/**
 * Verifies {@link XSquawkSource}'s pure mapping: OR-ing accounts into one {@code from:} query,
 * parsing Twitter's {@code created_at}, normalizing a tweet (incl. a quote's underlying text) to the
 * common article shape, and the source-watermark cutoff applied to the {@code tweets[]} array.
 */
public class XSquawkSource_utest
{
    @Test
    public void buildQueryOrsHandlesAndToleratesAtPrefix()
    {
        assertEquals("from:DeItaone OR from:FirstSquawk",
                XSquawkSource.buildQuery(List.of("DeItaone", "@FirstSquawk")));
        assertEquals("blank handles are dropped", "from:DeItaone",
                XSquawkSource.buildQuery(List.of(" ", "DeItaone")));
        assertEquals("empty list yields an empty query", "", XSquawkSource.buildQuery(List.of()));
    }

    @Test
    public void buildQueryCapsAtTheProviderClauseLimit()
    {
        // Past MAX_ACCOUNTS from: clauses the provider silently returns zero, so the query is capped and
        // the overflow tail dropped -- the head (core accounts, listed first) is what survives.
        List<String> many = new ArrayList<>();
        for (int i = 1; i <= XSquawkSource.MAX_ACCOUNTS + 3; i++)
        {
            many.add("acct" + i);
        }
        String query = XSquawkSource.buildQuery(many);
        assertEquals("capped at the provider clause limit", XSquawkSource.MAX_ACCOUNTS, query.split(" OR ").length);
        assertTrue("keeps the head of the list", query.startsWith("from:acct1 OR "));
        assertFalse("drops the overflow tail", query.contains("from:acct" + (XSquawkSource.MAX_ACCOUNTS + 1)));
    }

    @Test
    public void parseCreatedAtToSortableIso()
    {
        assertEquals("2026-06-08T23:58:00Z",
                XSquawkSource.parseCreatedAt("Mon Jun 08 23:58:00 +0000 2026"));
        assertEquals("an unparseable timestamp maps to empty", "",
                XSquawkSource.parseCreatedAt("not-a-date"));
        assertEquals("", XSquawkSource.parseCreatedAt(""));
    }

    @Test
    public void toArticleNormalizesTweetWithQuotedHeadline() throws Exception
    {
        JsonNode tweet = Json.parse("{"
                + "\"id\":\"1\",\"text\":\"BREAKING: SEC sues a top exchange\","
                + "\"url\":\"https://x.com/DeItaone/status/1\","
                + "\"createdAt\":\"Mon Jun 08 23:58:00 +0000 2026\","
                + "\"author\":{\"userName\":\"DeItaone\",\"name\":\"*Walter Bloomberg\"},"
                + "\"quoted_tweet\":{\"text\":\"original filing details here\"},"
                + "\"retweeted_tweet\":null}");

        ObjectNode article = XSquawkSource.toArticle(tweet);
        assertEquals("@DeItaone", article.path("source").path("name").asText());
        assertEquals("*Walter Bloomberg", article.path("author").asText());
        assertEquals("BREAKING: SEC sues a top exchange", article.path("title").asText());
        assertEquals("the quoted tweet's text is surfaced as the description",
                "original filing details here", article.path("description").asText());
        assertEquals("https://x.com/DeItaone/status/1", article.path("url").asText());
        assertEquals("2026-06-08T23:58:00Z", article.path("publishedAt").asText());
    }

    @Test
    public void toArticleOriginalPostHasNoDescriptionAndUnparseableIsDropped() throws Exception
    {
        JsonNode original = Json.parse("{"
                + "\"text\":\"*FED'S POWELL: ...\",\"url\":\"https://x.com/FirstSquawk/status/2\","
                + "\"createdAt\":\"Tue Jun 09 12:00:00 +0000 2026\","
                + "\"author\":{\"userName\":\"FirstSquawk\",\"name\":\"First Squawk\"},"
                + "\"quoted_tweet\":null,\"retweeted_tweet\":null}");
        assertEquals("an original post carries no underlying-headline description",
                "", XSquawkSource.toArticle(original).path("description").asText());

        JsonNode undated = Json.parse("{\"text\":\"x\",\"createdAt\":\"\",\"author\":{}}");
        assertNull("a tweet with an unparseable timestamp is skipped", XSquawkSource.toArticle(undated));
    }

    @Test
    public void articlesFromAppliesSourceWatermark() throws Exception
    {
        JsonNode response = Json.parse("{\"tweets\":["
                + "{\"text\":\"newer\",\"createdAt\":\"Mon Jun 08 23:58:00 +0000 2026\",\"author\":{\"userName\":\"A\"}},"
                + "{\"text\":\"older\",\"createdAt\":\"Mon Jun 08 23:50:00 +0000 2026\",\"author\":{\"userName\":\"A\"}}"
                + "]}");

        List<ObjectNode> all = XSquawkSource.articlesFrom(response, "");
        assertEquals("no watermark keeps both tweets", 2, all.size());

        List<ObjectNode> fresh = XSquawkSource.articlesFrom(response, "2026-06-08T23:55:00Z");
        assertEquals("the watermark drops the at-or-below tweet", 1, fresh.size());
        assertTrue("only the newer tweet survives", fresh.get(0).path("title").asText().equals("newer"));
    }

    @Test
    public void reachedWatermarkStopsWhenOldestTweetIsAtOrBelow() throws Exception
    {
        // tweets are newest-first, so the last element is the oldest -- the boundary the walk tests.
        JsonNode page = Json.parse("{\"tweets\":["
                + "{\"createdAt\":\"Mon Jun 08 23:58:00 +0000 2026\",\"author\":{}},"
                + "{\"createdAt\":\"Mon Jun 08 23:50:00 +0000 2026\",\"author\":{}}"
                + "]}");
        assertTrue("oldest (23:50) at/below the watermark -> reached, stop paging",
                XSquawkSource.reachedWatermark(page, "2026-06-08T23:55:00Z"));
        assertFalse("oldest (23:50) above the watermark -> more new may remain, keep paging",
                XSquawkSource.reachedWatermark(page, "2026-06-08T23:40:00Z"));
    }

    @Test
    public void reachedWatermarkColdStartAndEmptyPage() throws Exception
    {
        JsonNode page = Json.parse("{\"tweets\":[{\"createdAt\":\"Mon Jun 08 23:58:00 +0000 2026\",\"author\":{}}]}");
        assertFalse("no watermark (cold start) never reports reached -- the page cap bounds it instead",
                XSquawkSource.reachedWatermark(page, ""));
        assertTrue("an empty page stops the walk",
                XSquawkSource.reachedWatermark(Json.parse("{\"tweets\":[]}"), "2026-06-08T23:55:00Z"));
    }

    @Test
    public void morePagesRequiresHasMoreCursorAndUnreachedWatermark() throws Exception
    {
        String tweets = "\"tweets\":[{\"createdAt\":\"Mon Jun 08 23:58:00 +0000 2026\",\"author\":{}}]";
        assertTrue("has_more + cursor + above watermark -> page on",
                XSquawkSource.morePages(Json.parse("{\"has_more\":true,\"next_cursor\":\"c\"," + tweets + "}"),
                        "2026-06-08T23:00:00Z"));
        assertFalse("has_more false stops",
                XSquawkSource.morePages(Json.parse("{\"has_more\":false,\"next_cursor\":\"c\"," + tweets + "}"),
                        "2026-06-08T23:00:00Z"));
        assertFalse("an empty cursor stops",
                XSquawkSource.morePages(Json.parse("{\"has_more\":true,\"next_cursor\":\"\"," + tweets + "}"),
                        "2026-06-08T23:00:00Z"));
        assertFalse("reaching the watermark stops even with has_more",
                XSquawkSource.morePages(Json.parse("{\"has_more\":true,\"next_cursor\":\"c\"," + tweets + "}"),
                        "2026-06-08T23:58:00Z"));
    }
}
