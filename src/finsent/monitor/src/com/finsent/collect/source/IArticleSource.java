package com.finsent.collect.source;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A configured article source (NewsAPI, Polygon, CryptoPanic, or RSS). A source fetches and
 * normalizes articles into the common shape {@code {source:{id,name}, author, title, description,
 * url, urlToImage, publishedAt, content}} (RSS additionally sets {@code _rss_feed}); it does NOT
 * set {@code _source}/{@code id}/{@code hash}/{@code collected_at} (the cycle and the
 * {@code ArticleRegistry} add those). Ports the {@code _fetch_*} functions of Python {@code
 * collect.py}.
 *
 * <p>The whole per-source watermark map is passed to every source (the Python {@code from_ts_map}
 * contract): scalar sources read {@code fromTsMap.get(name())}, while RSS reads a per-feed key
 * {@code rss_<feed>}.
 */
public interface IArticleSource
{
    /** The source key used for the watermark map and {@code _source} attribution. */
    String name();

    /** Fetch and normalize the source's current articles; never null (empty on failure). */
    List<ObjectNode> fetch(Map<String, String> fromTsMap);
}
