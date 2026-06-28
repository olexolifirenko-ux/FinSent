package com.finsent.trade.broker.whitebit;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
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
    private static final String PUBLIC_TICKER = "/api/v4/public/ticker";
    private static final String TRADE_BALANCE = "/api/v4/trade-account/balance";
    private static final String COLLATERAL_BALANCE = "/api/v4/collateral-account/balance";
    private static final String COLLATERAL_SUMMARY = "/api/v4/collateral-account/balance-summary";
    private static final String OPEN_POSITIONS = "/api/v4/collateral-account/positions/open";
    private static final String ORDER_COLLATERAL_MARKET = "/api/v4/order/collateral/market";
    private static final String ORDER_CANCEL = "/api/v4/order/cancel";
    private static final String ORDER_COLLATERAL_TRIGGER_MARKET = "/api/v4/order/collateral/trigger-market";
    private static final String CONDITIONAL_ORDERS = "/api/v4/conditional-orders";
    private static final String ACTIVE_ORDERS = "/api/v4/orders";

    private final String apiKey_;
    private final String apiSecret_;
    private final String baseUrl_;
    private final String market_;
    private final AtomicLong lastNonce_ = new AtomicLong(0);

    public WhiteBitClient(String apiKey, String apiSecret, String baseUrl, String market)
    {
        apiKey_ = apiKey;
        apiSecret_ = apiSecret;
        baseUrl_ = baseUrl;
        market_ = market;
    }

    /** Whether both API credentials are present (else calls would 401; used to skip the live check). */
    public boolean configured()
    {
        return !apiKey_.isEmpty() && !apiSecret_.isEmpty();
    }

    /**
     * The venue's current last price for the configured market, from the public ticker (no auth). Used as
     * the trader's price feed when executing live on WhiteBIT, so the stop math and the fills read the
     * same venue. Best-effort: returns null on failure so the caller can fall back to another feed.
     */
    public Double lastPrice()
    {
        Double price = null;
        try
        {
            price = parseLastPrice(Http.get(baseUrl_ + PUBLIC_TICKER, Map.of(), Map.of(), TIMEOUT), market_);
        }
        catch (IOException | RuntimeException fetchFailed)
        {
            // Best-effort venue price; the caller falls back to the other feed (the Binance ticker).
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
        }
        return price;
    }

    /** Extract the {@code market}'s {@code last_price} from a WhiteBIT public-ticker body, or null when absent/unparseable. */
    static Double parseLastPrice(String body, String market)
    {
        Double price = null;
        try
        {
            JsonNode last = Json.parse(body).path(market).path("last_price");
            if (last.isValueNode() && !last.asText("").isEmpty())
            {
                price = Double.parseDouble(last.asText());
            }
        }
        catch (JsonProcessingException | NumberFormatException badBody)
        {
            price = null;
        }
        return price;
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

    /** The endpoint a collateral market order posts to (for the dry-run preview display). */
    public String orderUrl()
    {
        return baseUrl_ + ORDER_COLLATERAL_MARKET;
    }

    /**
     * Sign &mdash; but do NOT send &mdash; a collateral market order, for the dry-run preview. Lets the
     * exact order body be inspected before any real order is placed (consumes a nonce, harmless).
     * {@code positionSide} is {@code ""} in one-way mode (omitted) or {@code "LONG"}/{@code "SHORT"} in hedge mode.
     */
    public WhiteBitSigner.Signed previewMarketOrder(String side, String amount, String positionSide)
    {
        String body = requestBody(ORDER_COLLATERAL_MARKET, nextNonce(),
                marketOrderParams(market_, side, amount, positionSide, ""));
        return WhiteBitSigner.sign(body, apiSecret_);
    }

    /**
     * Place a collateral (futures) MARKET order &mdash; <b>this sends a real order</b>. {@code side} is
     * {@code "buy"}/{@code "sell"}, {@code amount} the base (BTC) quantity, {@code positionSide} empty for
     * one-way mode or {@code "LONG"}/{@code "SHORT"} for hedge mode. When {@code stopLoss} is non-empty it is
     * the trigger price of a position-linked protective stop attached to this entry as an OTO bracket (the
     * venue-resting stop); empty places a plain order. Returns the exchange response (carries {@code status},
     * {@code dealStock} filled base, {@code dealMoney} filled quote, {@code orderId}, and the {@code oto} object).
     */
    public JsonNode placeCollateralMarketOrder(String side, String amount, String positionSide, String stopLoss)
            throws IOException, InterruptedException
    {
        return post(ORDER_COLLATERAL_MARKET, marketOrderParams(market_, side, amount, positionSide, stopLoss));
    }

    /**
     * Place a collateral (futures) standalone TRIGGER-MARKET stop &mdash; <b>a real order</b>. {@code side} is
     * {@code "buy"}/{@code "sell"} (opposite the position to protect it), {@code amount} the base (BTC) size,
     * {@code activationPrice} the trigger price, {@code positionSide} empty in one-way mode. Unlike the
     * order-level OTO {@code stopLoss} param, this rests as a conditional order observable via
     * {@link #conditionalOrders()} and cancelable via {@link #cancelOrder}.
     */
    public JsonNode placeCollateralTriggerMarket(String side, String amount, String activationPrice,
            String positionSide) throws IOException, InterruptedException
    {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("market", market_);
        params.put("side", side);
        params.put("amount", amount);
        params.put("activation_price", activationPrice);
        if (positionSide != null && !positionSide.isEmpty())
        {
            params.put("positionSide", positionSide);
        }
        return post(ORDER_COLLATERAL_TRIGGER_MARKET, params);
    }

    /** Cancel a single active order by {@code orderId} on the configured market (used to pull a resting stop). */
    public JsonNode cancelOrder(String orderId) throws IOException, InterruptedException
    {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("market", market_);
        params.put("orderId", orderId);
        return post(ORDER_CANCEL, params);
    }

    /** Active conditional (stop/OCO/OTO) orders for the configured market &mdash; where venue-resting stops live. */
    public JsonNode conditionalOrders() throws IOException, InterruptedException
    {
        return post(CONDITIONAL_ORDERS, Map.of("market", market_));
    }

    /** Active (unfilled) orders for the configured market &mdash; the other place a resting stop could surface. */
    public JsonNode activeOrders() throws IOException, InterruptedException
    {
        return post(ACTIVE_ORDERS, Map.of("market", market_));
    }

    /**
     * Ordered market-order params (insertion order kept so the signed body is deterministic/testable);
     * {@code positionSide} (hedge mode) and {@code stopLoss} (OTO bracket trigger price) are each appended
     * only when non-empty, so a one-way plain order serializes exactly as before.
     */
    static Map<String, String> marketOrderParams(String market, String side, String amount, String positionSide,
            String stopLoss)
    {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("market", market);
        params.put("side", side);
        params.put("amount", amount);
        if (positionSide != null && !positionSide.isEmpty())
        {
            params.put("positionSide", positionSide);
        }
        if (stopLoss != null && !stopLoss.isEmpty())
        {
            params.put("stopLoss", stopLoss);
        }
        return params;
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
