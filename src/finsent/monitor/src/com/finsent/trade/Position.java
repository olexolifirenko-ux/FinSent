package com.finsent.trade;

import java.time.Instant;

import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.core.Json;
import com.finsent.core.Num;
import com.finsent.core.Times;

/**
 * One open paper position and the running trailing-stop state the {@link FSTrader} manages for it.
 * Exposure is {@code notionalInUsd * leverage}; the BTC quantity is that exposure divided by the
 * entry price, so the P&amp;L on a price move is
 * {@code side * (price-entry)/entry * notionalInUsd * leverage}. The best price and current stop
 * mutate as price is observed ({@link #updateTrail}); everything else is fixed at entry. Serializes
 * to/from the {@code ObjectNode} shapes the {@link TradeBook} persists (the open-position snapshot
 * and, on exit, a closed-trade record).
 */
public final class Position
{
    private final String day_;
    private final String intervalKey_;
    private final String source_;
    private final Side side_;
    private final double entryPrice_;
    private final double qty_;
    private final double notionalInUsd_;
    private final double leverage_;
    private final Instant openedAt_;
    private final double initialStop_;
    private double bestPrice_;
    private double currentStop_;

    private Position(String day, String intervalKey, String source, Side side, double entryPrice, double qty,
            double notionalInUsd, double leverage, Instant openedAt, double initialStop, double bestPrice,
            double currentStop)
    {
        day_ = day;
        intervalKey_ = intervalKey;
        source_ = source;
        side_ = side;
        entryPrice_ = entryPrice;
        qty_ = qty;
        notionalInUsd_ = notionalInUsd;
        leverage_ = leverage;
        openedAt_ = openedAt;
        initialStop_ = initialStop;
        bestPrice_ = bestPrice;
        currentStop_ = currentStop;
    }

    /** Open a fresh position at {@code entryPrice}, seeding the best price to entry and the stop to the initial. */
    public static Position open(String day, String intervalKey, String source, Side side, double entryPrice,
            double notionalInUsd, double leverage, Instant openedAt, double stopInPct)
    {
        double qty = notionalInUsd * leverage / entryPrice;
        double initialStop = TrailingStop.initialStop(side, entryPrice, stopInPct);
        return new Position(day, intervalKey, source, side, entryPrice, qty, notionalInUsd, leverage, openedAt,
                initialStop, entryPrice, initialStop);
    }

    /** Advance the best price and ratchet the stop toward the trade for the newly observed {@code price}. */
    public void updateTrail(double price, double trailInPct)
    {
        bestPrice_ = TrailingStop.bestSoFar(side_, bestPrice_, price);
        currentStop_ = TrailingStop.trail(side_, currentStop_, bestPrice_, trailInPct);
    }

    /** Profit/loss in USD if the position were closed at {@code price}. */
    public double pnlUsd(double price)
    {
        return side_.sign() * (price - entryPrice_) / entryPrice_ * notionalInUsd_ * leverage_;
    }

    /** Return on the committed notional in percent at {@code price} (leverage-scaled). */
    public double pnlPct(double price)
    {
        return side_.sign() * (price - entryPrice_) / entryPrice_ * leverage_ * 100.0;
    }

    public Side side()
    {
        return side_;
    }

    /** The lane that opened it ({@code "news"}/an analyser source, or {@code "momentum"} for a FastMove entry). */
    public String source()
    {
        return source_;
    }

    public String day()
    {
        return day_;
    }

    public double qty()
    {
        return qty_;
    }

    public double entryPrice()
    {
        return entryPrice_;
    }

    public double currentStop()
    {
        return currentStop_;
    }

    public double bestPrice()
    {
        return bestPrice_;
    }

    public Instant openedAt()
    {
        return openedAt_;
    }

    /** The open-position snapshot the trade book persists (and recovers on restart). */
    public ObjectNode toSnapshot()
    {
        ObjectNode node = Json.newObject();
        node.put("day", day_);
        node.put("interval_key", intervalKey_);
        node.put("source", source_);
        node.put("side", side_.name());
        node.put("entry_price", Num.round(entryPrice_, 2));
        node.put("qty", Num.round(qty_, 6));
        node.put("notional_usd", Num.round(notionalInUsd_, 2));
        node.put("leverage", leverage_);
        node.put("opened_at", Times.formatUtcIso(openedAt_));
        node.put("initial_stop", Num.round(initialStop_, 2));
        node.put("best_price", Num.round(bestPrice_, 2));
        node.put("current_stop", Num.round(currentStop_, 2));
        return node;
    }

    /** Rebuild a position from a persisted snapshot (restart recovery); null when the snapshot is flat. */
    public static Position fromSnapshot(ObjectNode node)
    {
        Position position = null;
        if (node != null && node.hasNonNull("side"))
        {
            position = new Position(node.path("day").asText(""), node.path("interval_key").asText(""),
                    node.path("source").asText(""), Side.valueOf(node.path("side").asText("LONG")),
                    node.path("entry_price").asDouble(), node.path("qty").asDouble(),
                    node.path("notional_usd").asDouble(), node.path("leverage").asDouble(1.0),
                    Times.parseIso(node.path("opened_at").asText()), node.path("initial_stop").asDouble(),
                    node.path("best_price").asDouble(), node.path("current_stop").asDouble());
        }
        return position;
    }

    /** The closed-trade ledger record for an exit at {@code exitPrice} with {@code reason}. */
    public ObjectNode toClosedRecord(double exitPrice, Instant closedAt, String reason)
    {
        ObjectNode node = toSnapshot();
        node.put("exit_price", Num.round(exitPrice, 2));
        node.put("closed_at", Times.formatUtcIso(closedAt));
        node.put("close_reason", reason);
        node.put("held_seconds", Math.max(0L, closedAt.getEpochSecond() - openedAt_.getEpochSecond()));
        node.put("pnl_usd", Num.round(pnlUsd(exitPrice), 2));
        node.put("pnl_pct", Num.round(pnlPct(exitPrice), 4));
        return node;
    }
}
