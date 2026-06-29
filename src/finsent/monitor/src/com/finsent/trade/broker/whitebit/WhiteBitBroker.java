package com.finsent.trade.broker.whitebit;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import com.finsent.trade.Side;
import com.finsent.trade.broker.BrokerException;
import com.finsent.trade.broker.Fill;
import com.finsent.trade.broker.IBroker;
import com.finsent.trade.broker.OrderSide;
import com.finsent.trade.broker.VenueState;
import com.finsent.util.GlobalSystem;

/**
 * The live {@link IBroker}: places WhiteBIT collateral (futures) MARKET orders through
 * {@link WhiteBitClient} and reads the venue's open position for reconciliation. Selected by
 * {@code broker=whitebit} in config; the paper broker is the default. A {@code BUY}/{@code SELL} of a
 * BTC quantity is sent at the market and the broker reports the venue's <b>actual</b> fill
 * ({@code dealMoney/dealStock}), not the price the strategy guessed. Anything other than a filled
 * order &mdash; rejection, insufficient margin, missing permission, network/auth error &mdash;
 * surfaces as a {@link BrokerException} so the trader does not book a phantom position.
 *
 * <p>Operates in one-way (net) account mode: {@code positionSide} is omitted from every order, and
 * reconciliation expects at most one net position per market.
 */
public final class WhiteBitBroker implements IBroker
{
    private static final String NAME = "whitebit";
    // BTC_USDT base-amount precision on WhiteBIT (4 dp / 0.0001 BTC step); orders must match it, and we
    // round DOWN so a sized order never exceeds the margin the strategy budgeted for it.
    private static final int AMOUNT_SCALE = 4;
    private static final RoundingMode AMOUNT_ROUNDING = RoundingMode.DOWN;
    // BTC_USDT money (price) precision on WhiteBIT; the protective-stop trigger is rounded to it.
    private static final int PRICE_SCALE = 2;
    private static final String FILLED = "FILLED";
    private static final String ONE_WAY = ""; // one-way (net) mode: positionSide omitted.

    private final WhiteBitClient client_;

    public WhiteBitBroker(WhiteBitClient client)
    {
        client_ = client;
    }

    @Override
    public Fill marketOrder(OrderSide side, double qty, double price) throws BrokerException
    {
        String amount = formatAmount(qty);
        Fill fill;
        try
        {
            fill = fillOf(client_.placeCollateralMarketOrder(orderSide(side), amount, ONE_WAY, ""), side, amount);
        }
        catch (IOException orderFailed)
        {
            // Rejection / insufficient margin / missing Order-management permission all surface here.
            throw new BrokerException("WhiteBIT " + side + " " + amount + " BTC failed: " + orderFailed.getMessage(),
                    orderFailed);
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
            throw new BrokerException("WhiteBIT " + side + " order interrupted", interrupted);
        }
        return fill;
    }

    /**
     * Entry, then &mdash; when {@code protectiveStopPrice > 0} &mdash; a SEPARATE standalone
     * {@code trigger-market} stop (opposite side, the actual filled size, trigger at that price) so the
     * position carries a venue-resting stop that survives a crash. The stop is a second call after the entry
     * fills, so a failure to place it does NOT unwind the (already open) position &mdash; it is logged loudly
     * and the app-side stop still manages the exit. The order-level OTO {@code stopLoss} param is deliberately
     * NOT used: a probe proved it produces a stop the venue acknowledges but does not surface anywhere
     * queryable (so it cannot be observed or cancelled); a standalone {@code trigger-market} shows in the
     * active-orders list and is cancelable by id.
     */
    @Override
    public Fill marketOrder(OrderSide side, double qty, double price, double protectiveStopPrice)
            throws BrokerException
    {
        Fill fill = marketOrder(side, qty, price);
        if (protectiveStopPrice > 0.0)
        {
            placeProtectiveStop(side, fill.qty(), protectiveStopPrice);
        }
        return fill;
    }

    /** Place the standalone protective stop opposite the entry; a failure leaves the open position intact
     *  (logged, not thrown -- the entry already filled and the app-side stop still manages the exit). */
    private void placeProtectiveStop(OrderSide entrySide, double qty, double triggerPrice)
    {
        OrderSide stopSide = entrySide == OrderSide.BUY ? OrderSide.SELL : OrderSide.BUY;
        try
        {
            placeStop(stopSide, qty, triggerPrice);
        }
        catch (BrokerException stopFailed)
        {
            GlobalSystem.warning().writes(NAME, "Venue protective stop NOT placed (position is OPEN; the app-side "
                    + "stop still manages the exit): " + stopFailed.getMessage());
        }
    }

    /**
     * Trail the venue stop to a new level: cancel the resting stop and place a fresh {@code trigger-market} at
     * {@code triggerPrice} on {@code closeSide} (the side that closes the position). Cancel-then-place reuses
     * the already-validated place/cancel calls (no unproven {@code /order/modify}); the brief window with no
     * resting stop is acceptable since the app is alive and re-places immediately, and the app-side stop still
     * manages the exit. Surfaced as a {@link BrokerException} so the trader can keep managing on a failure.
     */
    @Override
    public void amendProtectiveStop(OrderSide closeSide, double qty, double triggerPrice) throws BrokerException
    {
        cancelProtectiveStops();
        placeStop(closeSide, qty, triggerPrice);
    }

