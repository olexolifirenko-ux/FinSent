package com.finsent.collect.source;

import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.core.Http;
import com.finsent.core.Json;
import com.finsent.core.Times;
import com.finsent.util.GlobalSystem;

/**
 * Fast X (Twitter) amplifier source: polls a curated set of breaking-news accounts (e.g. {@code
 * @DeItaone}, {@code @FirstSquawk}) through the GetXAPI advanced-search endpoint and normalizes each
 * tweet into the common article shape. All accounts are fetched together by OR-ing them into one
 * {@code from:a OR from:b ...} query (newest-first); each poll then pages backward through the
 * {@code next_cursor} until it reaches the last-seen watermark (or the {@code maxPages} guard), so a
 * burst larger than one 20-tweet page is not silently truncated.
 *
 * <p>Runs on the urgent {@link Http.Channel} with the urgent lane's fail-fast timeout/no-retry policy
 * (the next poll, seconds away, retries). A tweet's own {@code text} becomes the title; a quote- or
 * retweet's underlying text becomes the description so the screener sees the amplified headline. The
 * source carries no per-account watermark of its own &mdash; it tags every article with {@code
 * _source=}{@value #NAME} and lets the collector apply the single newest-seen watermark and content
 * de-duplication (the same contract the scalar sources use).
 */
public final class XSquawkSource implements IArticleSource
{
    private static final String NAME = "x";
    // GetXAPI returns Twitter's native created_at, e.g. "Mon Jun 08 23:58:00 +0000 2026".
    private static final DateTimeFormatter TWITTER_TS =
            DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss Z yyyy", Locale.ENGLISH);
    // advanced_search "product": "Latest" returns the most recent tweets (vs the relevance-ranked "Top").
    private static final String PRODUCT = "Latest";
    private static final int MAX_DESC = 500;
    // GetXAPI advanced_search silently returns ZERO tweets once a query carries more than this many
    // from: clauses (no error -- a total blackout), so the merged account list is capped here.
    static final int MAX_ACCOUNTS = 25;

    private final String searchUrl_;
    private final String apiKey_;
    private final String query_;
    private final Duration timeout_;
    private final int maxRetries_;
    // Backward-walk page cap per poll (~20 tweets/page): bounds a cold-start catch-up / runaway burst.
    // From FSCollector getxapiMaxPages (default 5 = ~100 tweets/poll); see Config.getxapiMaxPages.
    private final int maxPages_;
    // Runtime on/off (the `collect x on|off` command). Starts off; FSApp sets the initial state from
    // the -DfetchX launcher flag. Volatile: the cmd thread writes it, the urgent poll thread reads it.
    private volatile boolean enabled_;

    public XSquawkSource(String searchUrl, String apiKey, List<String> accounts, Duration timeout, int maxRetries,
            int maxPages)
    {
        searchUrl_ = searchUrl;
        apiKey_ = apiKey;
        query_ = buildQuery(accounts);
        timeout_ = timeout;
        maxRetries_ = maxRetries;
        maxPages_ = maxPages;
    }

    /**
     * OR the accounts into one {@code from:a OR from:b ...} query (tolerating a leading {@code @}),
     * capped at {@link #MAX_ACCOUNTS} clauses -- past that the provider silently returns nothing.
     */
    static String buildQuery(List<String> accounts)
    {
        StringBuilder query = new StringBuilder();
        for (String handle : capHandles(normalize(accounts)))
        {
            query.append(query.length() == 0 ? "" : " OR ").append("from:").append(handle);
        }
        return query.toString();
    }

    /** Trim, drop a leading {@code @}, and skip blanks -- the clean handle list, order preserved. */
    private static List<String> normalize(List<String> accounts)
    {
        List<String> handles = new ArrayList<>();
        for (String account : accounts)
        {
            String handle = account == null ? "" : account.trim();
            handle = handle.startsWith("@") ? handle.substring(1) : handle;
            if (!handle.isEmpty())
            {
                handles.add(handle);
            }
        }
        return handles;
    }

