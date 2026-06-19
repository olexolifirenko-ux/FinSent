package com.finsent.trade.broker.whitebit;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.core.Http;
import com.finsent.core.Json;

/**
 * Minimal authenticated client for WhiteBIT's private HTTP API V4. Step 1 of the WhiteBIT
 * integration: <b>read-only</b> account reads only (no orders), to validate that the API keys and the
 * HMAC signing are correct before any order code is written. Each call POSTs a signed JSON body
 * ({@code {"request":<path>,"nonce":<ms>, ...}}) with the {@code X-TXC-APIKEY/PAYLOAD/SIGNATURE}
 * headers produced by {@link WhiteBitSigner}; the body posted is byte-identical to the one signed.
 *
 * <p>Nonces are unix-millis, forced strictly increasing across this client so concurrent calls never
 * collide. Reuses the shared {@link Http} client (retry/backoff, raise-on-non-2xx), so a {@code 401}
 * surfaces as an {@link IOException} carrying WhiteBIT's error body for diagnosis.
 */
public final class WhiteBitClient
{
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final String TRADE_BALANCE = "/api/v4/trade-account/balance";
    private static final String COLLATERAL_BALANCE = "/api/v4/collateral-account/balance";
    private static final String COLLATERAL_SUMMARY = "/api/v4/collateral-account/balance-summary";
    private static final String OPEN_POSITIONS = "/api/v4/collateral-account/positions/open";

    private final String apiKey_;
    private final String apiSecret_;
    private final String baseUrl_;
    private final AtomicLong lastNonce_ = new AtomicLong(0);

    public WhiteBitClient(String apiKey, String apiSecret, String baseUrl)
    {
        apiKey_ = apiKey;
        apiSecret_ = apiSecret;
        baseUrl_ = baseUrl;
    }

    /** Whether both API credentials are present (else calls would 401; used to skip the live check). */
    public boolean configured()
    {
        return !apiKey_.isEmpty() && !apiSecret_.isEmpty();
    }

    /** Spot/main trading-account balances (all tickers when {@code ticker} is empty). */
    public JsonNode tradingBalance(String ticker) throws IOException, InterruptedException
    {
        Map<String, String> params = ticker == null || ticker.isEmpty() ? Map.of() : Map.of("ticker", ticker);
        return post(TRADE_BALANCE, params);
    }

    /** Collateral (futures) account balances. */
    public JsonNode collateralBalance() throws IOException, InterruptedException
    {
        return post(COLLATERAL_BALANCE, Map.of());
    }

    /** Collateral (futures) account balance summary: available margin with and without borrowing. */
    public JsonNode collateralSummary() throws IOException, InterruptedException
    {
        return post(COLLATERAL_SUMMARY, Map.of());
    }

    /** Currently open futures positions (empty when flat) -- the broker's reconcile/state read. */
    public JsonNode openPositions() throws IOException, InterruptedException
    {
        return post(OPEN_POSITIONS, Map.of());
    }

    /** Build, sign and POST a private read request to {@code path}; returns the parsed JSON response. */
    private JsonNode post(String path, Map<String, String> params) throws IOException, InterruptedException
    {
        String body = requestBody(path, nextNonce(), params);
        WhiteBitSigner.Signed signed = WhiteBitSigner.sign(body, apiSecret_);
        Map<String, String> headers = Map.of(
                "X-TXC-APIKEY", apiKey_,
                "X-TXC-PAYLOAD", signed.payload(),
                "X-TXC-SIGNATURE", signed.signature());
        return Json.parse(Http.postJson(baseUrl_ + path, signed.body(), headers, TIMEOUT));
    }

    /** Next nonce: unix-millis, forced strictly greater than the previous (WhiteBIT rejects stale/equal). */
    private long nextNonce()
    {
        long now = System.currentTimeMillis();
        return lastNonce_.updateAndGet(previous -> Math.max(now, previous + 1));
    }

    /**
     * The canonical request body: a compact, insertion-ordered JSON object {@code request} then
     * {@code nonce} then any params, no whitespace, nonce as a number &mdash; the exact shape WhiteBIT's
     * documented payload decodes to. Package-private and pure so the serialization can be asserted.
     */
    static String requestBody(String request, long nonce, Map<String, String> params)
    {
        ObjectNode node = Json.newObject();
        node.put("request", request);
        node.put("nonce", nonce);
        for (Map.Entry<String, String> param : params.entrySet())
        {
            node.put(param.getKey(), param.getValue());
        }
        try
        {
            return Json.toCompactString(node);
        }
        catch (JsonProcessingException neverForAnObjectNode)
        {
            throw new IllegalStateException("WhiteBIT request body render failed", neverForAnObjectNode);
        }
    }
}
