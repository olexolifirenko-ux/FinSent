package com.finsent.core.io;

/**
 * The set of persisted data streams and their on-disk layout. Each stream maps to a family of
 * per-day files named {@code <prefix><YYYYMMDD><suffix>} in a single serialization format. This
 * is the one place the file layout lives: the {@link PersistenceService} maps a stream to paths
 * and the read/write format, and the registries reference a stream only as an identity tag for
 * the data they load and the {@link WriteUnit}s they emit &mdash; they never touch paths or files.
 */
public enum DataStream
{
    /** Collected articles, one JSONL file per day (one article object per line). */
    ARTICLES("articles_", ".jsonl", Format.JSONL),
    /** Macro context snapshots, one JSON object per day keyed by {@code HH:MM}. */
    MACRO("macro_context_", ".json", Format.JSON),
    /** Options context snapshots, one JSON object per day keyed by {@code HH:MM}. */
    OPTIONS("options_context_", ".json", Format.JSON),
    /** BTC OHLC bars, one JSONL file per day (one {@code {ts,o,h,l,c,v}} bar per line, sorted/deduped). */
    OHLC("btc_price_", ".jsonl", Format.JSONL),
    /** BTC price-context snapshots (1h/24h change + 24h range position), one object per day keyed by {@code HH:MM}. */
    PRICE_CONTEXT("price_context_", ".json", Format.JSON),
    /** Perpetual funding-rate snapshots, one JSON object per day keyed by {@code HH:MM}. */
    FUNDING("funding_", ".json", Format.JSON),
    /** Resolved scheduled-economic actuals, one JSON object per day keyed by event name. */
    ECON("econ_actuals_", ".json", Format.JSON),
    /** Analyser output, one JSON object per day keyed by {@code HH:MM} (analyser-owned). */
    ANALYSIS("analysis_", ".json", Format.JSON);

    /** On-disk serialization of a stream's per-day file. */
    public enum Format
    {
        /** A single JSON object per file (pretty-printed). */
        JSON,
        /** One compact JSON object per line. */
        JSONL
    }

    private final String prefix_;
    private final String suffix_;
    private final Format format_;

    DataStream(String prefix, String suffix, Format format)
    {
        prefix_ = prefix;
        suffix_ = suffix;
        format_ = format;
    }

    public String prefix()
    {
        return prefix_;
    }

    public String suffix()
    {
        return suffix_;
    }

    public Format format()
    {
        return format_;
    }
}
