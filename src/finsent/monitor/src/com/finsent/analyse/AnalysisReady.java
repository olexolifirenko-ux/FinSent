package com.finsent.analyse;

import java.time.Instant;

/**
 * Published on the collector-owned event bus once the analyser has recorded a window's deep
 * {@code prediction_record} (the Java signal a downstream consumer &mdash; e.g. the trading module
 * &mdash; subscribes to instead of polling the analysis store). It carries only the decision inputs
 * a consumer acts on: the interval the analysis is for, the directional lean and materiality tier,
 * and the BTC price anchor at prediction time. Emitted from {@code FSAnalyser.runDeepAnalysis} after
 * {@code store_.record}; the screener-only path (no deep prediction) does not publish.
 *
 * @param day         the {@code YYYYMMDD} day of the interval.
 * @param intervalKey the {@code HH:MM} interval key.
 * @param source      the analysis lane that produced it ({@code "news"} for v1).
 * @param direction   the directional lean ({@code bullish} / {@code bearish} / {@code neutral}).
 * @param impactTier  the materiality tier ({@code high} / {@code low} / {@code noise}).
 * @param anchorPrice the BTC price at prediction time ({@code btc_at_prediction}), null when absent.
 * @param analyzedAt  when the analysis was stamped.
 */
public record AnalysisReady(String day, String intervalKey, String source, String direction,
        String impactTier, Double anchorPrice, Instant analyzedAt)
{
}
