package com.finsent.collect.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.finsent.collect.source.RssParser.RssItem;

/**
 * Verifies {@link RssParser} against Python {@code collect._parse_rss_items}/{@code _parse_atom_entries}:
 * RSS + Atom parsing, RFC-822/ISO date normalization, Google-News {@code <source>} handling, and
 * tolerance of malformed feeds. (HTML-strip + truncation is {@link RssSource}'s job, not the parser's.)
 */
public class RssParser_utest
{
    @Test
    public void parsesRssItem()
    {
        // 2026-05-31 is a Sunday (UTC) — RFC-1123 requires a matching day-of-week token.
        String xml = "<rss><channel><item>"
                + "<title>Fed cuts rates</title>"
                + "<link>http://x/1</link>"
                + "<description>&lt;p&gt;Big news&lt;/p&gt;</description>"
                + "<pubDate>Sun, 31 May 2026 12:31:00 GMT</pubDate>"
                + "</item></channel></rss>";

        List<RssItem> items = RssParser.parse(xml);

        assertEquals(1, items.size());
        RssItem item = items.get(0);
        assertEquals("Fed cuts rates", item.title());
        assertEquals("http://x/1", item.url());
        assertEquals("<p>Big news</p>", item.desc()); // raw; HTML strip happens in RssSource
        assertEquals("2026-05-31T12:31:00Z", item.pubIso());
        assertEquals("", item.sourceName());
    }

    @Test
    public void parsesGoogleNewsSourceAndStripsTitleSuffix()
    {
        String xml = "<rss><channel><item>"
                + "<title>Fed cuts rates - CoinDesk</title>"
                + "<link>http://news.google/redirect</link>"
                + "<pubDate>Sun, 31 May 2026 12:31:00 GMT</pubDate>"
                + "<source url=\"http://real/article\">CoinDesk</source>"
                + "</item></channel></rss>";

        RssItem item = RssParser.parse(xml).get(0);

        assertEquals("CoinDesk", item.sourceName());
        assertEquals("http://real/article", item.url()); // <source url> overrides the Google redirect
        assertEquals("Fed cuts rates", item.title());     // " - CoinDesk" suffix stripped
    }

    @Test
    public void parsesAtomEntry()
    {
        String xml = "<feed xmlns=\"http://www.w3.org/2005/Atom\"><entry>"
                + "<title>BTC rallies</title>"
                + "<link rel=\"alternate\" href=\"http://a/1\"/>"
                + "<summary>summary text</summary>"
                + "<published>2026-05-31T12:00:00Z</published>"
                + "</entry></feed>";

        List<RssItem> items = RssParser.parse(xml);

        assertEquals(1, items.size());
        RssItem item = items.get(0);
        assertEquals("BTC rallies", item.title());
        assertEquals("http://a/1", item.url());
        assertEquals("summary text", item.desc());
        assertEquals("2026-05-31T12:00:00Z", item.pubIso());
    }

    @Test
    public void unparseablePubDateYieldsEmptyIso()
    {
        String xml = "<rss><channel><item><title>t</title><link>u</link>"
                + "<pubDate>not a date</pubDate></item></channel></rss>";

        assertEquals("", RssParser.parse(xml).get(0).pubIso());
    }

    @Test
    public void malformedFeedYieldsEmptyList()
    {
        assertTrue(RssParser.parse("this is not <<< xml").isEmpty());
        assertTrue(RssParser.parse((String) null).isEmpty());
    }
}
