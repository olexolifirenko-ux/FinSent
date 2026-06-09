package com.finsent.collect.source;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
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
 * Polygon.io news source (ports Python {@code collect._fetch_polygon}/{@code _normalize_polygon}).
 * Fetches recent BTC news, de-duplicates by Polygon id, normalizes to the common article shape,
 * and returns the 30 most recent by publication time.
 */
public final class PolygonSource implements IArticleSource
{
    private static final String NAME = "polygon";
    private static final String URL = "https://api.polygon.io/v2/reference/news";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_ARTICLES = 30;

    private final String apiKey_;

    public PolygonSource(String apiKey)
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
        String fromTs = fromTsMap.getOrDefault(NAME, "");
        List<ObjectNode> articles = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("apiKey", apiKey_);
        params.put("ticker", "BTC");
        params.put("limit", "10");
        params.put("order", "desc");
        params.put("sort", "published_utc");
        if (!fromTs.isEmpty())
        {
            params.put("published_utc.gte", fromTs);
        }
        try
        {
            JsonNode results = Json.parse(Http.get(URL, params, null, TIMEOUT)).path("results");
            if (results.isArray())
            {
                for (JsonNode item : results)
                {
                    if (seenIds.add(item.path("id").asText("")))
                    {
                        articles.add(normalize(item));
                    }
                }
            }
        }
        catch (IOException | RuntimeException fetchFailed)
        {
            GlobalSystem.warning().writes(NAME, "Polygon fetch error", fetchFailed);
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
            GlobalSystem.warning().writes(NAME, "Interrupted fetching Polygon", interrupted);
        }
        articles.sort(Comparator.comparing((ObjectNode a) -> a.path("publishedAt").asText("")).reversed());
        return articles.size() > MAX_ARTICLES ? new ArrayList<>(articles.subList(0, MAX_ARTICLES)) : articles;
    }

    /** Map a Polygon news item to the common article shape. */
    static ObjectNode normalize(JsonNode item)
    {
        ObjectNode source = Json.newObject();
        source.putNull("id");
        source.put("name", item.path("publisher").path("name").asText("Unknown"));

        ObjectNode article = Json.newObject();
        article.set("source", source);
        article.put("author", item.path("author").asText(""));
        article.put("title", item.path("title").asText(""));
        article.put("description", item.path("description").asText(""));
        article.put("url", item.path("article_url").asText(""));
        article.put("urlToImage", item.path("image_url").asText(""));
        article.put("publishedAt", item.path("published_utc").asText(""));
        article.put("content", "");
        return article;
    }
}
