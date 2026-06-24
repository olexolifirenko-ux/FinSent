package com.finsent.trade.broker.whitebit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Verifies {@link WhiteBitClient#parseLastPrice}: pull the configured market's {@code last_price} out of
 * a WhiteBIT public-ticker body, and return null when the market/price is absent or the body is malformed.
 */
public class WhiteBitClient_utest
{
    private static final double EPS = 1e-9;

    @Test
    public void parsesLastPriceForTheConfiguredMarket()
    {
        String body = "{\"BTC_USDT\":{\"last_price\":\"67890.12\",\"base_volume\":\"1\"},"
                + "\"ETH_USDT\":{\"last_price\":\"3000\"}}";
        assertEquals(67890.12, WhiteBitClient.parseLastPrice(body, "BTC_USDT"), EPS);
    }

    @Test
    public void nullWhenMarketAbsent()
    {
        assertNull(WhiteBitClient.parseLastPrice("{\"ETH_USDT\":{\"last_price\":\"3000\"}}", "BTC_USDT"));
    }

    @Test
    public void nullWhenLastPriceMissingOrEmpty()
    {
        assertNull(WhiteBitClient.parseLastPrice("{\"BTC_USDT\":{\"base_volume\":\"1\"}}", "BTC_USDT"));
        assertNull(WhiteBitClient.parseLastPrice("{\"BTC_USDT\":{\"last_price\":\"\"}}", "BTC_USDT"));
    }

    @Test
    public void nullOnMalformedBody()
    {
        assertNull(WhiteBitClient.parseLastPrice("not json", "BTC_USDT"));
    }
}
