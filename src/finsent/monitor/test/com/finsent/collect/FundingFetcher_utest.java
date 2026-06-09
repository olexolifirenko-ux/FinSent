package com.finsent.collect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.Test;

import com.finsent.core.Json;

/**
 * Verifies {@link FundingFetcher#buildSnapshot}: parsing Binance's string-encoded premiumIndex into
 * {@code {ts, funding_rate, mark_price}}, and a null snapshot when the funding rate is absent.
 */
public class FundingFetcher_utest
{
    private static final Instant NOW = Instant.parse("2026-06-09T08:00:00Z");

    @Test
    public void buildsSnapshotFromPremiumIndex() throws Exception
    {
        JsonNode body = Json.parse("{\"symbol\":\"BTCUSDT\",\"markPrice\":\"64250.50000000\","
                + "\"lastFundingRate\":\"0.00038000\",\"nextFundingTime\":1000}");

        ObjectNode snapshot = FundingFetcher.buildSnapshot(NOW, body);

        assertEquals("2026-06-09T08:00:00Z", snapshot.path("ts").asText());
        assertEquals(0.00038, snapshot.path("funding_rate").asDouble(), 1e-9);
        assertEquals(64250.50, snapshot.path("mark_price").asDouble(), 1e-9);
    }

    @Test
    public void nullSnapshotWhenRateAbsent() throws Exception
    {
        assertNull(FundingFetcher.buildSnapshot(NOW, Json.parse("{\"symbol\":\"BTCUSDT\"}")));
    }
}
