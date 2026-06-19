package com.finsent.trade.broker.whitebit;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;

import com.fasterxml.jackson.databind.JsonNode;

import com.finsent.trade.Side;
import com.finsent.trade.broker.BrokerException;
import com.finsent.trade.broker.Fill;
import com.finsent.trade.broker.IBroker;
import com.finsent.trade.broker.OrderSide;
import com.finsent.trade.broker.VenueState;

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
            fill = fillOf(client_.placeCollateralMarketOrder(orderSide(side), amount, ONE_WAY), side, amount);
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

    private static String orderSide(OrderSide side)
    {
        return side == OrderSide.BUY ? "buy" : "sell";
    }
}
