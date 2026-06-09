package com.finsent.collect.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.Test;

import com.finsent.collect.source.RssParser.RssItem;
import com.finsent.core.Json;

/**
 * Verifies the article-normalization helpers of the sources against their Python counterparts:
 * Polygon mapping, NewsAPI merge-dedup + URL-path blocklist, CryptoPanic timestamp normalization,
 * and the RSS HTML-strip + 500-char truncation + feed tagging.
 */
public class SourceNormalization_utest
{
    @Test
    public void polygonNormalizeMapsFields() throws Exception
    {
        String item = "{\"id\":\"abc\",\"publisher\":{\"name\":\"Reuters\"},\"author\":\"Jane\","
                + "\"title\":\"BTC up\",\"description\":\"d\",\"article_url\":\"http://r/1\","
                + "\"image_url\":\"http://r/img\",\"published_utc\":\"2026-05-31T12:00:00Z\"}";

        ObjectNode a = PolygonSource.normalize(Json.parse(item));

        assertEquals("Reuters", a.path("source").path("name").asText());
        assertTrue(a.path("source").path("id").isNull());
        assertEquals("http://r/1", a.path("url").asText());
        assertEquals("http://r/img", a.path("urlToImage").asText());
        assertEquals("2026-05-31T12:00:00Z", a.path("publishedAt").asText());
        assertEquals("", a.path("content").asText());
    }

    @Test
    public void newsApiMergeDedupAndBlocklist()
    {
        ObjectNode a1 = article("http://x/1", "Title A");
        ObjectNode a1dup = article("http://x/1", "Title A");
        ObjectNode a2 = article("http://x/2", "Title B");
        ObjectNode sport = article("http://x/sports/game", "Game recap");

        List<ObjectNode> merged = NewsApiSource.mergeDedup(List.of(a1, a2), List.of(a1dup));
        assertEquals("dedup by url+title keeps first", 2, merged.size());

        List<ObjectNode> kept = NewsApiSource.filterBlocklist(List.of(a1, sport));
        assertEquals(1, kept.size());
        assertEquals("http://x/1", kept.get(0).path("url").asText());
    }

    @Test
    public void cryptoPanicNormalizesTimestamp()
    {
        assertEquals("2026-05-31T12:31:00Z", CryptoPanicSource.normalizePublished("2026-05-31 12:31:00"));
        assertEquals("2026-05-31T12:31:00Z", CryptoPanicSource.normalizePublished("2026-05-31T12:31:00Z"));
    }

    @Test
    public void rssToArticleStripsHtmlTruncatesAndTags()
    {
        String longHtml = "<p>" + "x".repeat(600) + "</p>";
        RssItem item = new RssItem("Title", "http://u", longHtml, "2026-05-31T12:00:00Z", "");

        ObjectNode a = RssSource.toArticle(item, "CoinDesk", "rss_CoinDesk");

        assertEquals("CoinDesk", a.path("source").path("name").asText()); // feed-name fallback
        assertEquals(500, a.path("description").asText().length());       // HTML stripped + truncated
        assertFalse(a.path("description").asText().contains("<"));
        assertEquals("rss_CoinDesk", a.path("_rss_feed").asText());
    }

    @Test
    public void rssToArticlePrefersGoogleNewsSourceName()
    {
        RssItem item = new RssItem("Title", "http://u", "d", "2026-05-31T12:00:00Z", "Bloomberg");
        ObjectNode a = RssSource.toArticle(item, "GoogleNews", "rss_GoogleNews");
        assertEquals("Bloomberg", a.path("source").path("name").asText());
    }

    private static ObjectNode article(String url, String title)
    {
        ObjectNode a = Json.newObject();
        a.put("url", url);
        a.put("title", title);
        return a;
    }
}
