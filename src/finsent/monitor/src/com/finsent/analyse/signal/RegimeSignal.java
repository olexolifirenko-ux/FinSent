package com.finsent.analyse.signal;

import java.util.Locale;

/**
 * Mechanical multi-day BTC regime read for the deep pass. Emits a {@code btc_regime} line ONLY when the
 * tape is EXTENDED &mdash; a deep drawdown from the {@value #REGIME_DAYS}-day high AND sitting near the
 * multi-day low &mdash; the regime in which a broad risk-off is already largely PRICED and the down-fuel
 * spent, so a further systemic shock is a partly-priced continuation (the deep prompt demotes it to LOW).
 *
 * <p>Deliberately silent otherwise: a line showing a modest drawdown wrongly nudges the model to read a
 * fresh shock as an already-priced continuation, so a non-extended regime adds nothing and is omitted
 * (the prompt then judges normally). Pure: the caller supplies the current price and the multi-day
 * high/low; this only classifies and formats.
 */
public final class RegimeSignal
{
    /** Lookback for the multi-day regime read (calendar days). */
    public static final int REGIME_DAYS = 5;
    /** EXTENDED needs the drawdown from the multi-day high at or below this (a deep multi-day sell-off). */
    private static final double DRAWDOWN_EXTENDED_PCT = -6.0;
    /** ...AND the current price at or below this position in the multi-day low->high range (near the low). */
    private static final double RANGE_POS_EXTENDED = 0.30;

    private RegimeSignal()
    {
    }

    /**
     * The {@code btc_regime} line for {@code market_signals}, or {@code ""} when not extended (or the
     * inputs are unusable). {@code high}/{@code low} are the max-high/min-low over the {@value #REGIME_DAYS}
     * -day window ending at the analysis instant; {@code current} is the window's BTC price.
     */
    public static String line(double current, double high, double low)
    {
        String result = "";
        if (current > 0.0 && high > 0.0)
        {
            double drawdownPct = (current - high) / high * 100.0;
            double rangePos = high > low ? (current - low) / (high - low) : 0.5;
            if (drawdownPct <= DRAWDOWN_EXTENDED_PCT && rangePos <= RANGE_POS_EXTENDED)
            {
                result = String.format(Locale.ROOT,
                        "btc_regime: %+.1f%% from %dd-high $%.0f, near the %dd low -- EXTENDED multi-day "
                                + "sell-off (the broad risk-off is largely PRICED in)",
                        drawdownPct, REGIME_DAYS, high, REGIME_DAYS);
            }
        }
        return result;
    }
}