    /** Cap to the provider's clause limit, loudly warning and dropping the overflow (the list tail). */
    private static List<String> capHandles(List<String> handles)
    {
        List<String> capped = handles;
        if (handles.size() > MAX_ACCOUNTS)
        {
            GlobalSystem.warning().writes(NAME, "X account list has " + handles.size() + " handles but GetXAPI "
                    + "advanced_search caps at " + MAX_ACCOUNTS + " from: clauses (it silently returns nothing "
                    + "past that) -- following the first " + MAX_ACCOUNTS + ", dropping: "
                    + handles.subList(MAX_ACCOUNTS, handles.size()));
            capped = handles.subList(0, MAX_ACCOUNTS);
        }
        return capped;
    }

    @Override
    public String name()
    {
        return NAME;
    }

    /** Turn polling on/off at runtime; off means no fetch and no GetXAPI call at all. */
    public void setEnabled(boolean enabled)
    {
        enabled_ = enabled;
    }

    /** Whether the source is currently polling. */
    public boolean isEnabled()
    {
        return enabled_;
    }

    @Override
    public List<ObjectNode> fetch(Map<String, String> fromTsMap)
    {
        List<ObjectNode> articles = new ArrayList<>();
        if (enabled_ && !query_.isEmpty())
        {
            articles = fetchArticles(fromTsMap.getOrDefault(NAME, ""));
        }
        return articles;
    }

    /**
     * Page the merged-account query backward (newest-first), accumulating every tweet past the source
     * watermark: follow {@code next_cursor} until the API has no more, a page reaches the watermark, or
     * the {@code maxPages} guard trips -- so a burst larger than one 20-tweet page is not truncated.
     */
    private List<ObjectNode> fetchArticles(String fromTs)
    {
        List<ObjectNode> articles = new ArrayList<>();
        boolean more = true;
        String cursor = "";
        int page = 0;
        while (more && page < maxPages_)
        {
            JsonNode response = fetchPage(cursor);
            if (response == null)
            {
                more = false;
            }
            else
            {
                articles.addAll(articlesFrom(response, fromTs));
                cursor = response.path("next_cursor").asText("");
                more = morePages(response, fromTs);
            }
            page++;
        }
        warnIfTruncated(more);
        // Per-cycle observability (mirrors the RSS lane's debug lines): show the squawk poll ran and its
        // yield, so a quiet but healthy X lane is visible -- not just the warning when it fails.
        GlobalSystem.debug().writes(NAME, articles.isEmpty()
                ? "squawk poll -- no new tweets"
                : "squawk poll -- " + articles.size() + " new tweet(s) over " + page + " page(s)");
        return articles;
    }

    /** One advanced_search page (the first when {@code cursor} is empty), or null when the call fails. */
    private JsonNode fetchPage(String cursor)
    {
        JsonNode response = null;
        try
        {
            Map<String, String> params = cursor.isEmpty()
                    ? Map.of("q", query_, "product", PRODUCT)
                    : Map.of("q", query_, "product", PRODUCT, "cursor", cursor);
            String body = Http.get(Http.Channel.URGENT, searchUrl_, params,
                    Map.of("Authorization", "Bearer " + apiKey_), timeout_, maxRetries_);
            response = Json.parse(body);
        }
        catch (IOException | RuntimeException fetchFailed)
        {
            // Log the deepest cause: an HTTP status error already carries "HTTP <code>" in its message,
            // but a timeout/connection failure is wrapped as "... failed after retries" with the real
            // cause nested -- surfacing the root makes a rate-limit (HTTP 429) distinguishable from a
            // timeout (e.g. HttpTimeoutException) at a glance.
            GlobalSystem.warning().writes(NAME, "X squawk fetch failed: " + rootCause(fetchFailed));
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
            GlobalSystem.error().writes(NAME, "Interrupted fetching X squawk", interrupted);
        }
        return response;
    }

    /** Continue paging only while the API has more, hands back a cursor, and we haven't hit the watermark. */
    static boolean morePages(JsonNode response, String fromTs)
    {
        return response.path("has_more").asBoolean(false)
                && !response.path("next_cursor").asText("").isEmpty()
                && !reachedWatermark(response, fromTs);
    }

