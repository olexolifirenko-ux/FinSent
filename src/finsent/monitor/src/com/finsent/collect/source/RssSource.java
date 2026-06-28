package com.finsent.collect.source;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.collect.source.RssParser.RssItem;
import com.finsent.core.Config;
import com.finsent.core.Http;
import com.finsent.core.Json;
import com.finsent.util.GlobalSystem;

/**
 * RSS/Atom feed source (ports Python {@code collect._fetch_rss}). Fetches each configured feed,
 * parses it via {@link RssParser}, applies the per-feed watermark cutoff ({@code rss_<feed>}), and
 * maps each entry to the common article shape &mdash; HTML-stripping and truncating the
 * description to 500 chars and tagging {@code _rss_feed}. A Google-News {@code <source>} name
 * overrides the feed name when present.
 *
 * <p>Feeds are fetched concurrently on a daemon pool so the overall fetch latency is the slowest
 * single feed rather than the sum, but concurrency is capped at {@value #MAX_CONCURRENT_FETCHES}:
 * firing every feed's cold TLS connect at once storms the {@link Http} client's single NIO selector
 * and roughly half the connects time out, so connects are staggered in small batches. The
 * per-request timeout and retry budget are injected so the urgent poller can fail fast (see
 * {@code ArticleSources}); the urgent lane additionally runs on its own {@link Http.Channel} so its
 * burst is not starved by the regular collection on the shared selector. A fuller browser
 * {@code User-Agent}/{@code Accept} header set is sent to look like an ordinary client.
 */
public final class RssSource implements IArticleSource
{
    private static final String NAME = "rss";
    private static final Map<String, String> HEADERS = Map.of(
            "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language", "en-US,en;q=0.9");
    private static final int MAX_DESC = 500;
    // Cap on simultaneous feed connects: enough to keep fetch latency near the slowest feed, low
    // enough that the cold-connect burst never storms the shared HttpClient's single selector
    // (empirically ~4 connects complete cleanly where the full 14-feed burst times ~half out).
    private static final int MAX_CONCURRENT_FETCHES = 4;
    // After this many consecutive failures a feed is treated as problematic: one loud error+trace at
    // the crossing, then quiet (debug) while it stays down -- but it is still probed every cycle and
    // re-includes itself on the next success. Resets to 0 on any successful fetch (200 or 304).
    static final int FAILURE_THRESHOLD = 5;

    private final List<Config.Feed> feeds_;
    private final Duration timeout_;
    private final int maxRetries_;
    private final Http.Channel channel_;
    // Per-feed conditional-GET validators (BL#16): the ETag / Last-Modified from each feed's last
    // response, replayed as If-None-Match / If-Modified-Since so an unchanged feed returns a cheap,
    // bodyless 304. Concurrent map: feeds are fetched in parallel, each touching only its own key.
    private final Map<String, String> etagByFeed_ = new ConcurrentHashMap<>();
    private final Map<String, String> lastModifiedByFeed_ = new ConcurrentHashMap<>();
    // Per-feed consecutive-failure count for the recoverable circuit-breaker (see FAILURE_THRESHOLD).
    private final Map<String, Integer> consecutiveFailures_ = new ConcurrentHashMap<>();
    private final ExecutorService executor_;
    private volatile boolean warmed_;

    public RssSource(List<Config.Feed> feeds, Duration timeout, int maxRetries, Http.Channel channel)
    {
        feeds_ = feeds;
        timeout_ = timeout;
        maxRetries_ = maxRetries;
        channel_ = channel;
        executor_ = Executors.newFixedThreadPool(poolSize(feeds.size()), this::daemonThread);
    }

    /** Bounds the fetch pool to {@link #MAX_CONCURRENT_FETCHES} (at least one thread). */
    static int poolSize(int feedCount)
    {
        return Math.max(1, Math.min(feedCount, MAX_CONCURRENT_FETCHES));
    }

    @Override
    public String name()
    {
        return NAME;
    }

