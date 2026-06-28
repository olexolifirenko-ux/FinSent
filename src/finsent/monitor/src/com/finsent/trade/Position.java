package com.finsent.trade;

import java.time.Instant;

import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.core.Json;
import com.finsent.core.Num;
import com.finsent.core.Times;

/**
 * One open paper position and the running trailing-stop state the {@link FSTrader} manages for it.
 * The authoritative size is {@code qty} BTC &mdash; the broker's <b>actual</b> filled quantity, not a
 * notional-derived estimate &mdash; so the P&amp;L on a move is {@code side * qty * (price-entry)},
 * net of round-trip taker fees ({@link #feeUsd}). {@code notionalInUsd} is the margin actually
 * committed ({@code qty * entry / leverage}), used as the denominator for the return-on-margin
 * {@link #pnlPct}. The best price and current stop mutate as price is observed
 * ({@link #updateTrail}); everything else is fixed at entry. Serializes to/from the
 * {@code ObjectNode} shapes the {@link TradeBook} persists (the open-position snapshot and, on exit,
 * a closed-trade record).
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
    // Taker fee per side, as a percent of traded notional (exposure). Charged on both the entry and the exit;
    // pnlUsd is reported net of it. 0 for a costless (paper, zero-fee) fill.
    private final double feeRatePct_;
    private double bestPrice_;
    private double currentStop_;

    private Position(String day, String intervalKey, String source, Side side, double entryPrice, double qty,
            double notionalInUsd, double leverage, Instant openedAt, double initialStop, double bestPrice,
            double currentStop, double feeRatePct)
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
        feeRatePct_ = feeRatePct;
        bestPrice_ = bestPrice;
        currentStop_ = currentStop;
    }

    /**
     * Open a fresh position from the broker's actual filled {@code qty} at {@code entryPrice} (the venue's
     * fill, so the booked size matches what is really held), seeding the best price to entry and the stop to
     * the initial. The committed margin is derived from the fill ({@code qty * entry / leverage}); fills are
     * priced for {@code feeRatePct} taker fee per side.
     */
    public static Position open(String day, String intervalKey, String source, Side side, double entryPrice,
            double qty, double leverage, Instant openedAt, double stopInPct, double feeRatePct)
    {
        double notionalInUsd = leverage == 0.0 ? 0.0 : qty * entryPrice / leverage;
        double initialStop = TrailingStop.initialStop(side, entryPrice, stopInPct);
        return new Position(day, intervalKey, source, side, entryPrice, qty, notionalInUsd, leverage, openedAt,
                initialStop, entryPrice, initialStop, feeRatePct);
    }

    /** Advance the best price and ratchet the stop toward the trade for the newly observed {@code price}. */
    public void updateTrail(double price, double trailInPct)
    {
        bestPrice_ = TrailingStop.bestSoFar(side_, bestPrice_, price);
        currentStop_ = TrailingStop.trail(side_, currentStop_, bestPrice_, trailInPct);
    }

    /** Profit/loss in USD if the position were closed at {@code price}, net of round-trip taker fees. */
    public double pnlUsd(double price)
    {
        return grossPnlUsd(price) - feeUsd(price);
    }

    /** Gross profit/loss in USD on the price move alone (before fees): {@code side * qty * (price-entry)}. */
    public double grossPnlUsd(double price)
    {
        return side_.sign() * qty_ * (price - entryPrice_);
    }

    /**
     * Round-trip taker fees in USD: {@code feeRatePct} of the traded notional on both the entry and the exit
     * ({@code qty * (entry + exit)}). Zero when the fill is costless (paper, zero-fee).
     */
    public double feeUsd(double exitPrice)
    {
        return feeRatePct_ / 100.0 * qty_ * (entryPrice_ + exitPrice);
    }

    /** Return on the committed margin in percent at {@code price}, net of fees. */
    public double pnlPct(double price)
    {
        return notionalInUsd_ == 0.0 ? 0.0 : pnlUsd(price) / notionalInUsd_ * 100.0;
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
        node.put("fee_rate_pct", feeRatePct_);
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
                    node.path("best_price").asDouble(), node.path("current_stop").asDouble(),
                    node.path("fee_rate_pct").asDouble(0.0));
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
        node.put("gross_pnl_usd", Num.round(grossPnlUsd(exitPrice), 2));
        node.put("fee_usd", Num.round(feeUsd(exitPrice), 2));
        node.put("pnl_usd", Num.round(pnlUsd(exitPrice), 2));
        node.put("pnl_pct", Num.round(pnlPct(exitPrice), 4));
        return node;
    }
}
