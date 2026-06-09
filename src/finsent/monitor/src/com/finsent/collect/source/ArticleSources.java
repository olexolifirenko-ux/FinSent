package com.finsent.collect.source;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.finsent.core.Config;
import com.finsent.core.Http;
import com.finsent.util.GlobalSystem;

/**
 * Builds the configured {@link IArticleSource}s from {@link Config} (ports the source-selection
 * logic of Python {@code collect.fetch_articles}). Sources requiring a key are skipped when the
 * key is missing or a placeholder; RSS needs no key. Order follows the config (it determines
 * which source wins on cross-source duplicates downstream).
 */
public final class ArticleSources
{
    private static final String NAME = "ArticleSources";
    private static final String PLACEHOLDER_PREFIX = "YOUR_";

    // Regular RSS keeps the default fetch policy (every-10-min collector tolerates retries/backoff).
    private static final Duration REGULAR_TIMEOUT = Duration.ofSeconds(15);
    private static final int REGULAR_RETRIES = 3;
    // Urgent RSS favours latency: a short timeout and no retry so a flaky feed fails fast and the
    // next poll (seconds away) retries, instead of stalling the lane on backoff sleeps.
    private static final Duration URGENT_TIMEOUT = Duration.ofSeconds(5);
    private static final int URGENT_RETRIES = 0;

    private ArticleSources()
    {
    }

    /** The regular collection sources, in config order. */
    public static List<IArticleSource> fromConfig(Config config)
    {
        List<IArticleSource> sources = new ArrayList<>();
        for (Config.Source entry : config.sources())
        {
            IArticleSource source = build(entry, config);
            if (source != null)
            {
                sources.add(source);
            }
        }
        return sources;
    }

    /** The urgent poller's single RSS source over the urgent feeds. */
    public static IArticleSource urgentFromConfig(Config config)
    {
        return new RssSource(config.urgentSources(), URGENT_TIMEOUT, URGENT_RETRIES, Http.Channel.URGENT);
    }

    private static IArticleSource build(Config.Source entry, Config config)
    {
        String name = entry.name() == null ? "" : entry.name().trim().toLowerCase(Locale.ROOT);
        String apiKey = entry.apiKey() == null ? "" : entry.apiKey().trim();
        IArticleSource source = null;
        switch (name)
        {
            case "rss":
                source = new RssSource(config.rssFeeds(), REGULAR_TIMEOUT, REGULAR_RETRIES, Http.Channel.SHARED);
                break;
            case "newsapi":
                source = keyConfigured(name, apiKey) ? new NewsApiSource(apiKey) : null;
                break;
            case "polygon":
                source = keyConfigured(name, apiKey) ? new PolygonSource(apiKey) : null;
                break;
            case "cryptopanic":
                source = keyConfigured(name, apiKey) ? new CryptoPanicSource(apiKey) : null;
                break;
            default:
                GlobalSystem.warning().writes(NAME, "Skipping unknown source: '" + name + "'");
        }
        return source;
    }

    private static boolean keyConfigured(String name, String apiKey)
    {
        boolean configured = !apiKey.isEmpty() && !apiKey.startsWith(PLACEHOLDER_PREFIX);
        if (!configured)
        {
            GlobalSystem.info().writes(NAME, "Skipping '" + name + "': api_key not configured");
        }
        return configured;
    }
}