    @Override
    public List<ObjectNode> fetch(Map<String, String> fromTsMap)
    {
        // The first fetch runs sequentially to prime the client (one-time selector init + each host's
        // cold connect). Subsequent polls fetch concurrently but bounded to MAX_CONCURRENT_FETCHES:
        // firing every cold connect at once storms the single selector and times the burst out en
        // masse, so connects are staggered into small batches while latency stays near the slowest feed.
        List<ObjectNode> articles;
        if (warmed_)
        {
            articles = fetchConcurrently(fromTsMap);
        }
        else
        {
            articles = fetchSequentially(fromTsMap);
            warmed_ = true;
        }
        return articles;
    }

    private List<ObjectNode> fetchSequentially(Map<String, String> fromTsMap)
    {
        List<ObjectNode> all = new ArrayList<>();
        for (Config.Feed feed : feeds_)
        {
            all.addAll(fetchFeed(feed, fromTsMap));
        }
        return all;
    }

    private List<ObjectNode> fetchConcurrently(Map<String, String> fromTsMap)
    {
        List<Future<List<ObjectNode>>> futures = new ArrayList<>();
        for (Config.Feed feed : feeds_)
        {
            futures.add(executor_.submit(() -> fetchFeed(feed, fromTsMap)));
        }
        return collect(futures);
    }

    private List<ObjectNode> collect(List<Future<List<ObjectNode>>> futures)
    {
        List<ObjectNode> all = new ArrayList<>();
        for (Future<List<ObjectNode>> future : futures)
        {
            all.addAll(await(future));
        }
        return all;
    }

    private static List<ObjectNode> await(Future<List<ObjectNode>> future)
    {
        List<ObjectNode> result;
        try
        {
            result = future.get();
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
            result = List.of();
        }
        catch (ExecutionException unexpected)
        {
            // fetchFeed handles its own fetch/parse failures; a leaked exception is unexpected.
            GlobalSystem.warning().writes(NAME, "RSS feed task failed", unexpected);
            result = List.of();
        }
        return result;
    }

    private List<ObjectNode> fetchFeed(Config.Feed feed, Map<String, String> fromTsMap)
    {
        String feedKey = "rss_" + feed.name();
        List<ObjectNode> articles = new ArrayList<>();
        try
        {
            Http.Response response = Http.getConditional(channel_, feed.url(),
                    conditionalHeaders(feedKey), timeout_, maxRetries_);
            rememberValidators(feedKey, response);
            articles = articlesFrom(response, feed, feedKey, fromTsMap.getOrDefault(feedKey, ""));
            recordSuccess(feed.name());
        }
        catch (IOException | RuntimeException feedFailed)
        {
            recordFailure(feed.name(), feedFailed);
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
            GlobalSystem.error().writes(NAME, "Interrupted fetching RSS [" + feed.name() + "]", interrupted);
        }
        return articles;
    }

    /** Reset a feed's failure streak; if it was excluded (>= threshold), announce its recovery. */
    void recordSuccess(String feedName)
    {
        Integer failures = consecutiveFailures_.remove(feedName);
        if (failures != null && failures >= FAILURE_THRESHOLD)
        {
            GlobalSystem.info().writes(NAME, "RSS [" + feedName + "] recovered after " + failures
                    + " consecutive failure(s) -- re-included in active feeds.");
        }
    }

    /** The current consecutive-failure count for a feed (0 when healthy/unknown). */
    int consecutiveFailures(String feedName)
    {
        return consecutiveFailures_.getOrDefault(feedName, 0);
    }

