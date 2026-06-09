package com.finsent.collect.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.finsent.core.Http;

/**
 * Verifies {@link RssSource}'s concurrency cap (the fetch pool is bounded to MAX_CONCURRENT_FETCHES
 * so a many-feed cold-connect burst is staggered instead of storming the shared HttpClient selector)
 * and its conditional-GET validator handling (ETag / Last-Modified replayed as If-None-Match /
 * If-Modified-Since, kept across a 304).
 */
public class RssSource_utest
{
    @Test
    public void poolSizeIsBoundedAndAtLeastOne()
    {
        assertEquals("empty feed list still gets a usable pool", 1, RssSource.poolSize(0));
        assertEquals("a single feed needs a single thread", 1, RssSource.poolSize(1));
        assertEquals("below the cap is unchanged", 3, RssSource.poolSize(3));
        assertEquals("at the cap", 4, RssSource.poolSize(4));
        assertEquals("the full 14-feed burst is capped", 4, RssSource.poolSize(14));
    }

    @Test
    public void conditionalHeadersOmitValidatorsUntilRemembered()
    {
        RssSource source = new RssSource(List.of(), Duration.ofSeconds(5), 0, Http.Channel.SHARED);
        Map<String, String> headers = source.conditionalHeaders("rss_Feed");
        assertFalse("no If-None-Match before a response is seen", headers.containsKey("If-None-Match"));
        assertFalse("no If-Modified-Since before a response is seen", headers.containsKey("If-Modified-Since"));
        assertTrue("base browser headers are still present", headers.containsKey("User-Agent"));
    }

    @Test
    public void rememberedValidatorsAreReplayedAndSurvive304()
    {
        RssSource source = new RssSource(List.of(), Duration.ofSeconds(5), 0, Http.Channel.SHARED);
        source.rememberValidators("rss_Feed",
                new Http.Response(200, "<rss/>", "\"etag-1\"", "Mon, 08 Jun 2026 10:36:00 GMT"));

        Map<String, String> headers = source.conditionalHeaders("rss_Feed");
        assertEquals("\"etag-1\"", headers.get("If-None-Match"));
        assertEquals("Mon, 08 Jun 2026 10:36:00 GMT", headers.get("If-Modified-Since"));

        // A 304 carries no fresh validators -- the cached ones must be kept for the next request.
        source.rememberValidators("rss_Feed", new Http.Response(304, null, "", ""));
        Map<String, String> afterNotModified = source.conditionalHeaders("rss_Feed");
        assertEquals("\"etag-1\"", afterNotModified.get("If-None-Match"));
        assertEquals("Mon, 08 Jun 2026 10:36:00 GMT", afterNotModified.get("If-Modified-Since"));
    }

    @Test
    public void perFeedFailureStreakCountsAndResetsOnSuccess()
    {
        // The recoverable circuit-breaker: failures accumulate per feed (driving the warning -> error ->
        // debug log tiers); the feed is still probed past the threshold, and a single success clears it.
        RssSource source = new RssSource(List.of(), Duration.ofSeconds(5), 0, Http.Channel.SHARED);
        IOException timedOut = new IOException("request timed out");

        for (int attempt = 1; attempt <= RssSource.FAILURE_THRESHOLD; attempt++)
        {
            source.recordFailure("Flaky", timedOut);
            assertEquals(attempt, source.consecutiveFailures("Flaky"));
        }
        // Crossing the threshold keeps counting -- the excluded feed is still probed each cycle.
        source.recordFailure("Flaky", timedOut);
        assertEquals(RssSource.FAILURE_THRESHOLD + 1, source.consecutiveFailures("Flaky"));

        // One success clears the streak -> the feed is re-included.
        source.recordSuccess("Flaky");
        assertEquals(0, source.consecutiveFailures("Flaky"));
    }
}
