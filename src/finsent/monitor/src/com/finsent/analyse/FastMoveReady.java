package com.finsent.analyse;

import java.time.Instant;

/**
 * Published on the collector-owned event bus when the {@code FastMovePoller} detects a sharp, clean BTC
 * price move on its own tape -- the mechanical, news-independent sibling of {@link AnalysisReady}. It is
 * a momentum signal, not a news verdict: it carries no catalyst time (so the trader's news-freshness gate
 * does not apply to it) and a {@code conviction} set by the funding/OI structural gate ({@code full} =
 * fresh positioning building into the move = a real trend; {@code reduced} = unconfirmed; {@code skip} =
 * positions unwinding = a likely transient wick, do not trade). The trader consumes it through the same
 * single-position gate as news, with its own (tighter/smaller) entry params.
 *
 * @param day          the {@code YYYYMMDD} day the move fired on.
 * @param intervalKey  the {@code HH:MM} interval key at fire time.
 * @param direction    the move's lean ({@code bullish} / {@code bearish}).
 * @param conviction   the funding/OI structural read ({@code full} / {@code reduced} / {@code skip}).
 * @param anchorPrice  the live price at fire time (the trader's divergence rail anchors on it).
 * @param magnitudePct the endpoint move of the winning window (signed percent).
 * @param r2           the regression fit of the winning window (cleanliness).
 * @param spanMinutes  the lookback span of the winning window.
 * @param setup        the funding/OI setup label at fire time (e.g. {@code up_squeeze_fuel}), for logs.
 * @param firedAt      when the move fired.
 */
public record FastMoveReady(String day, String intervalKey, String direction, String conviction,
        Double anchorPrice, double magnitudePct, double r2, int spanMinutes, String setup, Instant firedAt)
{
}