    /**
     * Tiered per-feed failure logging (count-any-failure, recoverable). Below the threshold a failure
     * is a one-line {@code warning} with no stack trace (transient network jitter); the crossing fires
     * one {@code error} with a trace and marks the feed excluded; while it stays excluded failures are
     * logged at {@code debug} so a long outage never floods the log. The feed is still probed every
     * cycle, so {@link #recordSuccess} re-includes it as soon as it answers again.
     */
    void recordFailure(String feedName, Throwable cause)
    {
        int failures = consecutiveFailures_.merge(feedName, 1, Integer::sum);
        if (failures < FAILURE_THRESHOLD)
        {
            GlobalSystem.warning().writes(NAME, "RSS [" + feedName + "] fetch failed ("
                    + failures + " consecutive): " + cause);
        }
        else if (failures == FAILURE_THRESHOLD)
        {
            GlobalSystem.error().writes(NAME, "RSS [" + feedName + "] excluded from active feeds after "
                    + FAILURE_THRESHOLD + " consecutive failures (still probed; auto-re-includes on recovery)", cause);
        }
        else
        {
            GlobalSystem.debug().writes(NAME, "RSS [" + feedName + "] still failing ("
                    + failures + " consecutive)");
        }
    }

    /** Parse a fresh response into articles, or log+skip a 304 (the cached feed content is still current). */
    private List<ObjectNode> articlesFrom(Http.Response response, Config.Feed feed, String feedKey, String fromTs)
    {
        List<ObjectNode> articles = new ArrayList<>();
        // 304 (not modified) yields nothing; only a 200 body is parsed. Per-feed outcomes are intentionally
        // not logged -- the FSCollector per-cycle summary ("Urgent poll -- no new articles" / "collected N")
        // is the single line; the startup source manifest (`collect list`) shows what is subscribed.
        if (!response.notModified())
        {
            for (RssItem item : RssParser.parse(response.body()))
            {
                if (passesWatermark(item.pubIso(), fromTs))
                {
                    articles.add(toArticle(item, feed.name(), feedKey));
                }
            }
        }
        return articles;
    }

    /** Base browser headers plus the feed's cached validators (If-None-Match / If-Modified-Since). */
    Map<String, String> conditionalHeaders(String feedKey)
    {
        Map<String, String> headers = new HashMap<>(HEADERS);
        addIfPresent(headers, "If-None-Match", etagByFeed_.get(feedKey));
        addIfPresent(headers, "If-Modified-Since", lastModifiedByFeed_.get(feedKey));
        return headers;
    }

    /** Cache the feed's ETag / Last-Modified for the next conditional GET (only when the server sent them). */
    void rememberValidators(String feedKey, Http.Response response)
    {
        addIfPresent(etagByFeed_, feedKey, response.etag());
        addIfPresent(lastModifiedByFeed_, feedKey, response.lastModified());
    }

    private static void addIfPresent(Map<String, String> map, String key, String value)
    {
        if (value != null && !value.isEmpty())
        {
            map.put(key, value);
        }
    }

    /** Names the fetch-pool threads by lane so the log distinguishes the urgent feeds from the regular ones. */
    private Thread daemonThread(Runnable runnable)
    {
        Thread thread = new Thread(runnable, channel_ == Http.Channel.URGENT ? "FS-Urgent-Rss" : "FS-Rss");
        thread.setDaemon(true);
        return thread;
    }

    private static boolean passesWatermark(String pubIso, String fromTs)
    {
        return fromTs.isEmpty() || pubIso.isEmpty() || pubIso.compareTo(fromTs) > 0;
    }

    /** Build the article, HTML-stripping + truncating the description and tagging the feed. */
    static ObjectNode toArticle(RssItem item, String feedName, String feedKey)
    {
        String sourceName = item.sourceName().isEmpty() ? feedName : item.sourceName();
        String desc = item.desc().replaceAll("<[^>]+>", "");
        if (desc.length() > MAX_DESC)
        {
            desc = desc.substring(0, MAX_DESC);
        }

        ObjectNode source = Json.newObject();
        source.putNull("id");
        source.put("name", sourceName);

        ObjectNode article = Json.newObject();
        article.set("source", source);
        article.put("author", "");
        article.put("title", item.title());
        article.put("description", desc);
        article.put("url", item.url());
        article.put("urlToImage", "");
        article.put("publishedAt", item.pubIso());
        article.put("content", "");
        article.put("_rss_feed", feedKey);
        return article;
    }
}
