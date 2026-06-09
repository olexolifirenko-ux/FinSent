package com.finsent.collect.source;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.core.Http;
import com.finsent.core.Json;
import com.finsent.util.GlobalSystem;

/**
 * CryptoPanic news source (ports Python {@code collect._fetch_cryptopanic}). Fetches public BTC
 * news posts, normalizes the publication timestamp to the canonical UTC form, applies the
 * per-source watermark cutoff, and maps each post to the common article shape.
 */
public final class CryptoPanicSource implements IArticleSource
{
    private static final String NAME = "cryptopanic";
    private static final String URL = "https://cryptopanic.com/api/v1/posts/";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final String apiKey_;

    public CryptoPanicSource(String apiKey)
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
        try
        {
            JsonNode results = Json.parse(Http.get(URL, Map.of(
                    "auth_token", apiKey_, "currencies", "BTC", "kind", "news", "public", "true"),
                    null, TIMEOUT)).path("results");
            if (results.isArray())
            {
                for (JsonNode item : results)
                {
                    String pubIso = normalizePublished(item.path("published_at").asText(""));
                    if (fromTs.isEmpty() || pubIso.compareTo(fromTs) > 0)
                    {
                        articles.add(toArticle(item, pubIso));
                    }
                }
            }
        }
        catch (IOException | RuntimeException fetchFailed)
        {
            GlobalSystem.warning().writes(NAME, "CryptoPanic error", fetchFailed);
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
            GlobalSystem.warning().writes(NAME, "Interrupted fetching CryptoPanic", interrupted);
        }
        return articles;
    }

    /** Normalize {@code "YYYY-MM-DD HH:MM:SS"} to {@code "YYYY-MM-DDTHH:MM:SSZ"} (ports the Python form). */
    static String normalizePublished(String raw)
    {
        String iso = (raw == null ? "" : raw).replace(" ", "T");
        if (!iso.endsWith("Z"))
        {
            iso += "Z";
        }
        return iso;
    }

    private static ObjectNode toArticle(JsonNode item, String pubIso)
    {
        JsonNode sourceNode = item.path("source");
        ObjectNode source = Json.newObject();
        source.putNull("id");
        source.put("name", sourceNode.path("title").asText("CryptoPanic"));

        String url = item.path("url").asText("");
        if (url.isEmpty())
        {
            url = sourceNode.path("url").asText("");
        }

        ObjectNode article = Json.newObject();
        article.set("source", source);
        article.put("author", "");
        article.put("title", item.path("title").asText("").trim());
        article.put("description", "");
        article.put("url", url);
        article.put("urlToImage", "");
        article.put("publishedAt", pubIso);
        article.put("content", "");
        return article;
    }
}