    /**
     * Whether this page has reached already-seen territory: its oldest (last, newest-first) tweet is at
     * or below the watermark, so the next page would be all duplicates. With no watermark (cold start)
     * this stays false and the {@code maxPages} cap bounds the backward walk instead.
     */
    static boolean reachedWatermark(JsonNode response, String fromTs)
    {
        JsonNode tweets = response.path("tweets");
        boolean reached = tweets.size() == 0;
        if (!reached && !fromTs.isEmpty())
        {
            String oldest = parseCreatedAt(tweets.path(tweets.size() - 1).path("createdAt").asText(""));
            reached = !oldest.isEmpty() && oldest.compareTo(fromTs) <= 0;
        }
        return reached;
    }

    /** Warn when the page cap stopped a still-growing sweep -- a sign the poll interval is too long. */
    private void warnIfTruncated(boolean more)
    {
        if (more)
        {
            GlobalSystem.warning().writes(NAME, "X squawk hit the " + maxPages_ + "-page cap with more "
                    + "results pending; the oldest tweets this poll may be truncated -- shorten urgentPollInSec");
        }
    }

    /** The deepest cause of a failure, so a wrapped timeout/connection error is visible (not just "failed after retries"). */
    private static Throwable rootCause(Throwable error)
    {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root)
        {
            root = root.getCause();
        }
        return root;
    }

    /** Map the response's {@code tweets[]} to articles, keeping only those past the source watermark. */
    static List<ObjectNode> articlesFrom(JsonNode response, String fromTs)
    {
        List<ObjectNode> articles = new ArrayList<>();
        for (JsonNode tweet : response.path("tweets"))
        {
            ObjectNode article = toArticle(tweet);
            if (article != null && passesWatermark(article.path("publishedAt").asText(""), fromTs))
            {
                articles.add(article);
            }
        }
        return articles;
    }

    /** Normalize one tweet to the common article shape, or null when its timestamp is unparseable. */
    static ObjectNode toArticle(JsonNode tweet)
    {
        String publishedAt = parseCreatedAt(tweet.path("createdAt").asText(""));
        ObjectNode article = null;
        if (!publishedAt.isEmpty())
        {
            article = buildArticle(tweet, publishedAt);
        }
        return article;
    }

    private static ObjectNode buildArticle(JsonNode tweet, String publishedAt)
    {
        JsonNode author = tweet.path("author");
        String handle = author.path("userName").asText("");

        ObjectNode source = Json.newObject();
        source.putNull("id");
        source.put("name", handle.isEmpty() ? "X" : "@" + handle);

        ObjectNode article = Json.newObject();
        article.set("source", source);
        article.put("author", author.path("name").asText(""));
        article.put("title", tweet.path("text").asText(""));
        article.put("description", underlyingText(tweet));
        article.put("url", tweet.path("url").asText(""));
        article.put("urlToImage", "");
        article.put("publishedAt", publishedAt);
        article.put("content", "");
        return article;
    }

    /** The quoted/retweeted tweet's text (the amplified headline), truncated; "" for an original post. */
    private static String underlyingText(JsonNode tweet)
    {
        JsonNode nested = tweet.path("quoted_tweet");
        if (nested.isMissingNode() || nested.isNull())
        {
            nested = tweet.path("retweeted_tweet");
        }
        String text = nested.path("text").asText("");
        return text.length() > MAX_DESC ? text.substring(0, MAX_DESC) : text;
    }

    /** Twitter's {@code created_at} to a sortable UTC ISO timestamp, or "" when unparseable. */
    static String parseCreatedAt(String createdAt)
    {
        String iso = "";
        if (!createdAt.isEmpty())
        {
            try
            {
                iso = Times.formatUtcIso(OffsetDateTime.parse(createdAt, TWITTER_TS).toInstant());
            }
            catch (RuntimeException badDate)
            {
                iso = "";
            }
        }
        return iso;
    }

    private static boolean passesWatermark(String pubIso, String fromTs)
    {
        return fromTs.isEmpty() || pubIso.isEmpty() || pubIso.compareTo(fromTs) > 0;
    }
}
