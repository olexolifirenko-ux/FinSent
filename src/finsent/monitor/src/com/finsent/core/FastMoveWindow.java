package com.finsent.core;

/**
 * One detection window for the FastMove price-tape trigger: a lookback {@code spanMinutes} of recent
 * 1-minute closes, the {@code thresholdPct} the endpoint move must clear (in either direction) to fire,
 * and the {@code r2Floor} the linear-regression fit must clear so a clean trend fires but choppy drift
 * does not. Each window carries its own threshold because the bar that counts as a real move scales with
 * the span (a -1% over 5m is a spike; a -1% over 60m is noise). Built from the {@code <FSFastMove><Windows>}
 * config list (see {@link Config#fastMoveWindows()}).
 */
public record FastMoveWindow(int spanMinutes, double thresholdPct, double r2Floor)
{
}
