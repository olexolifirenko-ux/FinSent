package com.finsent.collect.source;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.core.Http;
import com.finsent.core.Json;
import com.finsent.util.GlobalSystem;

/**
 * NewsAPI source (ports Python {@code collect._fetch_newsapi}/{@code _newsapi_query}/
 * {@code _newsapi_filter}). Runs two queries &mdash; crypto and macro &mdash; over a fixed domain
 * allowlist, merges them de-duplicated by URL+title, and drops off-topic articles by URL path.
 * Keeps the raw NewsAPI article shape (which already carries {@code source/author/title/...}).
 */
public final class NewsApiSource implements IArticleSource
{
    private static final String NAME = "newsapi";
    private static final String URL = "https://newsapi.org/v2/everything";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    // The developer NewsAPI quota (100 requests / 24h) cannot sustain the 10-min collector cadence
    // at two requests per poll; gate actual fetches to at most once per hour. A skipped poll costs
    // nothing -- the watermark is unchanged, so the next fetch's `from` window still covers the gap.
    private static final Duration MIN_POLL_INTERVAL = Duration.ofMinutes(60);

    private static final String QUERY_CRYPTO =
            "bitcoin OR BTC OR \"bitcoin ETF\" OR \"bitcoin miner\" OR halving OR "
            + "crypto OR cryptocurrency OR stablecoin OR \"crypto regulation\"";
    private static final String QUERY_MACRO =
            "\"Federal Reserve\" OR \"interest rate\" OR inflation OR CPI OR "
            + "\"bond yield\" OR \"treasury yield\" OR recession OR \"bank failure\" OR "
            + "\"banking crisis\" OR \"stock market crash\" OR \"risk-off\" OR "
            + "sanctions OR \"trade war\" OR tariff OR geopolitical OR "
            + "\"gold price\" OR \"dollar index\" OR \"oil price\"";
    private static final String DOMAINS =
            "coindesk.com,cointelegraph.com,theblock.co,decrypt.co,"
            + "bloomberg.com,reuters.com,cnbc.com,ft.com,wsj.com,apnews.com,bbc.com";
    private static final String[] BLOCKLIST =
            { "/sport/", "/sports/", "/entertainment/", "/travel/", "/lifestyle/" };

    private final String apiKey_;
    private long lastFetchEpochMs_; // 0 until the first fetch -> the first poll always proceeds

    public NewsApiSource(String apiKey)
    {
        apiKey_ = apiKey;
    }

    @Override
    public String name()
    {
        return NAME;
    }

    @Override
    public List<ObjectNode> fetch(Map<String, String> fromTsMap)
    {
        List<ObjectNode> articles = List.of();
        if (pollDue(System.currentTimeMillis()))
        {
            String fromTs = fromTsMap.getOrDefault(NAME, "");
            List<ObjectNode> crypto = query(QUERY_CRYPTO, "crypto", fromTs);
            List<ObjectNode> macro = query(QUERY_MACRO, "macro", fromTs);
            articles = filterBlocklist(mergeDedup(crypto, macro));
        }
        return articles;
    }

    /**
     * NewsAPI quota gate: {@code true} when at least {@link #MIN_POLL_INTERVAL} has elapsed since
     * the last wire fetch (and on the first call), recording {@code nowEpochMs} as the new
     * last-fetch time. The collector polls every source each 10-min cycle, but the developer quota
     * cannot sustain that rate (two requests per poll), so this caps the actual fetch rate. A
     * skipped poll returns no articles and leaves the source watermark unchanged, so the next due
     * poll's {@code from} window still covers the gap -- fewer fetches, no lost articles.
     */
    boolean pollDue(long nowEpochMs)
    {
        boolean due = nowEpochMs - lastFetchEpochMs_ >= MIN_POLL_INTERVAL.toMillis();
        if (due)
        {
            lastFetchEpochMs_ = nowEpochMs;
        }
        return due;
    }

    private List<ObjectNode> query(String queryText, String label, String fromTs)
    {
        List<ObjectNode> articles = new ArrayList<>();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("q", queryText);
        params.put("language", "en");
        params.put("sortBy", "publishedAt");
        params.put("pageSize", "30");
        params.put("domains", DOMAINS);
        if (!fromTs.isEmpty())
        {
            params.put("from", fromTs);
        }
        try
        {
            JsonNode data = Json.parse(Http.get(URL, params, Map.of("X-Api-Key", apiKey_), TIMEOUT));
            if ("ok".equals(data.path("status").asText()))
            {
                collectArticles(data.path("articles"), articles);
            }
            else
            {
                GlobalSystem.warning().writes(NAME, "NewsAPI [" + label + "] error: "
                        + data.path("message").asText("unknown"));
            }
        }
        catch (IOException | RuntimeException requestFailed)
        {
            GlobalSystem.warning().writes(NAME, "NewsAPI [" + label + "] request failed", requestFailed);
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
            GlobalSystem.warning().writes(NAME, "Interrupted fetching NewsAPI [" + label + "]", interrupted);
        }
        return articles;
    }

    private static void collectArticles(JsonNode array, List<ObjectNode> into)
    {
        if (array.isArray())
        {
            for (JsonNode article : array)
            {
                if (article instanceof ObjectNode)
                {
                    into.add((ObjectNode) article);
                }
            }
        }
    }

    /** Merge crypto+macro, keeping the first occurrence per URL+title. */
    static List<ObjectNode> mergeDedup(List<ObjectNode> crypto, List<ObjectNode> macro)
    {
        Set<String> seen = new HashSet<>();
        List<ObjectNode> merged = new ArrayList<>();
        List<ObjectNode> all = new ArrayList<>(crypto);
        all.addAll(macro);
        for (ObjectNode article : all)
        {
            String uid = article.path("url").asText("") + article.path("title").asText("");
            if (seen.add(uid))
            {
                merged.add(article);
            }
        }
        return merged;
    }

    /** Drop articles whose URL path is in the off-topic blocklist. */
    static List<ObjectNode> filterBlocklist(List<ObjectNode> articles)
    {
        List<ObjectNode> kept = new ArrayList<>();
        for (ObjectNode article : articles)
        {
            String url = article.path("url").asText("");
            boolean blocked = false;
            for (String segment : BLOCKLIST)
            {
                if (url.contains(segment))
                {
                    blocked = true;
                    break;
                }
            }
            if (!blocked)
            {
                kept.add(article);
            }
        }
        return kept;
    }
}
