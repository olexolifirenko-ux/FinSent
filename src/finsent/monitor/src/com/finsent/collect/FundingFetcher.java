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
 * Binance USD-M perpetual positioning fetcher (BL#2a + open interest). Reads two public (no-auth)
 * futures endpoints &mdash; the premium index (funding rate) and open interest &mdash; into the
 * per-interval snapshot the collector stores: {@code {ts, funding_rate, mark_price, open_interest}}.
 * The funding rate is the 8-hourly rate longs pay shorts (positive) or shorts pay longs (negative);
 * open interest is the total notional of open perpetual contracts (how much leverage is in the
 * system). The mechanical positioning signal that fuses them (crowding + whether leverage is
 * building/unwinding vs the price move) lives in {@code com.finsent.analyse.signal.FundingSignals}.
 * Fetch-only &mdash; no interpretation here. OI is best-effort: its absence just omits that field.
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

    /** Fetch the current funding + open-interest snapshot, or null on failure. */
    public ObjectNode fetchSnapshot()
    {
        ObjectNode snapshot = null;
        try
        {
            String body = Http.get(baseUrl_ + "/premiumIndex", Map.of("symbol", SYMBOL), null, TIMEOUT);
            snapshot = buildSnapshot(Instant.now(), Json.parse(body), fetchOpenInterest());
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

    /** Current open interest, or null on failure (best-effort: a funding snapshot still forms without it). */
    private JsonNode fetchOpenInterest()
    {
        JsonNode openInterest = null;
        try
        {
            openInterest = Json.parse(Http.get(baseUrl_ + "/openInterest", Map.of("symbol", SYMBOL), null, TIMEOUT));
        }
        catch (IOException | RuntimeException fetchFailed)
        {
            GlobalSystem.warning().writes(NAME, "Failed to fetch open interest", fetchFailed);
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
            GlobalSystem.warning().writes(NAME, "Interrupted fetching open interest", interrupted);
        }
        return openInterest;
    }

    /** Funding-only snapshot (open interest omitted) -- the back-compatible 2-arg form. */
    static ObjectNode buildSnapshot(Instant now, JsonNode premiumIndex)
    {
        return buildSnapshot(now, premiumIndex, null);
    }

    /**
     * Build the snapshot from the premiumIndex response, or null when the funding rate is absent.
     * Binance returns the rate as a decimal string (e.g. {@code "0.00010000"} = 0.01% per 8h) and open
     * interest as a contracts string; {@code openInterest} may be null (best-effort). {@code now} is
     * injected for deterministic timestamps in tests.
     */
    static ObjectNode buildSnapshot(Instant now, JsonNode premiumIndex, JsonNode openInterest)
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
            String oi = openInterest == null ? "" : text(openInterest, "openInterest");
            if (!oi.isEmpty())
            {
                snapshot.put("open_interest", Num.round(Double.parseDouble(oi), 2));
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
