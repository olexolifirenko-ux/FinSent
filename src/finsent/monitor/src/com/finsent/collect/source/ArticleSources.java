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

    /**
     * The urgent poller's sources: the RSS squawk feeds plus the X (Twitter) amplifier source when its
     * GetXAPI key and account list are configured (skipped cleanly otherwise, like the keyed sources).
     */
    public static List<IArticleSource> urgentSourcesFromConfig(Config config)
    {
        List<IArticleSource> sources = new ArrayList<>();
        sources.add(new RssSource(config.urgentSources(), URGENT_TIMEOUT, URGENT_RETRIES, Http.Channel.URGENT));
        IArticleSource x = buildX(config);
        if (x != null)
        {
            sources.add(x);
        }
        return sources;
    }

    /**
     * A human-readable manifest of the configured sources across both lanes -- for the startup log and the
     * {@code collect list} command. Mirrors {@link #build}'s key check, so a keyed source reads on/off exactly
     * as the collector subscribes it. One line per category; never empty.
     */
    public static List<String> describe(Config config)
    {
        List<String> lines = new ArrayList<>();
        lines.add("Regular API: " + regularApiSummary(config));
        lines.add("Regular RSS (10-min): " + feedNames(config.rssFeeds()));
        lines.add("Urgent RSS (" + config.urgentPollInSec() + "s): " + feedNames(config.urgentSources()));
        lines.add("X (Twitter): " + xSummary(config));
        return lines;
    }

    /** The {@code <Sources>} keyed APIs (RSS excluded -- it has its own line), each marked on / off-no-key. */
    private static String regularApiSummary(Config config)
    {
        List<String> parts = new ArrayList<>();
        for (Config.Source entry : config.sources())
        {
            String name = entry.name() == null ? "" : entry.name().trim().toLowerCase(Locale.ROOT);
            if (!name.isEmpty() && !name.equals("rss"))
            {
                parts.add(name + (hasKey(entry.apiKey()) ? " (on)" : " (off: no key)"));
            }
        }
        return parts.isEmpty() ? "(none)" : String.join(", ", parts);
    }

    private static String feedNames(List<Config.Feed> feeds)
    {
        List<String> names = new ArrayList<>();
        for (Config.Feed feed : feeds)
        {
            names.add(feed.name());
        }
        return names.isEmpty() ? "(none)" : String.join(", ", names);
    }

    private static String xSummary(Config config)
    {
        int core = config.xAccounts().size();
        int situational = config.xSituationalAccounts().size();
        String state = hasKey(config.getxapiKey()) && core + situational > 0 ? "key configured" : "not configured";
        return core + " core + " + situational + " situational handle(s) -- " + state;
    }

    /**
     * The X amplifier source over the core + situational accounts (merged into the one squawk query),
     * or null when the key or both account lists are absent. Core is listed first so that if the
     * combined list overruns the provider's clause cap, the situational tail is what gets dropped.
     */
    private static IArticleSource buildX(Config config)
    {
        String apiKey = config.getxapiKey().trim();
        List<String> accounts = new ArrayList<>(config.xAccounts());
        accounts.addAll(config.xSituationalAccounts());
        IArticleSource source = null;
        if (keyConfigured("x", apiKey) && !accounts.isEmpty())
        {
            source = new XSquawkSource(config.getxapiSearchUrl(), apiKey, accounts, URGENT_TIMEOUT, URGENT_RETRIES,
                    config.getxapiMaxPages());
        }
        return source;
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
        boolean configured = hasKey(apiKey);
        if (!configured)
        {
            GlobalSystem.info().writes(NAME, "Skipping '" + name + "': api_key not configured");
        }
        return configured;
    }

    /** Whether an api key is present and not a placeholder -- the pure check behind {@link #keyConfigured}. */
    private static boolean hasKey(String apiKey)
    {
        String key = apiKey == null ? "" : apiKey.trim();
        return !key.isEmpty() && !key.startsWith(PLACEHOLDER_PREFIX);
    }
}
