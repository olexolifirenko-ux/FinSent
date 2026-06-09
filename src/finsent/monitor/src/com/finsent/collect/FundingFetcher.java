package com.finsent.collect;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.core.Http;
import com.finsent.core.Json;
import com.finsent.core.Num;
import com.finsent.core.Times;
import com.finsent.util.GlobalSystem;

/**
 * Binance USD-M perpetual funding-rate fetcher (BL#2a). Reads the public (no-auth) futures
 * premium-index endpoint and builds the per-interval snapshot the collector stores:
 * {@code {ts, funding_rate, mark_price}}. The funding rate is the 8-hourly rate longs pay shorts
 * (positive) or shorts pay longs (negative); the mechanical positioning signal over a snapshot lives
 * in {@code com.finsent.analyse.signal.FundingSignals}. Fetch-only &mdash; no interpretation here.
 */
public final class FundingFetcher
{
    private static final String NAME = "FundingFetcher";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final String SYMBOL = "BTCUSDT";

    private final String baseUrl_;

    public FundingFetcher(String binanceFuturesBaseUrl)
    {
        baseUrl_ = binanceFuturesBaseUrl;
    }

    /** Fetch the current funding snapshot, or null on failure. Mirrors {@code OptionsFetcher.fetchSnapshot}. */
    public ObjectNode fetchSnapshot()
    {
        ObjectNode snapshot = null;
        try
        {
            String body = Http.get(baseUrl_ + "/premiumIndex", Map.of("symbol", SYMBOL), null, TIMEOUT);
            snapshot = buildSnapshot(Instant.now(), Json.parse(body));
        }
        catch (IOException | RuntimeException fetchFailed)
        {
            GlobalSystem.warning().writes(NAME, "Failed to fetch funding rate", fetchFailed);
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
            GlobalSystem.warning().writes(NAME, "Interrupted fetching funding rate", interrupted);
        }
        return snapshot;
    }

    /**
     * Build the snapshot from the premiumIndex response, or null when the funding rate is absent.
     * Binance returns the rate as a decimal string (e.g. {@code "0.00010000"} = 0.01% per 8h).
     * {@code now} is injected for deterministic timestamps in tests.
     */
    static ObjectNode buildSnapshot(Instant now, JsonNode premiumIndex)
    {
        ObjectNode snapshot = null;
        String rate = text(premiumIndex, "lastFundingRate");
        if (!rate.isEmpty())
        {
            snapshot = Json.newObject();
            snapshot.put("ts", Times.formatUtcIso(now));
            snapshot.put("funding_rate", Double.parseDouble(rate));
            String mark = text(premiumIndex, "markPrice");
            if (!mark.isEmpty())
            {
                snapshot.put("mark_price", Num.round(Double.parseDouble(mark), 2));
            }
        }
        return snapshot;
    }

    private static String text(JsonNode node, String field)
    {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }
}