    /** Place one standalone {@code trigger-market} stop ({@code stopSide} closes the position) at {@code triggerPrice}. */
    private void placeStop(OrderSide stopSide, double qty, double triggerPrice) throws BrokerException
    {
        try
        {
            client_.placeCollateralTriggerMarket(orderSide(stopSide), formatAmount(qty), formatPrice(triggerPrice),
                    ONE_WAY);
        }
        catch (IOException stopFailed)
        {
            throw new BrokerException("WhiteBIT protective stop placement failed: " + stopFailed.getMessage(),
                    stopFailed);
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
            throw new BrokerException("WhiteBIT protective stop placement interrupted", interrupted);
        }
    }

    /**
     * Cancel every active stop/trigger order on the market &mdash; the trader's only resting orders are the
     * protective stops it placed (filtered by type so a stray manual limit order is left alone). Called before
     * a deliberate close so the close can never leave a naked stop. A {@code code 2} ("order not found") from a
     * stop that already fired is benign and surfaces only if the whole read fails.
     */
    @Override
    public void cancelProtectiveStops() throws BrokerException
    {
        try
        {
            for (String orderId : protectiveStopIds(client_.activeOrders()))
            {
                client_.cancelOrder(orderId);
            }
        }
        catch (IOException cancelFailed)
        {
            throw new BrokerException("WhiteBIT cancel protective stops failed: " + cancelFailed.getMessage(),
                    cancelFailed);
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
            throw new BrokerException("WhiteBIT cancel protective stops interrupted", interrupted);
        }
    }

    /**
     * The order ids of the resting stop/trigger orders to cancel, read from an active-orders response (a bare
     * JSON array, or a {@code records}-wrapped one). Only orders whose {@code type} names a stop/trigger are
     * returned, so an unrelated resting limit order is not cancelled. Empty when there are none.
     */
    static List<String> protectiveStopIds(JsonNode activeOrders)
    {
        List<String> ids = new ArrayList<>();
        JsonNode orders = ordersArray(activeOrders);
        if (orders != null)
        {
            for (JsonNode order : orders)
            {
                String type = order.path("type").asText("").toLowerCase();
                String id = order.path("orderId").asText(order.path("id").asText(""));
                if (!id.isEmpty() && (type.contains("trigger") || type.contains("stop")))
                {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    /** The orders array from an active-orders response: the node itself if an array, else its {@code records}. */
    private static JsonNode ordersArray(JsonNode response)
    {
        JsonNode orders = null;
        if (response != null && response.isArray())
        {
            orders = response;
        }
        else if (response != null && response.path("records").isArray())
        {
            orders = response.path("records");
        }
        return orders;
    }

    @Override
    public String name()
    {
        return NAME;
    }

    @Override
    public VenueState venueState() throws BrokerException
    {
        VenueState state;
        try
        {
            state = parsePositions(client_.openPositions());
        }
        catch (IOException readFailed)
        {
            throw new BrokerException("WhiteBIT open-positions read failed: " + readFailed.getMessage(), readFailed);
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
            throw new BrokerException("WhiteBIT open-positions read interrupted", interrupted);
        }
        return state;
    }

    /** Map the venue's open-positions response to a {@link VenueState} (flat, or the one net position). */
    static VenueState parsePositions(JsonNode positions)
    {
        VenueState state = VenueState.flat();
        if (positions != null && positions.isArray() && !positions.isEmpty())
        {
            JsonNode position = positions.get(0); // one net position per market in one-way mode
            double amount = position.path("amount").asDouble(0.0);
            if (amount != 0.0)
            {
                Side side = amount > 0.0 ? Side.LONG : Side.SHORT;
                double entryPrice = position.path("basePrice").asDouble(0.0);
                state = VenueState.open(side, Math.abs(amount), entryPrice, effectiveLeverage(amount, position));
            }
        }
        return state;
    }

    /** Effective leverage of an open position: exposure (|amount| x basePrice) over the posted margin, or 0 if unknown. */
    private static double effectiveLeverage(double amount, JsonNode position)
    {
        double margin = position.path("margin").asDouble(0.0);
        double exposure = Math.abs(amount) * position.path("basePrice").asDouble(0.0);
        return margin > 0.0 ? exposure / margin : 0.0;
    }

    /** Read the venue's actual fill out of an order response, or fail if it was not filled. */
    static Fill fillOf(JsonNode response, OrderSide side, String requested) throws BrokerException
    {
        String status = response.path("status").asText("");
        double filled = response.path("dealStock").asDouble(0.0);
        double cost = response.path("dealMoney").asDouble(0.0);
        if (!FILLED.equalsIgnoreCase(status) || filled <= 0.0)
        {
            throw new BrokerException("WhiteBIT " + side + " " + requested + " BTC not filled (status=" + status
                    + "): " + response);
        }
        return new Fill(cost / filled, filled);
    }

    /** Format a BTC quantity to the market's amount precision, rounding down to stay within budget. */
    static String formatAmount(double qty)
    {
        return BigDecimal.valueOf(qty).setScale(AMOUNT_SCALE, AMOUNT_ROUNDING).toPlainString();
    }

    /** Format a price (the protective-stop trigger) to the market's money precision. */
    static String formatPrice(double price)
    {
        return BigDecimal.valueOf(price).setScale(PRICE_SCALE, RoundingMode.HALF_UP).toPlainString();
    }

    private static String orderSide(OrderSide side)
    {
        return side == OrderSide.BUY ? "buy" : "sell";
    }
}
